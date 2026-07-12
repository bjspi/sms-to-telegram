package io.github.bjspi.smsrelayer.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.ui.common.BadgeTone
import io.github.bjspi.smsrelayer.ui.common.SectionCard
import io.github.bjspi.smsrelayer.ui.common.StatusBadge
import io.github.bjspi.smsrelayer.ui.common.UiMessageSnackbarEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    UiMessageSnackbarEffect(viewModel.messageFlow, snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_diagnostics)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.refreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SectionCard(title = stringResource(R.string.diag_actions_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::refresh,
                        enabled = !state.refreshing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.diag_refresh))
                    }
                    OutlinedButton(
                        onClick = viewModel::testBotConnection,
                        enabled = !state.actionInFlight,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.diag_action_test_bot))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::testAllTargets,
                        enabled = !state.actionInFlight,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.diag_action_test_all))
                    }
                    OutlinedButton(
                        onClick = viewModel::drainQueue,
                        enabled = !state.actionInFlight,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.diag_action_drain))
                    }
                }
                OutlinedButton(
                    onClick = viewModel::restartService,
                    enabled = !state.actionInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.status_restart_service))
                }
            }

            state.checks.forEach { check ->
                CheckCard(check)
            }
        }
    }
}

@Composable
private fun CheckCard(check: DiagnosticCheck) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(check.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = stringResource(check.state.labelRes()),
                    tone = check.state.badgeTone(),
                )
            }
            check.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun CheckState.labelRes(): Int = when (this) {
    CheckState.Ok -> R.string.diag_state_ok
    CheckState.Warning -> R.string.diag_state_warning
    CheckState.Failed -> R.string.diag_state_failed
    CheckState.Unknown -> R.string.diag_state_unknown
}

private fun CheckState.badgeTone(): BadgeTone = when (this) {
    CheckState.Ok -> BadgeTone.Positive
    CheckState.Warning -> BadgeTone.Warning
    CheckState.Failed -> BadgeTone.Negative
    CheckState.Unknown -> BadgeTone.Neutral
}
