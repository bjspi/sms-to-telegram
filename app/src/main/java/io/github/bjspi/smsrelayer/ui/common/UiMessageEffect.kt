package io.github.bjspi.smsrelayer.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow

/** Collects a ViewModel's one-shot [UiMessage] stream into a snackbar host. */
@Composable
fun UiMessageSnackbarEffect(messages: Flow<UiMessage>, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages, snackbarHostState) {
        messages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }
}
