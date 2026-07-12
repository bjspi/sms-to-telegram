package io.github.bjspi.smsrelayer.ui.onboarding

import android.Manifest
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.TelegramBotInfo
import io.github.bjspi.smsrelayer.domain.model.TelegramChatCandidate
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.domain.model.TestResult
import io.github.bjspi.smsrelayer.domain.usecase.AddChatTarget
import io.github.bjspi.smsrelayer.domain.usecase.BotTokenValidator
import io.github.bjspi.smsrelayer.ui.common.UiMessage
import io.github.bjspi.smsrelayer.ui.common.containerViewModelFactory
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

enum class OnboardingStep(@StringRes val labelRes: Int) {
    Welcome(R.string.step_welcome),
    DeviceName(R.string.step_device),
    BotToken(R.string.step_bot),
    ChatTargets(R.string.step_targets),
    Permissions(R.string.step_permissions),
    Test(R.string.step_test),
    Done(R.string.step_done),
}

data class PermissionItem(
    @StringRes val labelRes: Int,
    val granted: Boolean,
    val optional: Boolean = false,
)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val deviceName: String = "",
    val token: String = "",
    val tokenVisible: Boolean = false,
    val tokenVerifying: Boolean = false,
    val tokenVerified: Boolean = false,
    val botInfo: TelegramBotInfo? = null,
    val targets: List<TelegramChatTarget> = emptyList(),
    val newTargetName: String = "",
    val newTargetChatId: String = "",
    val discovering: Boolean = false,
    val discoveredChats: List<TelegramChatCandidate> = emptyList(),
    val permissions: List<PermissionItem> = emptyList(),
    val testResults: List<TestResult> = emptyList(),
    val testing: Boolean = false,
    val finishing: Boolean = false,
)

/**
 * Drives the guided setup. Each step gates progression on a verifiable
 * condition (valid name, verified token, ≥1 target, SMS permission, ≥1
 * successful test), so a completed onboarding is a working relay by
 * construction.
 */
class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val messageFlow: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            state.update {
                it.copy(
                    deviceName = settings.deviceName,
                    token = settings.telegramBotToken.orEmpty(),
                    tokenVerified = !settings.telegramBotToken.isNullOrBlank(),
                )
            }
        }
        viewModelScope.launch {
            container.chatTargetRepository.observeAll().collect { targets ->
                state.update { it.copy(targets = targets) }
            }
        }
        refreshPermissions()
    }

    fun back() {
        val current = state.value.step
        val previous = OnboardingStep.entries.getOrNull(current.ordinal - 1) ?: return
        state.update { it.copy(step = previous) }
    }

    fun startSetup() {
        state.update { it.copy(step = OnboardingStep.DeviceName) }
    }

    // --- Device name --------------------------------------------------------

    fun onDeviceNameChange(value: String) {
        state.update { it.copy(deviceName = value.take(MAX_NAME_LENGTH)) }
    }

    fun submitDeviceName() {
        val name = state.value.deviceName.trim()
        if (name.length !in MIN_NAME_LENGTH..MAX_NAME_LENGTH) {
            send(UiMessage(R.string.device_error_length))
            return
        }
        viewModelScope.launch {
            container.settingsRepository.updateDeviceName(name)
            state.update { it.copy(step = OnboardingStep.BotToken) }
        }
    }

    // --- Bot token -----------------------------------------------------------

    fun onTokenChange(value: String) {
        state.update {
            it.copy(token = value.trim(), tokenVerified = false, botInfo = null)
        }
    }

    fun toggleTokenVisibility() {
        state.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun verifyToken() {
        val token = state.value.token
        if (!BotTokenValidator.isPlausible(token)) {
            send(UiMessage(R.string.bot_invalid_format))
            return
        }
        state.update { it.copy(tokenVerifying = true) }
        viewModelScope.launch {
            container.telegramGateway.getMe(token).fold(
                onSuccess = { info ->
                    container.settingsRepository.updateTelegramBotToken(token)
                    state.update {
                        it.copy(tokenVerifying = false, tokenVerified = true, botInfo = info)
                    }
                    send(UiMessage(R.string.bot_connected, listOf(info.username ?: info.firstName)))
                },
                onFailure = {
                    state.update {
                        it.copy(tokenVerifying = false, tokenVerified = false, botInfo = null)
                    }
                    send(UiMessage(R.string.bot_check_failed))
                },
            )
        }
    }

    fun submitToken() {
        if (!state.value.tokenVerified) {
            send(UiMessage(R.string.bot_check_first))
            return
        }
        state.update { it.copy(step = OnboardingStep.ChatTargets) }
    }

    // --- Chat targets ---------------------------------------------------------

    fun onNewTargetNameChange(value: String) {
        state.update { it.copy(newTargetName = value.take(MAX_NAME_LENGTH)) }
    }

    fun onNewTargetChatIdChange(value: String) {
        state.update { it.copy(newTargetChatId = value.take(MAX_CHAT_ID_LENGTH)) }
    }

    fun addTarget() {
        addTarget(state.value.newTargetName, state.value.newTargetChatId, clearInputs = true)
    }

    fun addDiscovered(candidate: TelegramChatCandidate) {
        addTarget(candidate.displayName, candidate.chatId, clearInputs = false)
    }

    fun removeTarget(target: TelegramChatTarget) {
        viewModelScope.launch {
            container.chatTargetRepository.deleteById(target.id)
            send(UiMessage(R.string.target_removed))
        }
    }

    fun testTarget(target: TelegramChatTarget) {
        viewModelScope.launch {
            val result = container.sendTestMessage.toTarget(target.id)
            send(
                if (result.success) {
                    UiMessage(R.string.settings_test_ok, listOf(target.displayName))
                } else {
                    UiMessage(
                        R.string.settings_test_failed,
                        listOf(target.displayName, result.errorMessage.orEmpty()),
                    )
                },
            )
        }
    }

    fun discoverChats() {
        if (state.value.discovering) return
        val token = state.value.token
        if (token.isBlank()) {
            send(UiMessage(R.string.bot_check_first))
            return
        }
        state.update { it.copy(discovering = true) }
        viewModelScope.launch {
            container.telegramGateway.getUpdates(token).fold(
                onSuccess = { candidates ->
                    state.update { it.copy(discovering = false, discoveredChats = candidates) }
                    send(
                        if (candidates.isEmpty()) {
                            UiMessage(R.string.targets_discovered_none)
                        } else {
                            UiMessage(R.string.targets_discovered_count, listOf(candidates.size))
                        },
                    )
                },
                onFailure = {
                    state.update { it.copy(discovering = false) }
                    send(UiMessage(R.string.targets_discover_failed))
                },
            )
        }
    }

    fun submitTargets() {
        if (state.value.targets.none { it.enabled }) {
            send(UiMessage(R.string.targets_need_one))
            return
        }
        refreshPermissions()
        state.update { it.copy(step = OnboardingStep.Permissions) }
    }

    // --- Permissions -----------------------------------------------------------

    fun refreshPermissions() {
        val inspector = container.systemStateInspector
        state.update {
            it.copy(
                permissions = listOf(
                    PermissionItem(
                        R.string.permission_receive_sms,
                        inspector.hasPermission(Manifest.permission.RECEIVE_SMS),
                    ),
                    PermissionItem(
                        R.string.permission_phone_state,
                        inspector.hasPermission(Manifest.permission.READ_PHONE_STATE),
                        optional = true,
                    ),
                    PermissionItem(
                        R.string.permission_notifications,
                        inspector.canPostNotifications(),
                    ),
                    PermissionItem(
                        R.string.permission_battery,
                        inspector.isIgnoringBatteryOptimizations(),
                        optional = true,
                    ),
                ),
            )
        }
    }

    fun requiredRuntimePermissions(): Array<String> =
        container.systemStateInspector.requiredRuntimePermissions().toTypedArray()

    fun submitPermissions() {
        if (!container.systemStateInspector.hasPermission(Manifest.permission.RECEIVE_SMS)) {
            send(UiMessage(R.string.permission_sms_required))
            return
        }
        state.update { it.copy(step = OnboardingStep.Test) }
    }

    // --- Test & finish ------------------------------------------------------------

    fun sendTestToAll() {
        if (state.value.testing) return
        state.update { it.copy(testing = true) }
        viewModelScope.launch {
            try {
                val results = container.sendTestMessage.toAllEnabled()
                state.update { it.copy(testResults = results, testing = false) }
                send(
                    if (results.any { it.success }) {
                        UiMessage(R.string.test_success)
                    } else {
                        UiMessage(R.string.test_failed)
                    },
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(testing = false) }
                send(UiMessage(R.string.test_failed))
            }
        }
    }

    fun submitTest() {
        if (state.value.testResults.none { it.success }) {
            send(UiMessage(R.string.test_need_success))
            return
        }
        state.update { it.copy(step = OnboardingStep.Done) }
    }

    /** Marks onboarding complete; [io.github.bjspi.smsrelayer.ui.AppRoot] switches to the main UI. */
    fun finish() {
        if (state.value.finishing) return
        state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            container.eventLog.info(LogCategory.Onboarding, "Onboarding completed")
            container.workScheduler.schedulePeriodicWatchdog()
            container.settingsRepository.markOnboardingCompleted()
            container.serviceController.ensureRunning(reason = "onboarding_done")
        }
    }

    private fun addTarget(name: String, chatId: String, clearInputs: Boolean) {
        viewModelScope.launch {
            when (container.addChatTarget(name, chatId)) {
                is AddChatTarget.Outcome.Added -> {
                    if (clearInputs) {
                        state.update { it.copy(newTargetName = "", newTargetChatId = "") }
                    }
                    send(UiMessage(R.string.target_added))
                }

                AddChatTarget.Outcome.MissingFields -> send(UiMessage(R.string.target_fields_required))
                AddChatTarget.Outcome.DuplicateChatId -> send(UiMessage(R.string.target_exists))
            }
        }
    }

    private fun send(message: UiMessage) {
        viewModelScope.launch { messages.send(message) }
    }

    companion object {
        private const val MIN_NAME_LENGTH = 2
        private const val MAX_NAME_LENGTH = 40
        private const val MAX_CHAT_ID_LENGTH = 80

        val Factory: ViewModelProvider.Factory = containerViewModelFactory(::OnboardingViewModel)
    }
}
