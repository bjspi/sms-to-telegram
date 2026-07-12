package io.github.bjspi.smsrelayer.data.telegram

import io.github.bjspi.smsrelayer.domain.telegram.TelegramSendOutcome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

class TelegramHttpGatewayTest {

    private val token = "123456:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    private lateinit var server: MockWebServer
    private lateinit var gateway: TelegramHttpGateway

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        gateway = TelegramHttpGateway(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getMe parses the bot identity`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"Relay","username":"relay_bot"}}""",
            ),
        )

        val info = gateway.getMe(token).getOrThrow()

        assertEquals(42L, info.id)
        assertTrue(info.isBot)
        assertEquals("relay_bot", info.username)
        assertEquals("/bot$token/getMe", server.takeRequest().path)
    }

    @Test
    fun `getMe failure never leaks the token`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ok":false,"error_code":401,"description":"Unauthorized for bot$token"}""",
                code = 401,
            ),
        )

        val result = gateway.getMe(token)

        assertTrue(result.isFailure)
        assertFalse(token in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun `successful send is classified as delivered`() = runTest {
        server.enqueue(jsonResponse("""{"ok":true,"result":{"message_id":7}}"""))

        val outcome = gateway.sendMessage(token, "111", "<b>hi</b>")

        assertIs<TelegramSendOutcome.Delivered>(outcome)
        val request = server.takeRequest()
        assertEquals("/bot$token/sendMessage", request.path)
        val body = request.body.readUtf8()
        assertTrue("\"chat_id\":\"111\"" in body)
        assertTrue("\"parse_mode\":\"HTML\"" in body)
        assertTrue("\"is_disabled\":true" in body)
    }

    @Test
    fun `rate limiting maps to retry with server-provided delay`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":31}}""",
                code = 429,
            ),
        )

        val outcome = gateway.sendMessage(token, "111", "hi")

        val retry = assertIs<TelegramSendOutcome.RetryLater>(outcome)
        assertEquals(31.seconds, retry.retryAfter)
    }

    @Test
    fun `client errors map to permanent rejection`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""",
                code = 400,
            ),
        )

        val outcome = gateway.sendMessage(token, "999", "hi")

        val rejected = assertIs<TelegramSendOutcome.Rejected>(outcome)
        assertEquals(400, rejected.httpCode)
        assertTrue("chat not found" in rejected.reason)
    }

    @Test
    fun `server errors map to retry`() = runTest {
        server.enqueue(jsonResponse("""{"ok":false,"error_code":502,"description":"Bad Gateway"}""", code = 502))

        assertIs<TelegramSendOutcome.RetryLater>(gateway.sendMessage(token, "111", "hi"))
    }

    @Test
    fun `network failures map to retry and never throw`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertIs<TelegramSendOutcome.RetryLater>(gateway.sendMessage(token, "111", "hi"))
    }

    @Test
    fun `getUpdates deduplicates chats across update types`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {"ok":true,"result":[
                  {"update_id":1,"message":{"chat":{"id":100,"type":"private","first_name":"Ada","last_name":"L."}}},
                  {"update_id":2,"message":{"chat":{"id":100,"type":"private","first_name":"Ada"}}},
                  {"update_id":3,"channel_post":{"chat":{"id":-200,"type":"channel","title":"Alerts"}}}
                ]}
                """.trimIndent(),
            ),
        )

        val candidates = gateway.getUpdates(token).getOrThrow()

        assertEquals(2, candidates.size)
        assertEquals("Ada L.", candidates[0].displayName)
        assertEquals("-200", candidates[1].chatId)
        assertEquals("Alerts", candidates[1].displayName)
    }

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
