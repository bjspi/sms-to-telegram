package io.github.bjspi.smsrelayer.ui.settings

import android.content.Context
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bjspi.smsrelayer.BuildConfig
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.ui.common.BadgeTone
import io.github.bjspi.smsrelayer.ui.common.DiscoveredChatCard
import io.github.bjspi.smsrelayer.ui.common.LabeledValue
import io.github.bjspi.smsrelayer.ui.common.SectionCard
import io.github.bjspi.smsrelayer.ui.common.StatusBadge
import io.github.bjspi.smsrelayer.ui.common.UiMessageSnackbarEffect
import io.github.bjspi.smsrelayer.ui.common.openBatteryOptimizationSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    UiMessageSnackbarEffect(viewModel.messageFlow, snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DeviceSection(state, viewModel) }
            item { LanguageSection() }
            item { BotSection(state, viewModel) }
            item { TargetsSection(state, viewModel) }
            item { PreviewSection(state) }
            item { ServiceSection(viewModel, context) }
        }
    }

    state.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.settings_delete_target_title)) },
            text = {
                Text(stringResource(R.string.settings_delete_target_body, target.displayName, target.chatId))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeviceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = stringResource(R.string.settings_section_device)) {
        OutlinedTextField(
            value = state.deviceNameDraft,
            onValueChange = viewModel::onDeviceNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.device_label)) },
            singleLine = true,
        )
        Button(
            onClick = viewModel::saveDeviceName,
            enabled = state.deviceNameDraft.isNotBlank(),
        ) {
            Text(stringResource(R.string.action_save))
        }
        LabeledValue(
            label = stringResource(R.string.status_app_version),
            value = BuildConfig.VERSION_NAME,
        )
    }
}

private enum class AppLanguage(val tag: String?, val labelRes: Int) {
    System(null, R.string.language_system),
    English("en", R.string.language_english),
    German("de", R.string.language_german),
}

/**
 * Per-app language preference (Android 13+ exposes it in system settings too;
 * AppCompat backports it down to this app's minSdk). An empty locale list
 * means "follow the system language".
 */
@Composable
private fun LanguageSection() {
    val appliedTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val current = AppLanguage.entries.firstOrNull { language ->
        language.tag != null && appliedTags.startsWith(language.tag)
    } ?: AppLanguage.System

    SectionCard(title = stringResource(R.string.settings_section_language)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { language ->
                FilterChip(
                    selected = current == language,
                    onClick = {
                        AppCompatDelegate.setApplicationLocales(
                            language.tag
                                ?.let(LocaleListCompat::forLanguageTags)
                                ?: LocaleListCompat.getEmptyLocaleList(),
                        )
                    },
                    label = { Text(stringResource(language.labelRes)) },
                )
            }
        }
    }
}

@Composable
private fun BotSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(
        title = stringResource(R.string.settings_section_bot),
        subtitle = stringResource(R.string.bot_body),
    ) {
        OutlinedTextField(
            value = state.tokenDraft,
            onValueChange = viewModel::onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.bot_label)) },
            singleLine = true,
            visualTransformation = if (state.tokenVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = viewModel::toggleTokenVisibility) {
                    Text(
                        stringResource(if (state.tokenVisible) R.string.bot_hide else R.string.bot_show),
                    )
                }
            },
        )
        if (state.tokenVerifying) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.botInfo?.let { info ->
            Text(
                text = stringResource(R.string.bot_connected, info.username ?: info.firstName),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::verifyToken,
                enabled = !state.tokenVerifying && state.tokenDraft.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.bot_check))
            }
            Button(
                onClick = viewModel::saveToken,
                enabled = state.tokenDraft.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun TargetsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(
        title = stringResource(R.string.settings_section_targets),
        subtitle = stringResource(R.string.targets_body),
    ) {
        if (state.targets.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_targets),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.targets.forEach { target ->
            val draft = state.targetDrafts[target.id] ?: TargetDraft.of(target)
            TargetEditor(
                target = target,
                draft = draft,
                onDraftChange = { viewModel.onDraftChange(target.id, it) },
                onSave = { viewModel.saveTarget(target) },
                onTest = { viewModel.testTarget(target) },
                onDelete = { viewModel.requestDelete(target) },
            )
        }

        DiscoverySection(state, viewModel)

        Text(
            text = stringResource(R.string.target_add),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = state.newTargetName,
            onValueChange = viewModel::onNewTargetNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.target_name_label)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.newTargetChatId,
            onValueChange = viewModel::onNewTargetChatIdChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.target_chat_id_label)) },
            singleLine = true,
        )
        Button(
            onClick = viewModel::addTarget,
            enabled = state.newTargetName.isNotBlank() && state.newTargetChatId.isNotBlank(),
        ) {
            Text(stringResource(R.string.action_add))
        }
    }
}

@Composable
private fun DiscoverySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    OutlinedButton(
        onClick = viewModel::discoverChats,
        enabled = !state.discovering,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (state.discovering) R.string.targets_discovering else R.string.targets_discover,
            ),
        )
    }
    if (state.discoveredChats.isNotEmpty()) {
        Text(
            text = stringResource(R.string.targets_found_title),
            style = MaterialTheme.typography.titleSmall,
        )
        state.discoveredChats.forEach { candidate ->
            DiscoveredChatCard(
                displayName = candidate.displayName,
                chatId = candidate.chatId,
                type = candidate.type,
                onAdd = { viewModel.addDiscovered(candidate) },
                onUse = { viewModel.useDiscovered(candidate) },
            )
        }
    }
}

@Composable
private fun PreviewSection(state: SettingsUiState) {
    SectionCard(
        title = stringResource(R.string.settings_section_preview),
        subtitle = stringResource(R.string.settings_preview_hint),
    ) {
        HtmlPreviewCard(
            title = stringResource(R.string.settings_preview_sms_title),
            html = state.smsPreviewHtml,
        )
        HtmlPreviewCard(
            title = stringResource(R.string.settings_preview_test_title),
            html = state.testPreviewHtml,
        )
    }
}

@Composable
private fun HtmlPreviewCard(title: String, html: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        setTextIsSelectable(true)
                        textSize = 14f
                    }
                },
                update = { textView ->
                    textView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
                },
            )
        }
    }
}

@Composable
private fun TargetEditor(
    target: TelegramChatTarget,
    draft: TargetDraft,
    onDraftChange: (TargetDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        text = stringResource(
                            if (draft.enabled) R.string.target_enabled else R.string.target_disabled,
                        ),
                        tone = if (draft.enabled) BadgeTone.Positive else BadgeTone.Neutral,
                    )
                    target.lastTestSuccessful?.let { successful ->
                        StatusBadge(
                            text = stringResource(
                                if (successful) R.string.test_result_ok else R.string.test_result_failed,
                            ),
                            tone = if (successful) BadgeTone.Positive else BadgeTone.Negative,
                        )
                    }
                }
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
                )
            }
            OutlinedTextField(
                value = draft.displayName,
                onValueChange = { onDraftChange(draft.copy(displayName = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.target_name_label)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.chatId,
                onValueChange = { onDraftChange(draft.copy(chatId = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.target_chat_id_label)) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave) { Text(stringResource(R.string.action_save)) }
                OutlinedButton(onClick = onTest) { Text(stringResource(R.string.action_test)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun ServiceSection(viewModel: SettingsViewModel, context: Context) {
    SectionCard(title = stringResource(R.string.settings_section_service)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::restartService,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.status_restart_service))
            }
            OutlinedButton(
                onClick = { openBatteryOptimizationSettings(context) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.permissions_battery_open))
            }
        }
    }
}
