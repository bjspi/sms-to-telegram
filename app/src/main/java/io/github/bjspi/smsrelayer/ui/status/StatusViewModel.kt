package io.github.bjspi.smsrelayer.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.domain.model.QueueCounts
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.ui.common.UiMessage
import io.github.bjspi.smsrelayer.ui.common.containerViewModelFactory
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ServiceHealth { Running, Stale, Unknown }

data class StatusUiState(
    val loaded: Boolean = false,
    val deviceName: String = "",
    val botTokenConfigured: Boolean = false,
    val serviceHealth: ServiceHealth = ServiceHealth.Unknown,
    val lastHeartbeatAt: Long? = null,
    val lastSmsAt: Long? = null,
    val lastDeliveryAt: Long? = null,
    val latestSms: SmsEvent? = null,
    val enabledTargets: Int = 0,
    val totalTargets: Int = 0,
    val queue: QueueCounts = QueueCounts(),
    val batteryExempt: Boolean = false,
    val actionInFlight: Boolean = false,
)

class StatusViewModel(private val container: AppContainer) : ViewModel() {

    private val actionInFlight = MutableStateFlow(false)

    /**
     * Battery exemption is a binder IPC — snapshot it instead of querying
     * inside the combine below, which re-runs on every queue/settings change.
     * Refreshed after each user action (the only in-app way it can change).
     */
    private val batteryExempt =
        MutableStateFlow(container.systemStateInspector.isIgnoringBatteryOptimizations())

    private val localState = combine(actionInFlight, batteryExempt) { inFlight, battery ->
        inFlight to battery
    }

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val messageFlow: Flow<UiMessage> = messages.receiveAsFlow()

    val uiState: StateFlow<StatusUiState> = combine(
        container.settingsRepository.settings,
        container.chatTargetRepository.observeAll(),
        container.smsEventRepository.observeLatest(1),
        container.relayQueueRepository.observeCounts(),
        localState,
    ) { settings, targets, latestSms, queue, (inFlight, battery) ->
        StatusUiState(
            loaded = true,
            deviceName = settings.deviceName,
            botTokenConfigured = !settings.telegramBotToken.isNullOrBlank(),
            serviceHealth = serviceHealth(settings.lastServiceHeartbeatAt),
            lastHeartbeatAt = settings.lastServiceHeartbeatAt,
            lastSmsAt = settings.lastSmsReceivedAt,
            lastDeliveryAt = settings.lastSuccessfulTelegramSendAt,
            latestSms = latestSms.firstOrNull(),
            enabledTargets = targets.count { it.enabled },
            totalTargets = targets.size,
            queue = queue,
            batteryExempt = battery,
            actionInFlight = inFlight,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState())

    fun sendTestToAll() = runAction {
        val results = container.sendTestMessage.toAllEnabled()
        when {
            results.isEmpty() -> UiMessage(R.string.status_msg_no_targets)
            results.all { it.success } -> UiMessage(R.string.status_msg_tests_sent, listOf(results.size))
            else -> UiMessage(R.string.test_failed)
        }
    }

    fun drainQueue() = runAction {
        val summary = container.processRelayQueue()
        UiMessage(R.string.status_msg_drained, listOf(summary.sent, summary.retryable))
    }

    fun restartService() = runAction {
        container.serviceController.restart(reason = "status_screen")
        UiMessage(R.string.status_msg_service_restarted)
    }

    private fun runAction(block: suspend () -> UiMessage) {
        if (!actionInFlight.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                messages.send(block())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.send(UiMessage(R.string.msg_action_failed))
            } finally {
                actionInFlight.value = false
                batteryExempt.value = container.systemStateInspector.isIgnoringBatteryOptimizations()
            }
        }
    }

    private fun serviceHealth(lastHeartbeatAt: Long?): ServiceHealth = when {
        lastHeartbeatAt == null -> ServiceHealth.Unknown
        container.clock.now() - lastHeartbeatAt <= STALE_HEARTBEAT.inWholeMilliseconds -> ServiceHealth.Running
        else -> ServiceHealth.Stale
    }

    companion object {
        private val STALE_HEARTBEAT = 6.minutes

        val Factory: ViewModelProvider.Factory = containerViewModelFactory(::StatusViewModel)
    }
}
