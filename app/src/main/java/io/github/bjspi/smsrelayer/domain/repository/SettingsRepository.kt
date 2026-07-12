package io.github.bjspi.smsrelayer.domain.repository

import io.github.bjspi.smsrelayer.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/** Read/write access to app configuration. Implemented on top of DataStore. */
interface SettingsRepository {

    /** Emits the current settings and every subsequent change. */
    val settings: Flow<AppSettings>

    suspend fun updateDeviceName(name: String)

    /** Persists the token encrypted at rest; `null` clears it. */
    suspend fun updateTelegramBotToken(token: String?)

    suspend fun markOnboardingCompleted()

    suspend fun updateLastSuccessfulTelegramSendAt(timestamp: Long)

    suspend fun updateLastSmsReceivedAt(timestamp: Long)

    suspend fun updateLastServiceHeartbeatAt(timestamp: Long)
}
