package io.github.bjspi.smsrelayer.domain.telegram

import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramMessageFormatterTest {

    private val formatter = TelegramMessageFormatter(zoneId = ZoneOffset.UTC)

    private fun sms(
        sender: String = "+491701234567",
        body: String = "Hello",
        recipient: String? = null,
        simSlot: Int? = null,
    ) = SmsEvent(
        id = 1L,
        receivedAt = 1_700_000_000_000L,
        sender = sender,
        recipientNumber = recipient,
        simSlot = simSlot,
        subscriptionId = null,
        deviceName = "Relay Phone",
        body = body,
        rawDebugInfo = null,
    )

    @Test
    fun `escapes html in every dynamic field`() {
        val message = formatter.formatSmsMessage(
            sms(sender = "<b>evil</b>", body = "<script>alert(1)</script> & more"),
        )

        assertFalse("<script>" in message)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more" in message)
        assertTrue("&lt;b&gt;evil&lt;/b&gt;" in message)
    }

    @Test
    fun `wraps body in pre block`() {
        val message = formatter.formatSmsMessage(sms(body = "line1\nline2"))
        assertTrue("<pre>line1\nline2</pre>" in message)
    }

    @Test
    fun `formats utc timestamp deterministically`() {
        val message = formatter.formatSmsMessage(sms())
        assertTrue("2023-11-14 22:13:20" in message)
    }

    @Test
    fun `renders sim and recipient combinations`() {
        assertTrue("SIM 2 / +4917699" in formatter.formatSmsMessage(sms(recipient = "+4917699", simSlot = 1)))
        assertTrue("SIM 1" in formatter.formatSmsMessage(sms(simSlot = 0)))
        assertTrue("unavailable" in formatter.formatSmsMessage(sms()))
    }

    @Test
    fun `test message contains device and target`() {
        val message = formatter.formatTestMessage(
            deviceName = "Relay & Co",
            targetDisplayName = "<Team>",
            timestamp = 1_700_000_000_000L,
        )
        assertTrue("Relay &amp; Co" in message)
        assertTrue("&lt;Team&gt;" in message)
    }

    @Test
    fun `escape replaces ampersand first`() {
        assertEquals("&amp;lt;", escapeTelegramHtml("&lt;"))
    }
}
