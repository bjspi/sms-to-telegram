package io.github.bjspi.smsrelayer.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.bjspi.smsrelayer.di.appContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import io.github.bjspi.smsrelayer.service.RelayNotifications
import kotlinx.coroutines.CancellationException

/**
 * Drains the relay queue under WorkManager guarantees. Scheduled as expedited
 * work whenever an SMS arrives; retried with exponential backoff (and only
 * while the network constraint holds) whenever items remain undelivered.
 */
class RelayQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        return try {
            var summary = container.processRelayQueue()
            // The unique-work KEEP policy means an SMS that arrives while this
            // run is finishing gets no worker of its own — re-drain while fresh
            // pending items exist (bounded: each pass either delivers them or
            // pushes them into a retry backoff).
            var extraPasses = MAX_EXTRA_PASSES
            while (summary.fullyDrained &&
                extraPasses-- > 0 &&
                container.relayQueueRepository.getCounts().pending > 0
            ) {
                summary = container.processRelayQueue()
            }
            if (summary.fullyDrained) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            container.eventLog.error(
                LogCategory.Queue,
                "Queue drain worker crashed",
                e.compactMessage(),
            )
            Result.retry()
        }
    }

    /**
     * On API < 31 expedited work runs inside WorkManager's foreground service,
     * which requires this notification.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = RelayNotifications.deliveryNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                RelayNotifications.DELIVERY_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(RelayNotifications.DELIVERY_NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val MAX_EXTRA_PASSES = 3
    }
}
