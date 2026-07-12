package io.github.bjspi.smsrelayer.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Central WorkManager scheduling policy. WorkManager is the *guaranteed*
 * delivery path on a non-rooted device: it survives process death, reboots
 * (via re-scheduling in the boot receiver) and Doze, and it only runs the
 * drain when the network constraint is satisfied.
 */
class RelayWorkScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    /**
     * Requests a queue drain as expedited work with a network constraint.
     *
     * `KEEP` is deliberate: expedited work must not be part of a chain, which
     * rules out the APPEND policies, and `REPLACE` would cancel a drain that is
     * mid-delivery. The worker itself closes the resulting race by re-draining
     * until no pending items remain (see [RelayQueueWorker]).
     */
    fun scheduleQueueDrain() {
        val request = OneTimeWorkRequestBuilder<RelayQueueWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(QUEUE_DRAIN_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Periodic health check. 15 minutes is the platform minimum for periodic
     * work; sub-15-minute liveness comes from the foreground service's internal
     * heartbeat loop instead.
     */
    fun schedulePeriodicWatchdog() {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WATCHDOG_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** One-shot watchdog, used to resurrect the service after it was killed. */
    fun scheduleImmediateWatchdog() {
        val request = OneTimeWorkRequestBuilder<WatchdogWorker>().build()
        workManager.enqueueUniqueWork(WATCHDOG_ONCE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun networkConstraint(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private companion object {
        const val QUEUE_DRAIN_WORK = "relay_queue_drain"
        const val WATCHDOG_PERIODIC_WORK = "relay_watchdog_periodic"
        const val WATCHDOG_ONCE_WORK = "relay_watchdog_once"
        const val WATCHDOG_INTERVAL_MINUTES = 15L
    }
}
