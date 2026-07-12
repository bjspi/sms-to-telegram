package io.github.bjspi.smsrelayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.domain.model.TelegramBotInfo
import io.github.bjspi.smsrelayer.domain.model.TelegramChatCandidate
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TargetDraft(
    val displayName: String,
    val chatId: String,
    val enabled: Boolean,
) {
    companion object {
        fun of(target: TelegramChatTarget) =
            TargetDraft(target.displayName, target.chatId, target.enabled)
    }
}

data class SettingsUiState(
    val loaded: Boolean = false,
    val deviceNameDraft: String = "",
    val tokenDraft: String = "",
    val tokenVisible: Boolean = false,
    val tokenVerifying: Boolean = false,
    val botInfo: TelegramBotInfo? = null,
    val targets: List<TelegramChatTarget> = emptyList(),
    val targetDrafts: Map<Long, TargetDraft> = emptyMap(),
    val newTargetName: String = "",
    val newTargetChatId: String = "",
    val discovering: Boolean = false,
    val discoveredChats: List<TelegramChatCandidate> = emptyList(),
    val pendingDelete: TelegramChatTarget? = null,
    /** Rendered message previews (Telegram HTML) shown in the preview section. */
    val smsPreviewHtml: String = "",
    val testPreviewHtml: String = "",
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val messageFlow: Flow<UiMessage> = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            state.update {
                it.copy(
                    loaded = true,
                    deviceNameDraft = settings.deviceName,
                    tokenDraft = settings.telegramBotToken.orEmpty(),
                )
            }
        }
        viewModelScope.launch {
            container.chatTargetRepository.observeAll().collect { targets ->
                state.update { current ->
                    val drafts = targets.associate { target ->
                        // Keep in-progress edits; refresh drafts for untouched rows.
                        target.id to (current.targetDrafts[target.id] ?: TargetDraft.of(target))
                    }
                    current.copy(targets = targets, targetDrafts = drafts)
                }
            }
        }
        viewModelScope.launch {
            combine(
                container.settingsRepository.settings,
                container.chatTargetRepository.observeAll(),
            ) { settings, targets -> settings.deviceName to targets.firstOrNull()?.displayName }
                .collect { (deviceName, firstTarget) ->
                    val device = deviceName.ifBlank { "—" }
                    val now = container.clock.now()
                    state.update {
                        it.copy(
                            testPreviewHtml = container.messageFormatter.formatTestMessage(
                                deviceName = device,
                                targetDisplayName = firstTarget ?: device,
                                timestamp = now,
                            ),
                            smsPreviewHtml = container.messageFormatter.formatSmsMessage(
                                sampleSms(device, now),
                            ),
                        )
                    }
                }
        }
    }

    // --- Device -----------------------------------------------------------

    fun onDeviceNameChange(value: String) {
        state.update { it.copy(deviceNameDraft = value.take(MAX_NAME_LENGTH)) }
    }

    fun saveDeviceName() {
        val name = state.value.deviceNameDraft.trim()
        if (name.length < MIN_NAME_LENGTH) {
            send(UiMessage(R.string.device_error_length))
            return
        }
        viewModelScope.launch {
            container.settingsRepository.updateDeviceName(name)
            send(UiMessage(R.string.settings_device_saved))
        }
    }

    // --- Bot token ---------------------------------------------------------

    fun onTokenChange(value: String) {
        state.update { it.copy(tokenDraft = value.trim(), botInfo = null) }
    }

    fun toggleTokenVisibility() {
        state.update { it.copy(tokenVisible = !it.tokenVisible) }
    }

    fun verifyToken() {
        val token = state.value.tokenDraft
        if (!BotTokenValidator.isPlausible(token)) {
            send(UiMessage(R.string.bot_invalid_format))
            return
        }
        state.update { it.copy(tokenVerifying = true) }
        viewModelScope.launch {
            container.telegramGateway.getMe(token).fold(
                onSuccess = { info ->
                    state.update { it.copy(botInfo = info, tokenVerifying = false) }
                    send(UiMessage(R.string.bot_connected, listOf(info.username ?: info.firstName)))
                },
                onFailure = {
                    state.update { it.copy(botInfo = null, tokenVerifying = false) }
                    send(UiMessage(R.string.bot_check_failed))
                },
            )
        }
    }

    fun saveToken() {
        val token = state.value.tokenDraft
        if (!BotTokenValidator.isPlausible(token)) {
            send(UiMessage(R.string.bot_invalid_format))
            return
        }
        viewModelScope.launch {
            container.settingsRepository.updateTelegramBotToken(token)
            send(UiMessage(R.string.settings_bot_saved))
        }
    }

    // --- Chat targets ------------------------------------------------------

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

    fun useDiscovered(candidate: TelegramChatCandidate) {
        state.update {
            it.copy(newTargetName = candidate.displayName, newTargetChatId = candidate.chatId)
        }
    }

    fun discoverChats() {
        if (state.value.discovering) return
        viewModelScope.launch {
            val token = state.value.tokenDraft.ifBlank {
                container.settingsRepository.settings.first().telegramBotToken.orEmpty()
            }
            if (token.isBlank()) {
                send(UiMessage(R.string.bot_check_failed))
                return@launch
            }
            state.update { it.copy(discovering = true) }
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
                    state.update { it.copy(discovering = false, discoveredChats = emptyList()) }
                    send(UiMessage(R.string.targets_discover_failed))
                },
            )
        }
    }

    fun onDraftChange(targetId: Long, draft: TargetDraft) {
        state.update { it.copy(targetDrafts = it.targetDrafts + (targetId to draft)) }
    }

    fun saveTarget(target: TelegramChatTarget) {
        val draft = state.value.targetDrafts[target.id] ?: return
        val name = draft.displayName.trim()
        val chatId = draft.chatId.trim()
        viewModelScope.launch {
            when {
                name.isBlank() || chatId.isBlank() -> send(UiMessage(R.string.target_fields_required))
                isDuplicate(chatId, exceptId = target.id) -> send(UiMessage(R.string.target_exists))
                else -> {
                    container.chatTargetRepository.update(
                        target.copy(displayName = name, chatId = chatId, enabled = draft.enabled),
                    )
                    send(UiMessage(R.string.settings_target_saved))
                }
            }
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

    fun requestDelete(target: TelegramChatTarget) {
        state.update { it.copy(pendingDelete = target) }
    }

    fun cancelDelete() {
        state.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val target = state.value.pendingDelete ?: return
        viewModelScope.launch {
            container.chatTargetRepository.deleteById(target.id)
            state.update { it.copy(pendingDelete = null) }
            send(UiMessage(R.string.target_removed))
        }
    }

    // --- Service -----------------------------------------------------------

    fun restartService() {
        container.serviceController.restart(reason = "settings")
        send(UiMessage(R.string.status_msg_service_restarted))
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

    private suspend fun isDuplicate(chatId: String, exceptId: Long?): Boolean {
        val existing = container.chatTargetRepository.getByChatId(chatId)
        return existing != null && existing.id != exceptId
    }

    private fun sampleSms(deviceName: String, now: Long) = SmsEvent(
        id = 0L,
        receivedAt = now,
        sender = "+49 170 1234567",
        recipientNumber = "+49 170 7654321",
        simSlot = 0,
        subscriptionId = null,
        deviceName = deviceName,
        body = SAMPLE_SMS_BODY,
        rawDebugInfo = null,
    )

    private fun send(message: UiMessage) {
        viewModelScope.launch {
            try {
                messages.send(message)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    companion object {
        private const val MIN_NAME_LENGTH = 2
        private const val MAX_NAME_LENGTH = 40
        private const val MAX_CHAT_ID_LENGTH = 80
        private const val SAMPLE_SMS_BODY = "Hello! This is a preview of a relayed SMS."

        val Factory: ViewModelProvider.Factory = containerViewModelFactory(::SettingsViewModel)
    }
}
