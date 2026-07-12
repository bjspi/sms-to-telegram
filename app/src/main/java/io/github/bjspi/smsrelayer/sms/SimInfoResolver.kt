package io.github.bjspi.smsrelayer.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

data class SimInfo(
    val simSlot: Int?,
    val subscriptionId: Int?,
    val recipientNumber: String?
)

class SimInfoResolver(private val context: Context) {

    fun resolveFromIntent(intent: Intent): SimInfo {
        val subscriptionId = firstIntExtra(
            intent,
            "subscription",
            "sub_id",
            "subscription_id"
        )
        val simSlot = firstIntExtra(
            intent,
            "slot",
            "simSlot",
            "phone",
            "slot_id",
            "sim_slot"
        )

        return SimInfo(
            simSlot = simSlot,
            subscriptionId = subscriptionId,
            recipientNumber = resolveRecipientNumber(subscriptionId)
        )
    }

    private fun resolveRecipientNumber(subscriptionId: Int?): String? {
        if (subscriptionId == null) {
            return null
        }
        if (!hasReadPhoneStatePermission()) {
            return null
        }

        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return null

        return try {
            val info = subscriptionManager.activeSubscriptionInfoList
                ?.firstOrNull { it.subscriptionId == subscriptionId }
                ?: return null

            info.number?.takeIf { it.isNotBlank() }
                ?: info.displayName?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun hasReadPhoneStatePermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun firstIntExtra(intent: Intent, vararg keys: String): Int? {
        val extras = intent.extras ?: return null

        for (key in keys) {
            val value = extras.get(key) ?: continue
            when (value) {
                is Int -> return value
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }

        return null
    }
}
