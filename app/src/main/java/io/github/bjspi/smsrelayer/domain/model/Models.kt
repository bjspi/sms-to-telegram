package io.github.bjspi.smsrelayer.domain.model

/**
 * Persisted app configuration. The bot token is stored encrypted at rest and is
 * only exposed decrypted through this model.
 */
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val deviceName: String = "",
    val telegramBotToken: String? = null,
    val lastSuccessfulTelegramSendAt: Long? = null,
    val lastSmsReceivedAt: Long? = null,
    val lastServiceHeartbeatAt: Long? = null,
)

/** A Telegram chat that incoming SMS are relayed to. */
data class TelegramChatTarget(
    val id: Long,
    val displayName: String,
    val chatId: String,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTestAt: Long?,
    val lastTestSuccessful: Boolean?,
)

/** An SMS captured from the system broadcast, fully assembled from its parts. */
data class SmsEvent(
    val id: Long,
    val receivedAt: Long,
    val sender: String,
    val recipientNumber: String?,
    val simSlot: Int?,
    val subscriptionId: Int?,
    val deviceName: String,
    val body: String,
    val rawDebugInfo: String?,
)

/**
 * One pending delivery of one [SmsEvent] to one chat target. Every SMS fans out
 * into exactly one queue item per enabled target, so a slow or broken chat never
 * blocks delivery to the others.
 */
data class RelayQueueItem(
    val id: Long,
    val smsEventId: Long,
    val targetChatId: String,
    val targetDisplayName: String,
    val status: QueueStatus,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class QueueStatus {
    Pending,
    Sending,
    Sent,
    FailedRetryable,
    FailedPermanent,
}

/** Aggregated queue counters used by the status screen and the service notification. */
data class QueueCounts(
    val pending: Int = 0,
    val retryable: Int = 0,
    val sent: Int = 0,
) {
    val due: Int get() = pending + retryable
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val details: String?,
)

enum class LogLevel {
    Debug,
    Info,
    Warning,
    Error,
}

enum class LogCategory {
    App,
    Onboarding,
    Sms,
    Telegram,
    Queue,
    Service,
    Boot,
    Watchdog,
    Permissions,
    Diagnostics,
}

data class TelegramBotInfo(
    val id: Long,
    val isBot: Boolean,
    val firstName: String,
    val username: String?,
)

/** A chat discovered via `getUpdates` that the user can turn into a target. */
data class TelegramChatCandidate(
    val chatId: String,
    val displayName: String,
    val type: String?,
)

/** Outcome of sending a test message to a single target. */
data class TestResult(
    val targetId: Long,
    val chatId: String,
    val displayName: String,
    val success: Boolean,
    val httpCode: Int?,
    val errorMessage: String?,
)
