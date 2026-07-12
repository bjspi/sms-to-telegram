package io.github.bjspi.smsrelayer.ui.diagnostics

import android.Manifest
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.service.RelayNotifications
import io.github.bjspi.smsrelayer.ui.common.UiMessage
import io.github.bjspi.smsrelayer.ui.common.containerViewModelFactory
import io.github.bjspi.smsrelayer.ui.common.formatAbsoluteTime
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CheckState { Ok, Warning, Failed, Unknown }

data class DiagnosticCheck(
    @StringRes val labelRes: Int,
    val state: CheckState,
    val detail: String? = null,
)

data class DiagnosticsUiState(
    val refreshing: Boolean = false,
    val actionInFlight: Boolean = false,
    val checks: List<DiagnosticCheck> = emptyList(),
)

/**
 * Runs the full self-check suite on demand. Every check funnels through the
 * same [SystemStateInspector]/repository dependencies used by the runtime
 * paths, so the screen can never drift from actual behavior.
 */
class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = state.asStateFlow()

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val messageFlow: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.refreshing) return
        state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                state.update { it.copy(checks = runChecks(), refreshing = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(refreshing = false) }
                messages.send(UiMessage(R.string.msg_action_failed))
            }
        }
    }

    fun testBotConnection() = runAction {
        val token = container.settingsRepository.settings.first().telegramBotToken
        if (token.isNullOrBlank()) return@runAction UiMessage(R.string.diag_msg_bot_failed)
        container.telegramGateway.getMe(token).fold(
            onSuccess = { UiMessage(R.string.diag_msg_bot_ok, listOf(it.username ?: it.firstName)) },
            onFailure = { UiMessage(R.string.diag_msg_bot_failed) },
        )
    }

    fun testAllTargets() = runAction {
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
        container.serviceController.restart(reason = "diagnostics")
        UiMessage(R.string.status_msg_service_restarted)
    }

    private fun runAction(block: suspend () -> UiMessage) {
        if (state.value.actionInFlight) return
        state.update { it.copy(actionInFlight = true) }
        viewModelScope.launch {
            try {
                messages.send(block())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                messages.send(UiMessage(R.string.msg_action_failed))
            } finally {
                state.update { it.copy(actionInFlight = false) }
                refresh()
            }
        }
    }

    private suspend fun runChecks(): List<DiagnosticCheck> {
        val inspector = container.systemStateInspector
        val settings = container.settingsRepository.settings.first()
        val now = container.clock.now()

        val enabledTargets = runCatching { container.chatTargetRepository.getEnabled() }
            .getOrDefault(emptyList())
        val queueCounts = runCatching { container.relayQueueRepository.getCounts() }.getOrNull()

        val heartbeatAge = settings.lastServiceHeartbeatAt?.let { now - it }
        val heartbeatState = when {
            heartbeatAge == null -> CheckState.Failed
            heartbeatAge <= HEALTHY_HEARTBEAT.inWholeMilliseconds -> CheckState.Ok
            else -> CheckState.Warning
        }

        return listOf(
            check(
                R.string.diag_check_permission_sms,
                inspector.hasPermission(Manifest.permission.RECEIVE_SMS),
            ),
            check(
                R.string.diag_check_permission_phone,
                inspector.hasPermission(Manifest.permission.READ_PHONE_STATE),
                failedState = CheckState.Warning,
            ),
            check(R.string.diag_check_notifications, inspector.canPostNotifications()),
            check(
                R.string.diag_check_battery,
                inspector.isIgnoringBatteryOptimizations(),
                failedState = CheckState.Warning,
            ),
            DiagnosticCheck(
                labelRes = R.string.diag_check_service,
                state = heartbeatState,
                detail = settings.lastServiceHeartbeatAt?.let(::formatAbsoluteTime),
            ),
            check(
                R.string.diag_check_channel,
                inspector.notificationChannelExists(RelayNotifications.SERVICE_CHANNEL_ID),
            ),
            check(R.string.diag_check_internet, inspector.isInternetAvailable()),
            check(
                R.string.diag_check_bot_token,
                !settings.telegramBotToken.isNullOrBlank(),
            ),
            DiagnosticCheck(
                labelRes = R.string.diag_check_targets,
                state = if (enabledTargets.isNotEmpty()) CheckState.Ok else CheckState.Failed,
                detail = enabledTargets.size.toString(),
            ),
            DiagnosticCheck(
                labelRes = R.string.diag_check_queue,
                state = when {
                    queueCounts == null -> CheckState.Failed
                    queueCounts.retryable > 0 -> CheckState.Warning
                    else -> CheckState.Ok
                },
                detail = queueCounts?.let { "P ${it.pending} · R ${it.retryable} · S ${it.sent}" },
            ),
            DiagnosticCheck(
                labelRes = R.string.diag_check_last_sms,
                state = if (settings.lastSmsReceivedAt != null) CheckState.Ok else CheckState.Unknown,
                detail = settings.lastSmsReceivedAt?.let(::formatAbsoluteTime),
            ),
            DiagnosticCheck(
                labelRes = R.string.diag_check_last_delivery,
                state = if (settings.lastSuccessfulTelegramSendAt != null) CheckState.Ok else CheckState.Unknown,
                detail = settings.lastSuccessfulTelegramSendAt?.let(::formatAbsoluteTime),
            ),
        )
    }

    private fun check(
        @StringRes labelRes: Int,
        ok: Boolean,
        failedState: CheckState = CheckState.Failed,
    ): DiagnosticCheck = DiagnosticCheck(labelRes, if (ok) CheckState.Ok else failedState)

    companion object {
        private val HEALTHY_HEARTBEAT = 6.minutes

        val Factory: ViewModelProvider.Factory = containerViewModelFactory(::DiagnosticsViewModel)
    }
}
