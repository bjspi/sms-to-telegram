package io.github.bjspi.smsrelayer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.bjspi.smsrelayer.data.crypto.TokenCipher
import io.github.bjspi.smsrelayer.domain.model.AppSettings
import io.github.bjspi.smsrelayer.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sms_relayer_settings")

/**
 * DataStore-backed settings. The bot token is transparently encrypted with
 * [TokenCipher] before it touches disk; consumers of [settings] only ever see
 * plaintext.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val tokenCipher: TokenCipher,
) : SettingsRepository {

    /**
     * Decryption cache for the last seen ciphertext. Settings emit on every
     * preference change, and running Keystore crypto on each emission would be
     * wasted work for a value that rarely changes.
     */
    @Volatile
    private var cachedToken: Pair<String, String?>? = null

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            AppSettings(
                onboardingCompleted = preferences[Keys.ONBOARDING_COMPLETED] ?: false,
                deviceName = preferences[Keys.DEVICE_NAME].orEmpty(),
                telegramBotToken = preferences[Keys.BOT_TOKEN_ENCRYPTED]?.let(::decryptCached),
                lastSuccessfulTelegramSendAt = preferences[Keys.LAST_TELEGRAM_SUCCESS_AT],
                lastSmsReceivedAt = preferences[Keys.LAST_SMS_RECEIVED_AT],
                lastServiceHeartbeatAt = preferences[Keys.LAST_HEARTBEAT_AT],
            )
        }
        // Keystore decryption (binder IPC + AES) must never run on the
        // collector's dispatcher — ViewModels collect on Main.
        .flowOn(Dispatchers.IO)

    override suspend fun updateDeviceName(name: String) {
        dataStore.edit { it[Keys.DEVICE_NAME] = name }
    }

    override suspend fun updateTelegramBotToken(token: String?) {
        dataStore.edit { preferences ->
            if (token.isNullOrBlank()) {
                preferences.remove(Keys.BOT_TOKEN_ENCRYPTED)
            } else {
                preferences[Keys.BOT_TOKEN_ENCRYPTED] = tokenCipher.encrypt(token)
            }
        }
    }

    override suspend fun markOnboardingCompleted() {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = true }
    }

    override suspend fun updateLastSuccessfulTelegramSendAt(timestamp: Long) {
        dataStore.edit { it[Keys.LAST_TELEGRAM_SUCCESS_AT] = timestamp }
    }

    override suspend fun updateLastSmsReceivedAt(timestamp: Long) {
        dataStore.edit { it[Keys.LAST_SMS_RECEIVED_AT] = timestamp }
    }

    override suspend fun updateLastServiceHeartbeatAt(timestamp: Long) {
        dataStore.edit { it[Keys.LAST_HEARTBEAT_AT] = timestamp }
    }

    private fun decryptCached(ciphertext: String): String? {
        cachedToken?.let { (cached, plaintext) ->
            if (cached == ciphertext) return plaintext
        }
        return tokenCipher.decrypt(ciphertext).also { cachedToken = ciphertext to it }
    }

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val BOT_TOKEN_ENCRYPTED = stringPreferencesKey("telegram_bot_token_encrypted")
        val LAST_TELEGRAM_SUCCESS_AT = longPreferencesKey("last_successful_telegram_send_at")
        val LAST_SMS_RECEIVED_AT = longPreferencesKey("last_sms_received_at")
        val LAST_HEARTBEAT_AT = longPreferencesKey("last_service_heartbeat_at")
    }
}
