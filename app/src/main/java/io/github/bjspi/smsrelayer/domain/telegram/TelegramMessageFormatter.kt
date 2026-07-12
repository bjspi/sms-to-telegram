package io.github.bjspi.smsrelayer.domain.telegram

import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Localizable text fragments for relayed messages. Defaults are English; the
 * app container overrides them from Android string resources, which keeps the
 * formatter itself free of any Android dependency (and JVM-testable).
 */
data class TelegramMessageTexts(
    val smsHeader: String = "New SMS",
    val deviceLabel: String = "Device",
    val timeLabel: String = "Time",
    val fromLabel: String = "From",
    val toSimLabel: String = "To/SIM",
    val messageLabel: String = "Message",
    val testHeader: String = "SMS Relayer Test",
    val targetLabel: String = "Target",
    val testBody: String = "This is a test message from SMS Relayer.",
    val unavailable: String = "unavailable",
)

/**
 * Renders relay and test messages as Telegram HTML. All dynamic values are
 * escaped; the SMS body additionally goes into a `<pre>` block so Telegram
 * preserves whitespace and never interprets its content.
 *
 * Texts come from a provider (not a cached snapshot) so an in-app language
 * change is reflected in the next relayed message without a process restart.
 */
class TelegramMessageFormatter(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val textsProvider: () -> TelegramMessageTexts = { DEFAULT_TEXTS },
) {

    private val texts: TelegramMessageTexts get() = textsProvider()

    fun formatSmsMessage(event: SmsEvent): String = buildString {
        appendHeader("📩", texts.smsHeader)
        appendField("📱", texts.deviceLabel, escapeTelegramHtml(event.deviceName))
        appendField("🕒", texts.timeLabel, formatTimestamp(event.receivedAt))
        appendField("👤", texts.fromLabel, escapeTelegramHtml(event.sender.ifBlank { texts.unavailable }))
        appendField("📥", texts.toSimLabel, formatRecipientAndSim(event.recipientNumber, event.simSlot))
        append('\n')
        append("<b>💬 ")
        append(escapeTelegramHtml(texts.messageLabel))
        append(":</b>\n")
        append("<pre>")
        append(escapeTelegramHtml(event.body))
        append("</pre>")
    }

    fun formatTestMessage(
        deviceName: String,
        targetDisplayName: String,
        timestamp: Long,
    ): String = buildString {
        appendHeader("✅", texts.testHeader)
        appendField("📱", texts.deviceLabel, escapeTelegramHtml(deviceName))
        appendField("🕒", texts.timeLabel, formatTimestamp(timestamp))
        appendField("🎯", texts.targetLabel, escapeTelegramHtml(targetDisplayName))
        append('\n')
        append("<pre>")
        append(escapeTelegramHtml(texts.testBody))
        append("</pre>")
    }

    private fun StringBuilder.appendHeader(emoji: String, title: String) {
        append("<b>")
        append(emoji)
        append(' ')
        append(escapeTelegramHtml(title))
        append("</b>\n\n")
    }

    private fun StringBuilder.appendField(emoji: String, label: String, value: String) {
        append("<b>")
        append(emoji)
        append(' ')
        append(escapeTelegramHtml(label))
        append(":</b> ")
        append(value)
        append('\n')
    }

    private fun formatTimestamp(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).format(TIMESTAMP_FORMAT)

    private fun formatRecipientAndSim(recipientNumber: String?, simSlot: Int?): String {
        val recipient = recipientNumber?.takeIf { it.isNotBlank() }?.let(::escapeTelegramHtml)
        val sim = simSlot?.let { "SIM ${it + 1}" }
        return when {
            sim != null && recipient != null -> "$sim / $recipient"
            recipient != null -> recipient
            sim != null -> sim
            else -> escapeTelegramHtml(texts.unavailable)
        }
    }

    private companion object {
        val DEFAULT_TEXTS = TelegramMessageTexts()
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
