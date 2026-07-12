package io.github.bjspi.smsrelayer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.bjspi.smsrelayer.di.appContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores the relay after a reboot or app update.
 *
 * `BOOT_COMPLETED` is one of the few broadcast contexts Android still allows
 * to start a foreground service from, so the service comes back directly;
 * the periodic watchdog is re-registered as the safety net either way.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val container = context.appContainer
        val pendingResult = goAsync()

        container.applicationScope.launch {
            try {
                val settings = container.settingsRepository.settings.first()
                if (!settings.onboardingCompleted) {
                    container.eventLog.info(
                        LogCategory.Boot,
                        "Boot signal ignored: onboarding not completed",
                        "action=$action",
                    )
                    return@launch
                }

                container.eventLog.info(LogCategory.Boot, "Boot signal received", "action=$action")
                container.workScheduler.schedulePeriodicWatchdog()
                container.serviceController.ensureRunning(reason = "boot")
                container.workScheduler.scheduleQueueDrain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                container.eventLog.error(
                    LogCategory.Boot,
                    "Failed to restore relay after boot",
                    e.compactMessage(),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Some OEMs (HTC, older Xiaomi) fire this instead of BOOT_COMPLETED.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
