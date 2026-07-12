package io.github.bjspi.smsrelayer.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.domain.model.TestResult
import io.github.bjspi.smsrelayer.ui.common.BadgeTone
import io.github.bjspi.smsrelayer.ui.common.DiscoveredChatCard
import io.github.bjspi.smsrelayer.ui.common.SectionCard
import io.github.bjspi.smsrelayer.ui.common.StatusBadge
import io.github.bjspi.smsrelayer.ui.common.UiMessageSnackbarEffect
import io.github.bjspi.smsrelayer.ui.common.openBatteryOptimizationSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
    }

    UiMessageSnackbarEffect(viewModel.messageFlow, snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StepIndicator(current = state.step)

            when (state.step) {
                OnboardingStep.Welcome -> WelcomeStep(viewModel)
                OnboardingStep.DeviceName -> DeviceNameStep(state, viewModel)
                OnboardingStep.BotToken -> BotTokenStep(state, viewModel)
                OnboardingStep.ChatTargets -> ChatTargetsStep(state, viewModel)
                OnboardingStep.Permissions -> PermissionsStep(
                    state = state,
                    viewModel = viewModel,
                    onRequestPermissions = {
                        permissionLauncher.launch(viewModel.requiredRuntimePermissions())
                    },
                    onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
                )

                OnboardingStep.Test -> TestStep(state, viewModel)
                OnboardingStep.Done -> DoneStep(state, viewModel)
            }
        }
    }
}

@Composable
private fun StepIndicator(current: OnboardingStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnboardingStep.entries.forEach { step ->
            FilterChip(
                selected = step == current,
                onClick = {},
                enabled = false,
                label = { Text(stringResource(step.labelRes)) },
            )
        }
    }
}

@Composable
private fun WelcomeStep(viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.welcome_title)) {
        Text(stringResource(R.string.welcome_body))
        Button(onClick = viewModel::startSetup, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.welcome_start))
        }
    }
}

@Composable
private fun DeviceNameStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.device_title)) {
        Text(stringResource(R.string.device_body))
        OutlinedTextField(
            value = state.deviceName,
            onValueChange = viewModel::onDeviceNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.device_label)) },
            placeholder = { Text(stringResource(R.string.device_hint)) },
            singleLine = true,
            isError = state.deviceName.isNotEmpty() && state.deviceName.trim().length < 2,
        )
        StepButtons(onBack = viewModel::back, onNext = viewModel::submitDeviceName)
    }
}

@Composable
private fun BotTokenStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.bot_title)) {
        Text(stringResource(R.string.bot_body))
        OutlinedTextField(
            value = state.token,
            onValueChange = viewModel::onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.bot_label)) },
            placeholder = { Text("123456789:ABC…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::back, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_back))
            }
            OutlinedButton(
                onClick = viewModel::verifyToken,
                enabled = !state.tokenVerifying && state.token.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.bot_check))
            }
            Button(
                onClick = viewModel::submitToken,
                enabled = !state.tokenVerifying,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_next))
            }
        }
    }
}

@Composable
private fun ChatTargetsStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.targets_title)) {
        Text(stringResource(R.string.targets_body))

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
        if (state.discoveredChats.isEmpty()) {
            Text(
                text = stringResource(R.string.targets_discover_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.discoveredChats.forEach { candidate ->
            DiscoveredChatCard(
                displayName = candidate.displayName,
                chatId = candidate.chatId,
                type = candidate.type,
                onAdd = { viewModel.addDiscovered(candidate) },
            )
        }

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
        OutlinedButton(onClick = viewModel::addTarget, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.target_add))
        }

        state.targets.forEach { target ->
            TargetRow(target, viewModel)
        }

        StepButtons(onBack = viewModel::back, onNext = viewModel::submitTargets)
    }
}

@Composable
private fun TargetRow(target: TelegramChatTarget, viewModel: OnboardingViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(target.displayName, style = MaterialTheme.typography.titleSmall)
                StatusBadge(
                    text = stringResource(
                        if (target.enabled) R.string.target_enabled else R.string.target_disabled,
                    ),
                    tone = if (target.enabled) BadgeTone.Positive else BadgeTone.Neutral,
                )
            }
            Text(
                text = target.chatId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.testTarget(target) }) {
                    Text(stringResource(R.string.action_test))
                }
                TextButton(onClick = { viewModel.removeTarget(target) }) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.permissions_title)) {
        Text(stringResource(R.string.permissions_body))
        state.permissions.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(item.labelRes))
                StatusBadge(
                    text = stringResource(
                        when {
                            item.granted -> R.string.permission_state_granted
                            item.optional -> R.string.permission_state_optional
                            else -> R.string.permission_state_missing
                        },
                    ),
                    tone = when {
                        item.granted -> BadgeTone.Positive
                        item.optional -> BadgeTone.Warning
                        else -> BadgeTone.Negative
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.permissions_request))
            }
            OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.permissions_battery_open))
            }
        }
        StepButtons(onBack = viewModel::back, onNext = viewModel::submitPermissions)
    }
}

@Composable
private fun TestStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.test_title)) {
        Text(stringResource(R.string.test_body))
        Button(
            onClick = viewModel::sendTestToAll,
            enabled = !state.testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.test_send_all))
        }
        if (state.testing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        state.testResults.forEach { result ->
            TestResultRow(result)
        }
        StepButtons(
            onBack = viewModel::back,
            onNext = viewModel::submitTest,
            nextLabel = stringResource(R.string.test_finish_setup),
        )
    }
}

@Composable
private fun TestResultRow(result: TestResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.displayName.ifBlank { result.chatId },
                    style = MaterialTheme.typography.titleSmall,
                )
                StatusBadge(
                    text = stringResource(
                        if (result.success) R.string.test_result_ok else R.string.test_result_failed,
                    ),
                    tone = if (result.success) BadgeTone.Positive else BadgeTone.Negative,
                )
            }
            result.errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DoneStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.done_title)) {
        Text(stringResource(R.string.done_body))
        if (state.finishing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = viewModel::finish,
            enabled = !state.finishing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.done_finish))
        }
    }
}

@Composable
private fun StepButtons(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String = stringResource(R.string.action_next),
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_back))
        }
        Button(onClick = onNext, modifier = Modifier.weight(1f)) {
            Text(nextLabel)
        }
    }
}
