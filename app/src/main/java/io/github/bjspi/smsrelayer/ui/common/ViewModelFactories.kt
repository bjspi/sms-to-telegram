package io.github.bjspi.smsrelayer.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.bjspi.smsrelayer.app.SmsRelayerApp
import io.github.bjspi.smsrelayer.di.AppContainer

/**
 * Bridges manual DI into the ViewModel world: resolves the [AppContainer] from
 * the Application inside ViewModel creation extras, so screens can obtain
 * fully-wired ViewModels without any service locator calls in composables.
 */
inline fun <reified VM : ViewModel> containerViewModelFactory(
    crossinline create: (AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
            as? SmsRelayerApp
            ?: error("ViewModel created outside of SmsRelayerApp")
        create(application.container)
    }
}
