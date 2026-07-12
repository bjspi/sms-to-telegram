package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.QueueStatus
import io.github.bjspi.smsrelayer.domain.model.RelayQueueItem
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.domain.repository.ChatTargetRepository
import io.github.bjspi.smsrelayer.domain.repository.EventLog
import io.github.bjspi.smsrelayer.domain.repository.RelayQueueRepository
import io.github.bjspi.smsrelayer.domain.repository.SettingsRepository
import io.github.bjspi.smsrelayer.domain.repository.SmsEventRepository
import io.github.bjspi.smsrelayer.domain.util.compactSingleLine

/**
 * Persists an incoming SMS and fans it out into one queue item per enabled
 * chat target. Persistence happens before any delivery attempt, so an SMS can
 * never be lost to a crash, missing network, or process death — the queue is
 * the single source of truth for outstanding work.
 */
class EnqueueIncomingSms(
    private val smsEvents: SmsEventRepository,
    private val chatTargets: ChatTargetRepository,
    private val relayQueue: RelayQueueRepository,
    private val settings: SettingsRepository,
    private val eventLog: EventLog,
    private val clock: Clock,
) {

    data class Outcome(val sms: SmsEvent, val enqueuedTargets: Int)

    suspend operator fun invoke(sms: SmsEvent): Outcome {
        val stored = if (sms.id == 0L) smsEvents.save(sms) else sms
        settings.updateLastSmsReceivedAt(stored.receivedAt)

        eventLog.info(
            category = LogCategory.Sms,
            message = "SMS received",
            details = "smsId=${stored.id}, sender=${stored.sender}, " +
                "preview=\"${stored.body.compactSingleLine(PREVIEW_CHARS)}\"",
        )

        val targets = chatTargets.getEnabled()
        if (targets.isEmpty()) {
            eventLog.warn(
                category = LogCategory.Queue,
                message = "SMS not relayed: no enabled chat targets",
                details = "smsId=${stored.id}",
            )
            return Outcome(stored, enqueuedTargets = 0)
        }

        val now = clock.now()
        val enqueued = relayQueue.enqueue(
            targets.map { target ->
                RelayQueueItem(
                    id = 0L,
                    smsEventId = stored.id,
                    targetChatId = target.chatId,
                    targetDisplayName = target.displayName,
                    status = QueueStatus.Pending,
                    attemptCount = 0,
                    nextAttemptAt = now,
                    lastError = null,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )

        eventLog.info(
            category = LogCategory.Queue,
            message = "SMS queued for relay",
            details = "smsId=${stored.id}, targets=${enqueued.size}",
        )

        return Outcome(stored, enqueuedTargets = enqueued.size)
    }

    private companion object {
        const val PREVIEW_CHARS = 80
    }
}
