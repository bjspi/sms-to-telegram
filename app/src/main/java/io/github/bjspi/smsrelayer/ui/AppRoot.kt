package io.github.bjspi.smsrelayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.ui.common.containerViewModelFactory
import io.github.bjspi.smsrelayer.ui.navigation.MainScaffold
import io.github.bjspi.smsrelayer.ui.onboarding.OnboardingScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Top-level gate: shows onboarding until it is completed, then the main app.
 * Driven directly by the persisted flag, so finishing onboarding switches the
 * UI without any manual navigation plumbing.
 */
@Composable
fun AppRoot(viewModel: AppRootViewModel = viewModel(factory = AppRootViewModel.Factory)) {
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()

    when (onboardingCompleted) {
        null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        false -> OnboardingScreen()

        true -> MainScaffold()
    }
}

class AppRootViewModel(private val container: AppContainer) : ViewModel() {

    /** `null` while the first DataStore read is in flight. */
    val onboardingCompleted: StateFlow<Boolean?> =
        container.settingsRepository.settings
            .map { it.onboardingCompleted }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Opening the app is a natural self-healing point: an onboarded device
        // gets its service and watchdog back even if the OS killed them.
        viewModelScope.launch {
            container.settingsRepository.settings.first { it.onboardingCompleted }
            container.workScheduler.schedulePeriodicWatchdog()
            container.serviceController.ensureRunning(reason = "app_open")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = containerViewModelFactory(::AppRootViewModel)
    }
}
