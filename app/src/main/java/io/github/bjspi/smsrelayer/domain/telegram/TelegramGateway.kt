package io.github.bjspi.smsrelayer.domain.telegram

import io.github.bjspi.smsrelayer.domain.model.TelegramBotInfo
import io.github.bjspi.smsrelayer.domain.model.TelegramChatCandidate
import kotlin.time.Duration

/**
 * Boundary to the Telegram Bot API. The domain layer only sees this interface;
 * the HTTP implementation lives in the data layer.
 */
interface TelegramGateway {

    /** Validates a bot token by resolving the bot identity behind it. */
    suspend fun getMe(token: String): Result<TelegramBotInfo>

    /** Sends an HTML-formatted message to a single chat. Never throws. */
    suspend fun sendMessage(token: String, chatId: String, htmlText: String): TelegramSendOutcome

    /** Lists chats that recently interacted with the bot (chat-ID discovery). */
    suspend fun getUpdates(token: String): Result<List<TelegramChatCandidate>>
}

/**
 * Delivery outcome of a single `sendMessage` call, already classified into the
 * three cases the relay queue cares about. Keeping the classification here means
 * the queue never needs to understand HTTP.
 */
sealed interface TelegramSendOutcome {

    /** Message accepted by Telegram. */
    data class Delivered(val httpCode: Int) : TelegramSendOutcome

    /** Transient failure (network, 429, 5xx) — retry with backoff. */
    data class RetryLater(
        val httpCode: Int?,
        val reason: String,
        /** Server-requested pause from a 429 response, if any. */
        val retryAfter: Duration? = null,
    ) : TelegramSendOutcome

    /** Permanent failure (bad chat ID, revoked token) — retrying cannot help. */
    data class Rejected(
        val httpCode: Int?,
        val reason: String,
    ) : TelegramSendOutcome
}
