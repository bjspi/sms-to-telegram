package io.github.bjspi.smsrelayer.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.bjspi.smsrelayer.di.appContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.util.compactMessage
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the relay alive and visible.
 *
 * The service holds no business logic: it pins the process with a persistent
 * notification, ticks a heartbeat, and delegates all delivery to the shared
 * [io.github.bjspi.smsrelayer.domain.usecase.ProcessRelayQueue] use case.
 *
 * `startForeground` is called synchronously in [onCreate] with a placeholder —
 * no I/O ever happens on the main thread. The notification content is then
 * driven reactively from Room/DataStore flows, so it stays correct without
 * polling.
 *
 * Declared with the `specialUse` foreground service type on API 34+: unlike
 * `dataSync`, it has no 6-hours-per-day runtime cap, which is exactly what a
 * dedicated, always-plugged-in relay device needs.
 */
class RelayForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        RelayNotifications.ensureChannels(this)
        startInForeground()
        observeStateForNotification()
        runHeartbeatLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = appContainer
        val action = intent?.action
        val reason = intent?.getStringExtra(EXTRA_REASON)

        serviceScope.launch {
            if (action == ACTION_DRAIN_QUEUE) {
                container.eventLog.debug(LogCategory.Service, "Manual queue drain requested")
            } else if (reason != null) {
                container.eventLog.debug(LogCategory.Service, "Service poked", "reason=$reason")
            }
            drainQueueSafely()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        val container = appContainer
        // Log + resurrection are fire-and-forget on the application scope; the
        // service's own scope is already dead here.
        container.applicationScope.launch {
            container.eventLog.warn(LogCategory.Service, "Relay service destroyed, scheduling watchdog")
        }
        container.workScheduler.scheduleImmediateWatchdog()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = RelayNotifications.serviceNotification(
            context = this,
            deviceName = getString(io.github.bjspi.smsrelayer.R.string.notification_device_placeholder),
            enabledTargets = 0,
            queueDue = 0,
        )
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                RelayNotifications.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                RelayNotifications.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

            else -> startForeground(RelayNotifications.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun observeStateForNotification() {
        val container = appContainer
        serviceScope.launch {
            combine(
                container.settingsRepository.settings,
                container.chatTargetRepository.observeEnabled(),
                container.relayQueueRepository.observeCounts(),
            ) { settings, targets, counts ->
                NotificationState(
                    deviceName = settings.deviceName.ifBlank {
                        getString(io.github.bjspi.smsrelayer.R.string.notification_device_placeholder)
                    },
                    enabledTargets = targets.size,
                    queueDue = counts.due,
                )
            }
                .distinctUntilChanged()
                .conflate()
                .collect { state ->
                    try {
                        val manager = getSystemService(android.app.NotificationManager::class.java)
                        manager?.notify(
                            RelayNotifications.SERVICE_NOTIFICATION_ID,
                            RelayNotifications.serviceNotification(
                                context = this@RelayForegroundService,
                                deviceName = state.deviceName,
                                enabledTargets = state.enabledTargets,
                                queueDue = state.queueDue,
                            ),
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        container.eventLog.error(
                            LogCategory.Service,
                            "Failed to update service notification",
                            e.compactMessage(),
                        )
                    }
                }
        }
    }

    /**
     * 5-minute liveness tick: refreshes the heartbeat (watched by the watchdog
     * and the status screen) and opportunistically drains anything due —
     * covering retry backoffs that expire while the device is idle.
     */
    private fun runHeartbeatLoop() {
        val container = appContainer
        serviceScope.launch {
            container.eventLog.info(LogCategory.Service, "Relay service started")
            while (isActive) {
                try {
                    container.settingsRepository.updateLastServiceHeartbeatAt(container.clock.now())
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    container.eventLog.error(
                        LogCategory.Service,
                        "Failed to update heartbeat",
                        e.compactMessage(),
                    )
                }
                drainQueueSafely()
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    private suspend fun drainQueueSafely() {
        val container = appContainer
        try {
            container.processRelayQueue()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            container.eventLog.error(LogCategory.Service, "Queue drain failed", e.compactMessage())
        }
    }

    private data class NotificationState(
        val deviceName: String,
        val enabledTargets: Int,
        val queueDue: Int,
    )

    companion object {
        const val ACTION_ENSURE_RUNNING = "io.github.bjspi.smsrelayer.action.ENSURE_RUNNING"
        const val ACTION_DRAIN_QUEUE = "io.github.bjspi.smsrelayer.action.DRAIN_QUEUE"
        const val EXTRA_REASON = "reason"

        private val HEARTBEAT_INTERVAL = 5.minutes
    }
}
