package io.github.bjspi.smsrelayer.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.bjspi.smsrelayer.di.appContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Entry point for incoming SMS.
 *
 * Deliberately does the minimum inside the broadcast window: parse, persist,
 * fan out queue items — then hand delivery to WorkManager (guaranteed, network
 * aware) and poke the foreground service (fast path when it is allowed to
 * start). Never sends over the network itself.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val container = context.appContainer
        val pendingResult = goAsync()

        container.applicationScope.launch {
            try {
                withTimeout(RECEIVER_BUDGET_MS) {
                    handleIncomingSms(context.applicationContext, intent)
                }
            } catch (e: TimeoutCancellationException) {
                // TimeoutCancellationException IS a CancellationException — it
                // must be caught first, or a storage stall would silently drop
                // the SMS without any trace in the log.
                container.eventLog.error(
                    LogCategory.Sms,
                    "SMS handling exceeded the broadcast budget",
                    "budgetMs=$RECEIVER_BUDGET_MS",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.eventLog.error(
                    LogCategory.Sms,
                    "Unhandled failure in SMS receiver",
                    e.compactMessage(),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleIncomingSms(context: Context, intent: Intent) {
        val container = context.appContainer
        val settings = container.settingsRepository.settings.first()

        if (!settings.onboardingCompleted) {
            container.eventLog.info(
                LogCategory.Sms,
                "Incoming SMS ignored",
                "reason=onboarding_incomplete",
            )
            return
        }

        val deviceName = settings.deviceName.ifBlank { android.os.Build.MODEL }
        val smsEvent = SmsParser(SimInfoResolver(context)).parse(intent, deviceName)
        if (smsEvent == null) {
            container.eventLog.warn(
                LogCategory.Sms,
                "Incoming SMS ignored",
                "reason=unparseable_intent",
            )
            return
        }

        container.enqueueIncomingSms(smsEvent)

        // Guaranteed path: expedited, network-constrained WorkManager drain.
        container.workScheduler.scheduleQueueDrain()
        // Fast path: the long-lived service delivers immediately if it may run.
        container.serviceController.ensureRunning(reason = "sms_received")
    }

    private companion object {
        /** Stay well below the ~10s broadcast ANR budget; delivery happens elsewhere. */
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}
