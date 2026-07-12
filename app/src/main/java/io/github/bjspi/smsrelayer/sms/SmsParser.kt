package io.github.bjspi.smsrelayer.sms

import android.content.Intent
import android.provider.Telephony
import io.github.bjspi.smsrelayer.domain.model.SmsEvent

class SmsParser(private val simInfoResolver: SimInfoResolver) {

    fun parse(intent: Intent, deviceName: String): SmsEvent? {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return null
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) {
            return null
        }

        val firstMessage = messages.first()
        val receivedAt = firstMessage.timestampMillis.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val sender = firstMessage.originatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { message ->
            message.messageBody.orEmpty()
        }
        val simInfo = simInfoResolver.resolveFromIntent(intent)

        return SmsEvent(
            id = 0L,
            receivedAt = receivedAt,
            sender = sender,
            recipientNumber = simInfo.recipientNumber,
            simSlot = simInfo.simSlot,
            subscriptionId = simInfo.subscriptionId,
            deviceName = deviceName,
            body = body,
            rawDebugInfo = buildRawDebugInfo(intent, simInfo)
        )
    }

    private fun buildRawDebugInfo(intent: Intent, simInfo: SimInfo): String {
        val extrasKeys = intent.extras
            ?.keySet()
            ?.sorted()
            ?.joinToString(prefix = "[", postfix = "]")
            ?: "[]"

        return buildString {
            append("extrasKeys=")
            append(extrasKeys)
            append(", simSlot=")
            append(simInfo.simSlot ?: "null")
            append(", subscriptionId=")
            append(simInfo.subscriptionId ?: "null")
        }
    }
}
