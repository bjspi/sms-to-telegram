package io.github.bjspi.smsrelayer.data.telegram

import io.github.bjspi.smsrelayer.data.telegram.dto.SendMessageRequest
import io.github.bjspi.smsrelayer.data.telegram.dto.TelegramResponse
import io.github.bjspi.smsrelayer.data.telegram.dto.UpdateDto
import io.github.bjspi.smsrelayer.data.telegram.dto.UserDto
import io.github.bjspi.smsrelayer.domain.model.TelegramBotInfo
import io.github.bjspi.smsrelayer.domain.model.TelegramChatCandidate
import io.github.bjspi.smsrelayer.domain.telegram.TelegramGateway
import io.github.bjspi.smsrelayer.domain.telegram.TelegramSendOutcome
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp implementation of [TelegramGateway].
 *
 * The base URL is injectable so the full request/response/classification path
 * is exercised against MockWebServer in JVM unit tests. The bot token appears
 * in request URLs (Bot API design), so every error message that could contain
 * a URL is redacted before it leaves this class.
 */
class TelegramHttpGateway(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://api.telegram.org",
) : TelegramGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    override suspend fun getMe(token: String): Result<TelegramBotInfo> = withContext(Dispatchers.IO) {
        runTelegramCatching(token) {
            val envelope = execute<UserDto>(Request.Builder().url(url(token, "getMe")).get().build())
            val user = envelope.requireResult("getMe")
            TelegramBotInfo(
                id = user.id,
                isBot = user.isBot,
                firstName = user.firstName,
                username = user.username,
            )
        }
    }

    override suspend fun sendMessage(
        token: String,
        chatId: String,
        htmlText: String,
    ): TelegramSendOutcome = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(chatId = chatId, text = htmlText),
        )
        val request = Request.Builder()
            .url(url(token, "sendMessage"))
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val envelope = decodeOrNull<TelegramResponse<MessageAck>>(body)
                classifySendResponse(
                    httpCode = response.code,
                    apiErrorCode = envelope?.errorCode,
                    ok = envelope?.ok == true,
                    description = envelope?.description?.let { redact(it, token) },
                    retryAfterSeconds = envelope?.parameters?.retryAfter,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TelegramSendOutcome.RetryLater(httpCode = null, reason = redact(e.compactMessage(), token))
        }
    }

    override suspend fun getUpdates(token: String): Result<List<TelegramChatCandidate>> =
        withContext(Dispatchers.IO) {
            runTelegramCatching(token) {
                val envelope = execute<List<UpdateDto>>(
                    Request.Builder().url(url(token, "getUpdates")).get().build(),
                )
                val updates = envelope.requireResult("getUpdates")
                updates
                    .flatMap(UpdateDto::chats)
                    .distinctBy { it.id }
                    .map { chat ->
                        TelegramChatCandidate(
                            chatId = chat.id.toString(),
                            displayName = chat.displayName(),
                            type = chat.type,
                        )
                    }
            }
        }

    /**
     * Maps a `sendMessage` response onto the delivery outcome the queue acts on.
     * Kept `internal` (rather than private) so the classification table is unit
     * testable in isolation.
     */
    internal fun classifySendResponse(
        httpCode: Int,
        apiErrorCode: Int?,
        ok: Boolean,
        description: String?,
        retryAfterSeconds: Int?,
    ): TelegramSendOutcome {
        if (ok) return TelegramSendOutcome.Delivered(httpCode)

        val effectiveCode = apiErrorCode ?: httpCode
        val reason = buildString {
            append("Telegram sendMessage failed (HTTP ")
            append(effectiveCode)
            append(')')
            if (!description.isNullOrBlank()) {
                append(": ")
                append(description)
            }
        }

        return when {
            effectiveCode == 429 -> TelegramSendOutcome.RetryLater(
                httpCode = effectiveCode,
                reason = reason,
                retryAfter = retryAfterSeconds?.seconds,
            )

            effectiveCode in 500..599 -> TelegramSendOutcome.RetryLater(effectiveCode, reason)

            // 400 bad chat ID, 401/403 bad or revoked token, 404 malformed token:
            // retrying with the same configuration can never succeed.
            effectiveCode in 400..499 -> TelegramSendOutcome.Rejected(effectiveCode, reason)

            else -> TelegramSendOutcome.RetryLater(effectiveCode, reason)
        }
    }

    private inline fun <T> runTelegramCatching(token: String, block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(TelegramApiException(redact(e.compactMessage(), token)))
        }

    private inline fun <reified T> execute(request: Request): TelegramResponse<T> =
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            decodeOrNull<TelegramResponse<T>>(body)
                ?: throw TelegramApiException("Unexpected Telegram response (HTTP ${response.code})")
        }

    private fun <T> TelegramResponse<T>.requireResult(endpoint: String): T {
        if (!ok || result == null) {
            throw TelegramApiException(
                buildString {
                    append("Telegram ")
                    append(endpoint)
                    append(" failed")
                    errorCode?.let { append(" (HTTP ").append(it).append(')') }
                    description?.let { append(": ").append(it) }
                },
            )
        }
        return result
    }

    private inline fun <reified T> decodeOrNull(body: String): T? =
        try {
            if (body.isBlank()) null else json.decodeFromString<T>(body)
        } catch (_: Exception) {
            null
        }

    private fun url(token: String, method: String): String = "$baseUrl/bot$token/$method"

    private fun redact(text: String, token: String): String =
        if (token.isBlank()) text else text.replace(token, "<token>")

    private fun io.github.bjspi.smsrelayer.data.telegram.dto.ChatDto.displayName(): String = when {
        !title.isNullOrBlank() -> title
        !firstName.isNullOrBlank() || !lastName.isNullOrBlank() ->
            listOfNotNull(firstName, lastName).joinToString(" ")
        !username.isNullOrBlank() -> "@$username"
        else -> "Chat $id"
    }

    /** `sendMessage` result payload — content is irrelevant, only `ok` matters. */
    @kotlinx.serialization.Serializable
    private class MessageAck

    class TelegramApiException(message: String) : IOException(message)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
