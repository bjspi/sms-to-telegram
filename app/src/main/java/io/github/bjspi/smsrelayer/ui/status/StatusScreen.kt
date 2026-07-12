package io.github.bjspi.smsrelayer.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bjspi.smsrelayer.BuildConfig
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.domain.util.compactSingleLine
import io.github.bjspi.smsrelayer.ui.common.BadgeTone
import io.github.bjspi.smsrelayer.ui.common.LabeledValue
import io.github.bjspi.smsrelayer.ui.common.SectionCard
import io.github.bjspi.smsrelayer.ui.common.StatusBadge
import io.github.bjspi.smsrelayer.ui.common.UiMessageSnackbarEffect
import io.github.bjspi.smsrelayer.ui.common.formatRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: StatusViewModel = viewModel(factory = StatusViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    UiMessageSnackbarEffect(viewModel.messageFlow, snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        if (state.deviceName.isNotBlank()) {
                            Text(
                                text = state.deviceName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RelayCard(state, viewModel)
            DeviceCard(state)
            LatestSmsCard(state, context = LocalContext.current)
            QueueCard(state, viewModel)
        }
    }
}

@Composable
private fun RelayCard(state: StatusUiState, viewModel: StatusViewModel) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.status_card_relay)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.serviceHealth) {
                ServiceHealth.Running ->
                    StatusBadge(stringResource(R.string.status_service_running), BadgeTone.Positive)

                ServiceHealth.Stale ->
                    StatusBadge(stringResource(R.string.status_service_stale), BadgeTone.Warning)

                ServiceHealth.Unknown ->
                    StatusBadge(stringResource(R.string.status_service_unknown), BadgeTone.Neutral)
            }
            StatusBadge(
                text = stringResource(
                    if (state.botTokenConfigured) R.string.status_bot_configured else R.string.status_bot_missing,
                ),
                tone = if (state.botTokenConfigured) BadgeTone.Positive else BadgeTone.Negative,
            )
        }
        LabeledValue(
            label = stringResource(R.string.status_heartbeat),
            value = formatRelativeTime(context, state.lastHeartbeatAt),
        )
        LabeledValue(
            label = stringResource(R.string.status_last_sms),
            value = formatRelativeTime(context, state.lastSmsAt),
        )
        LabeledValue(
            label = stringResource(R.string.status_last_delivery),
            value = formatRelativeTime(context, state.lastDeliveryAt),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::sendTestToAll,
                enabled = !state.actionInFlight,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.status_send_test_all))
            }
            OutlinedButton(
                onClick = viewModel::restartService,
                enabled = !state.actionInFlight,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.status_restart_service))
            }
        }
    }
}

@Composable
private fun DeviceCard(state: StatusUiState) {
    SectionCard(title = stringResource(R.string.status_card_device)) {
        LabeledValue(
            label = stringResource(R.string.status_device_name),
            value = state.deviceName.ifBlank { stringResource(R.string.common_unknown) },
        )
        LabeledValue(
            label = stringResource(R.string.status_app_version),
            value = BuildConfig.VERSION_NAME,
        )
        LabeledValue(
            label = stringResource(R.string.status_battery_exempt),
            value = stringResource(if (state.batteryExempt) R.string.common_yes else R.string.common_no),
            valueColor = if (state.batteryExempt) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        LabeledValue(
            label = stringResource(R.string.status_card_targets),
            value = stringResource(R.string.status_targets_summary, state.enabledTargets, state.totalTargets),
        )
    }
}

@Composable
private fun LatestSmsCard(state: StatusUiState, context: android.content.Context) {
    SectionCard(title = stringResource(R.string.status_card_last_sms)) {
        val sms = state.latestSms
        if (sms == null) {
            Text(
                text = stringResource(R.string.status_none_yet),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabeledValue(
                label = stringResource(R.string.status_sms_from),
                value = sms.sender.ifBlank { stringResource(R.string.common_unknown) },
            )
            LabeledValue(
                label = stringResource(R.string.status_sms_received),
                value = formatRelativeTime(context, sms.receivedAt),
            )
            Text(
                text = sms.body.compactSingleLine(120),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QueueCard(state: StatusUiState, viewModel: StatusViewModel) {
    SectionCard(title = stringResource(R.string.status_card_queue)) {
        LabeledValue(stringResource(R.string.queue_pending), state.queue.pending.toString())
        LabeledValue(stringResource(R.string.queue_retryable), state.queue.retryable.toString())
        LabeledValue(stringResource(R.string.queue_sent), state.queue.sent.toString())
        Button(
            onClick = viewModel::drainQueue,
            enabled = !state.actionInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.status_drain_queue))
        }
    }
}
