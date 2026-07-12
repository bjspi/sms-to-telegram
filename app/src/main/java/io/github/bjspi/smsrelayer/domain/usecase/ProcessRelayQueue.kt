package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.QueueStatus
import io.github.bjspi.smsrelayer.domain.model.RelayQueueItem
import io.github.bjspi.smsrelayer.domain.repository.EventLog
import io.github.bjspi.smsrelayer.domain.repository.RelayQueueRepository
import io.github.bjspi.smsrelayer.domain.repository.SettingsRepository
import io.github.bjspi.smsrelayer.domain.repository.SmsEventRepository
import io.github.bjspi.smsrelayer.domain.telegram.TelegramGateway
import io.github.bjspi.smsrelayer.domain.telegram.TelegramMessageFormatter
import io.github.bjspi.smsrelayer.domain.telegram.TelegramSendOutcome
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import io.github.bjspi.smsrelayer.domain.util.compactSingleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Drains the relay queue: claims due items in batches, delivers them to
 * Telegram with bounded parallelism, and writes the resulting state
 * transitions back.
 *
 * Safe to trigger from any number of entry points at once (SMS receiver,
 * WorkManager, foreground service, UI buttons):
 *  - an in-process [Mutex] collapses concurrent invocations into one drain, and
 *  - [RelayQueueRepository.claimDue] flips items to `Sending` transactionally,
 *    so even a hypothetical second processor could never double-send.
 */
class ProcessRelayQueue(
    private val settings: SettingsRepository,
    private val smsEvents: SmsEventRepository,
    private val relayQueue: RelayQueueRepository,
    private val telegram: TelegramGateway,
    private val formatter: TelegramMessageFormatter,
    private val eventLog: EventLog,
    private val clock: Clock,
) {

    data class Summary(
        val sent: Int = 0,
        val retryable: Int = 0,
        val failedPermanently: Int = 0,
    ) {
        val processed: Int get() = sent + retryable + failedPermanently
        val fullyDrained: Boolean get() = retryable == 0
    }

    private val drainMutex = Mutex()

    suspend operator fun invoke(): Summary = drainMutex.withLock {
        val token = settings.settings.first().telegramBotToken?.trim().orEmpty()
        if (token.isEmpty()) {
            eventLog.warn(LogCategory.Queue, "Queue processing skipped: bot token missing")
            return Summary()
        }

        var summary = Summary()
        while (true) {
            val batch = relayQueue.claimDue(now = clock.now(), limit = BATCH_SIZE)
            if (batch.isEmpty()) break

            val results = coroutineScope {
                val gate = Semaphore(MAX_PARALLEL_SENDS)
                batch.map { item ->
                    async { gate.withPermit { deliver(item, token) } }
                }.awaitAll()
            }

            summary = results.fold(summary) { acc, status ->
                when (status) {
                    QueueStatus.Sent -> acc.copy(sent = acc.sent + 1)
                    QueueStatus.FailedPermanent -> acc.copy(failedPermanently = acc.failedPermanently + 1)
                    else -> acc.copy(retryable = acc.retryable + 1)
                }
            }
            // No early exit on failures: a rate-limited chat must not starve
            // healthy targets further back in the queue. The loop still
            // terminates — every failed item leaves the batch with a future
            // nextAttemptAt, so each due item is claimed at most once per drain.
        }

        if (summary.processed > 0) {
            eventLog.info(
                category = LogCategory.Queue,
                message = "Queue drain finished",
                details = "sent=${summary.sent}, retryable=${summary.retryable}, " +
                    "failedPermanently=${summary.failedPermanently}",
            )
        }
        summary
    }

    /** Delivers one claimed item and persists its final state. Never throws. */
    private suspend fun deliver(item: RelayQueueItem, token: String): QueueStatus {
        val outcome = try {
            val sms = smsEvents.getById(item.smsEventId)
                ?: return finalize(item, QueueStatus.FailedPermanent, "SMS event no longer exists")
            telegram.sendMessage(token, item.targetChatId, formatter.formatSmsMessage(sms))
        } catch (e: CancellationException) {
            // Return the claim so a cancelled drain (e.g. worker stopped) never
            // strands the item in `Sending` until stale-recovery kicks in.
            finalize(item, QueueStatus.Pending, error = null)
            throw e
        } catch (e: Exception) {
            TelegramSendOutcome.RetryLater(httpCode = null, reason = e.compactMessage())
        }

        return when (outcome) {
            is TelegramSendOutcome.Delivered -> {
                settings.updateLastSuccessfulTelegramSendAt(clock.now())
                finalize(item, QueueStatus.Sent, error = null)
            }

            is TelegramSendOutcome.RetryLater -> {
                val attempts = item.attemptCount + 1
                val backoff = RelayBackoffPolicy.delayAfter(attempts)
                val delay = outcome.retryAfter?.coerceAtLeast(backoff) ?: backoff
                finalize(
                    item.copy(attemptCount = attempts, nextAttemptAt = clock.now() + delay.inWholeMilliseconds),
                    QueueStatus.FailedRetryable,
                    outcome.reason,
                )
            }

            is TelegramSendOutcome.Rejected ->
                finalize(
                    item.copy(attemptCount = item.attemptCount + 1),
                    QueueStatus.FailedPermanent,
                    outcome.reason,
                )
        }
    }

    /**
     * Persists the item's final state. Runs [NonCancellable] so that a drain
     * cancelled mid-flight (worker stopped, service killed) still writes the
     * state back — most importantly returning a claimed item to `Pending`
     * instead of stranding it in `Sending`.
     */
    private suspend fun finalize(
        item: RelayQueueItem,
        status: QueueStatus,
        error: String?,
    ): QueueStatus = withContext(NonCancellable) {
        finalizeInternal(item, status, error)
    }

    private suspend fun finalizeInternal(item: RelayQueueItem, status: QueueStatus, error: String?): QueueStatus {
        val now = clock.now()
        try {
            relayQueue.update(
                item.copy(
                    status = status,
                    lastError = error?.compactSingleLine(MAX_ERROR_CHARS),
                    updatedAt = now,
                ),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            eventLog.error(
                category = LogCategory.Queue,
                message = "Failed to persist queue item state",
                details = "queueId=${item.id}, targetStatus=$status, error=${e.compactMessage()}",
            )
            return status
        }

        when (status) {
            QueueStatus.Sent -> eventLog.info(
                LogCategory.Queue,
                "Relay item delivered",
                "queueId=${item.id}, smsId=${item.smsEventId}, target=${item.targetDisplayName}",
            )

            QueueStatus.FailedRetryable -> eventLog.warn(
                LogCategory.Queue,
                "Relay item failed, will retry",
                "queueId=${item.id}, attempt=${item.attemptCount}, target=${item.targetDisplayName}, error=$error",
            )

            QueueStatus.FailedPermanent -> eventLog.error(
                LogCategory.Queue,
                "Relay item failed permanently",
                "queueId=${item.id}, target=${item.targetDisplayName}, error=$error",
            )

            else -> Unit
        }
        return status
    }

    private companion object {
        const val BATCH_SIZE = 10
        const val MAX_PARALLEL_SENDS = 3
        const val MAX_ERROR_CHARS = 240
    }
}
