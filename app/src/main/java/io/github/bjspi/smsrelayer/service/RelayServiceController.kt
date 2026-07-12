package io.github.bjspi.smsrelayer.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.repository.EventLog
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import io.github.bjspi.smsrelayer.work.RelayWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The only place that starts or stops [RelayForegroundService].
 *
 * Android 12+ forbids foreground-service starts from most background contexts
 * (a plain SMS broadcast is one of them, unless the user granted the battery
 * exemption). Instead of crashing, a refused start is logged and delivery
 * falls back to WorkManager, which is always allowed to run.
 */
class RelayServiceController(
    private val context: Context,
    private val workScheduler: RelayWorkScheduler,
    private val eventLog: EventLog,
    private val scope: CoroutineScope,
) {

    /** Attempts to start (or poke) the service; safe to call from anywhere. */
    fun ensureRunning(reason: String): Boolean {
        val intent = Intent(context, RelayForegroundService::class.java)
            .setAction(RelayForegroundService.ACTION_ENSURE_RUNNING)
            .putExtra(RelayForegroundService.EXTRA_REASON, reason)

        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // Typically ForegroundServiceStartNotAllowedException on API 31+.
            scope.launch {
                eventLog.warn(
                    LogCategory.Service,
                    "Foreground service start rejected, falling back to WorkManager",
                    "reason=$reason, error=${e.compactMessage()}",
                )
            }
            // Fall back to a queue drain, NOT another watchdog: the watchdog
            // itself calls ensureRunning, so scheduling one here would spin an
            // immediate watchdog → rejected start → watchdog loop on devices
            // that refuse background service starts.
            workScheduler.scheduleQueueDrain()
            false
        }
    }

    fun restart(reason: String) {
        context.stopService(Intent(context, RelayForegroundService::class.java))
        ensureRunning(reason)
    }
}
