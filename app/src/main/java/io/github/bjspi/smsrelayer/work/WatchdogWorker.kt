package io.github.bjspi.smsrelayer.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.bjspi.smsrelayer.di.appContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Periodic self-healing pass. Recovers queue items stranded in `Sending` by a
 * process death, restarts the foreground service if it stopped, flags stale
 * heartbeats, and drains anything that is due.
 *
 * Always returns success: the next periodic run is the retry mechanism, and a
 * `retry()` here would only add a competing schedule.
 */
class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val log = container.eventLog

        try {
            val settings = container.settingsRepository.settings.first()
            if (!settings.onboardingCompleted) return Result.success()

            val now = container.clock.now()

            val recovered = container.relayQueueRepository.recoverStaleSending(
                cutoff = now - STALE_SENDING_AFTER.inWholeMilliseconds,
                now = now,
            )
            if (recovered > 0) {
                log.warn(
                    LogCategory.Watchdog,
                    "Recovered queue items stuck in Sending",
                    "count=$recovered",
                )
            }

            val heartbeatAge = settings.lastServiceHeartbeatAt?.let { now - it }
            if (heartbeatAge == null || heartbeatAge > STALE_HEARTBEAT_AFTER.inWholeMilliseconds) {
                log.warn(
                    LogCategory.Watchdog,
                    "Service heartbeat stale, requesting restart",
                    "ageMs=${heartbeatAge ?: -1}",
                )
            }
            container.serviceController.ensureRunning(reason = "watchdog")

            val summary = container.processRelayQueue()
            if (!summary.fullyDrained) {
                // Hand the remainder to the network-constrained drain worker so
                // it fires exactly when connectivity returns.
                container.workScheduler.scheduleQueueDrain()
            }

            log.debug(
                LogCategory.Watchdog,
                "Watchdog pass finished",
                "recovered=$recovered, sent=${summary.sent}, remaining=${summary.retryable}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(LogCategory.Watchdog, "Watchdog pass failed", e.compactMessage())
        }
        return Result.success()
    }

    private companion object {
        val STALE_SENDING_AFTER = 10.minutes
        val STALE_HEARTBEAT_AFTER = 20.minutes
    }
}
