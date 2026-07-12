# Architecture

This document explains how SMS Relayer is structured and, more importantly, *why*.
The app is small, but it solves a hard problem — **guaranteed background delivery on
modern, non-rooted Android** — so most decisions below are about reliability.

## Layering

```text
ui/  ─────────────►  domain/  ◄─────────────  data/
(Compose,            (pure Kotlin:            (Room, DataStore,
 ViewModels)          models, use cases,       Keystore, OkHttp)
                      *interfaces*)
        entry points (receiver/service/workers) ──► domain/
```

- **`domain/` owns the contracts.** Repository and gateway *interfaces* live in the
  domain layer; `data/` implements them. The dependency arrow points inward
  (dependency inversion), which keeps the entire business core free of Android
  imports and therefore unit-testable on the JVM.
- **`data/` owns the mechanics.** Room entities/DAOs, DataStore preferences, the
  Android Keystore cipher and the Telegram HTTP client are implementation details
  invisible to the rest of the app.
- **`ui/` is MVVM.** One ViewModel per screen, exposing a single immutable
  `UiState` via `StateFlow` plus a `Channel`-based stream of one-shot snackbar
  messages (as string-resource IDs, so ViewModels stay Context-free).

### Dependency injection: a deliberate non-framework

All wiring happens in one place, [`di/AppContainer.kt`](../app/src/main/java/io/github/bjspi/smsrelayer/di/AppContainer.kt),
held by the `Application`. Every entry point — activity, broadcast receiver,
service, worker — resolves its dependencies from this container.

For an app with one module and ~15 collaborators, a full DI framework (Hilt)
would add annotation processing, build time and indirection without buying
anything. The container gives the same testability (constructor injection
everywhere) with zero magic: you can read the entire object graph in one file.
Everything is `lazy`, so a broadcast wake-up that only touches the database
never pays for an HTTP client.

## The delivery pipeline

The core invariant: **an SMS is persisted before anything else happens, and the
queue is the single source of truth for outstanding work.**

```text
SMS broadcast
     │  SmsReceiver (≤ 8 s budget, no network)
     ▼
 1. parse PDUs (multipart merge, dual-SIM best effort)
 2. INSERT SmsEvent
 3. INSERT one RelayQueueItem per enabled target   ← fan-out
     │
     ├──► WorkManager: expedited, network-constrained drain   (guaranteed path)
     └──► poke foreground service                             (fast path)
                    │
                    ▼
          ProcessRelayQueue (single shared instance)
          loop:
            claimDue(now, batch)      ← transactional Pending→Sending flip
            deliver with ≤ 3 parallel sends
            Sent | FailedRetryable(backoff) | FailedPermanent
```

### Why the fan-out?

One queue item per (SMS × target) means a broken chat (revoked bot admin,
deleted group) can never block delivery to healthy chats, and each target gets
its own retry schedule.

### Why transactional claiming?

The drain can be triggered concurrently from four places (receiver, worker,
service heartbeat, UI button). Two guards make this safe:

1. an in-process `Mutex` in `ProcessRelayQueue` collapses concurrent drains, and
2. `RelayQueueDao.claimDue()` selects due items **and** flips them to `Sending`
   inside a single Room `@Transaction` — even two independent processors could
   not claim the same row twice.

Items stranded in `Sending` by a process death are returned to `Pending` by the
watchdog (`recoverStaleSending`, 10-minute cutoff), so a crash mid-send delays a
message but never loses it.

### Error classification

The Telegram gateway maps every `sendMessage` response onto a three-state
outcome **before** it reaches the queue, so the queue never parses HTTP:

| Response | Outcome | Queue behavior |
|---|---|---|
| `ok: true` | `Delivered` | mark `Sent` |
| network error, 5xx | `RetryLater` | backoff 1 → 5 → 15 → 30 min (then every 30 min, forever) |
| 429 | `RetryLater(retry_after)` | server-requested delay wins if longer than backoff |
| 400 / 401 / 403 / 404 | `Rejected` | `FailedPermanent` — retrying an invalid chat ID or revoked token can never succeed |

## Staying alive without root

Three sanctioned mechanisms are layered so no single OS intervention can stop
the relay:

1. **Foreground service** (`RelayForegroundService`) — the fast path. Calls
   `startForeground()` synchronously in `onCreate` with a placeholder
   notification (zero I/O on the main thread), then drives the notification
   content *reactively* from Room/DataStore flows. A 5-minute internal loop
   updates the heartbeat and opportunistically drains retry backoffs.
   - On API 34+ the service runs with the **`specialUse`** type: the common
     `dataSync` type is capped at 6 h/day since Android 15, which would silently
     kill an always-on relay. `dataSync` remains declared for API 29–33.
   - Android 12+ forbids service starts from most background contexts. Every
     start goes through `RelayServiceController`, which catches the refusal,
     logs it and falls back to WorkManager instead of crashing.
2. **WorkManager** — the guaranteed path. Each incoming SMS enqueues an
   *expedited*, network-constrained drain. The unique work uses `KEEP` —
   expedited work must not be part of a chain, which rules out the APPEND
   policies, and `REPLACE` would cancel a drain mid-delivery. The worker closes
   the resulting enqueue race itself by re-draining while fresh pending items
   exist. Retries use WorkManager's exponential backoff and fire exactly when
   connectivity returns.
3. **Watchdog** — the safety net. A 15-minute periodic worker (platform minimum)
   recovers stale claims, flags dead heartbeats, restarts the service and
   re-drains. The boot receiver re-registers everything after reboot / app
   update (`BOOT_COMPLETED` is one of the few contexts still allowed to start a
   foreground service directly).

## Security model

- **Token at rest:** AES-256/GCM with a Keystore-resident key
  (`data/crypto/TokenCipher`). Payload format `v1:<iv>:<ciphertext>` allows
  future rotation. Decryption failure (backup restore onto another device wipes
  Keystore keys) degrades gracefully to "no token configured".
- **Token in transit through logs:** the token is embedded in Bot API URLs, so
  every exception message that could contain a URL passes through a redaction
  step in the gateway before leaving the class.
- **Log hygiene:** `EventLog.log()` is contractually infallible (diagnostics
  must never break the operation being diagnosed) and stores only compacted,
  truncated previews of SMS bodies.
- **Permission minimalism:** no `READ_SMS` (the receiver gets everything it
  needs from the broadcast), `READ_PHONE_STATE` only for optional dual-SIM
  labeling.

## Testing strategy

The domain layer's purity pays off here: all business rules run as plain JVM
tests, no emulator required.

- **Use cases** (`ProcessRelayQueueTest`, `EnqueueIncomingSmsTest`) run against
  in-memory fakes that mirror the DAO's claim semantics, plus a `FixedClock` —
  backoff timing is asserted deterministically.
- **HTTP layer** (`TelegramHttpGatewayTest`) runs the real OkHttp +
  kotlinx.serialization pipeline against **MockWebServer**: envelope parsing,
  the full error-classification table, `retry_after` extraction, chat
  deduplication and token redaction.
- **Pure functions** (formatter/HTML escaping, backoff policy, token validator,
  text utilities) are covered directly.

## Notable trade-offs

- **Light-only theme** — the app targets dedicated relay devices; one
  high-contrast theme keeps status colors unambiguous.
- **No Paging for logs** — the log table is capped at 10 000 rows (trimmed
  amortized, every 64th insert) and the UI reads at most 500; Paging would add
  a dependency for a list that small.
- **`getUpdates`-based chat discovery** — long polling or webhooks would be
  overkill; discovery is a one-shot convenience during setup.
- **Sequential test messages** — test sends are user-triggered and rare;
  parallelizing them would only complicate result reporting.
