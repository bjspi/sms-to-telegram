package io.github.bjspi.smsrelayer.app

import android.app.Application
import io.github.bjspi.smsrelayer.di.AppContainer
import io.github.bjspi.smsrelayer.service.RelayNotifications

class SmsRelayerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Channels must exist before any component (service, expedited worker)
        // posts a notification; creating them is idempotent and cheap.
        RelayNotifications.ensureChannels(this)
    }
}
