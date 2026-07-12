package io.github.bjspi.smsrelayer.di

import android.content.Context
import io.github.bjspi.smsrelayer.app.SmsRelayerApp
import io.github.bjspi.smsrelayer.data.crypto.TokenCipher
import io.github.bjspi.smsrelayer.data.database.SmsRelayerDatabase
import io.github.bjspi.smsrelayer.data.repository.RoomChatTargetRepository
import io.github.bjspi.smsrelayer.data.repository.RoomEventLog
import io.github.bjspi.smsrelayer.data.repository.RoomRelayQueueRepository
import io.github.bjspi.smsrelayer.data.repository.RoomSmsEventRepository
import io.github.bjspi.smsrelayer.data.settings.DataStoreSettingsRepository
import io.github.bjspi.smsrelayer.data.settings.settingsDataStore
import io.github.bjspi.smsrelayer.data.telegram.TelegramHttpGateway
import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.repository.ChatTargetRepository
import io.github.bjspi.smsrelayer.domain.repository.EventLog
import io.github.bjspi.smsrelayer.domain.repository.RelayQueueRepository
import io.github.bjspi.smsrelayer.domain.repository.SettingsRepository
import io.github.bjspi.smsrelayer.domain.repository.SmsEventRepository
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.domain.telegram.TelegramGateway
import io.github.bjspi.smsrelayer.domain.telegram.TelegramMessageFormatter
import io.github.bjspi.smsrelayer.domain.telegram.TelegramMessageTexts
import io.github.bjspi.smsrelayer.domain.usecase.AddChatTarget
import io.github.bjspi.smsrelayer.domain.usecase.EnqueueIncomingSms
import io.github.bjspi.smsrelayer.domain.usecase.ProcessRelayQueue
import io.github.bjspi.smsrelayer.domain.usecase.SendTestMessage
import io.github.bjspi.smsrelayer.platform.SystemStateInspector
import io.github.bjspi.smsrelayer.service.RelayServiceController
import io.github.bjspi.smsrelayer.work.RelayWorkScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Composition root. One instance lives on the [SmsRelayerApp]; every entry
 * point (activity, receiver, service, worker) resolves its dependencies from
 * here, so the object graph is wired exactly once.
 *
 * Everything is lazy: a broadcast wake-up that only needs the database never
 * pays for an HTTP client, and vice versa.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val clock: Clock = Clock.System

    /**
     * Process-wide scope for fire-and-forget work that must not die with a
     * single component (e.g. finishing a broadcast after the receiver returned).
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: SmsRelayerDatabase by lazy { SmsRelayerDatabase.build(appContext) }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy {
        DataStoreSettingsRepository(appContext.settingsDataStore, TokenCipher())
    }

    val chatTargetRepository: ChatTargetRepository by lazy {
        RoomChatTargetRepository(database.telegramTargetDao(), clock)
    }

    val smsEventRepository: SmsEventRepository by lazy {
        RoomSmsEventRepository(database.smsEventDao())
    }

    val relayQueueRepository: RelayQueueRepository by lazy {
        RoomRelayQueueRepository(database.relayQueueDao())
    }

    val eventLog: EventLog by lazy { RoomEventLog(database.logDao(), clock) }

    val telegramGateway: TelegramGateway by lazy { TelegramHttpGateway(httpClient) }

    /** Formatter with labels from resources, so relayed messages follow the app language. */
    val messageFormatter: TelegramMessageFormatter by lazy {
        TelegramMessageFormatter(
            textsProvider = {
                TelegramMessageTexts(
                    smsHeader = appContext.getString(R.string.tg_sms_header),
                    deviceLabel = appContext.getString(R.string.tg_device_label),
                    timeLabel = appContext.getString(R.string.tg_time_label),
                    fromLabel = appContext.getString(R.string.tg_from_label),
                    toSimLabel = appContext.getString(R.string.tg_to_sim_label),
                    messageLabel = appContext.getString(R.string.tg_message_label),
                    testHeader = appContext.getString(R.string.tg_test_header),
                    targetLabel = appContext.getString(R.string.tg_target_label),
                    testBody = appContext.getString(R.string.tg_test_body),
                    unavailable = appContext.getString(R.string.tg_unavailable),
                )
            },
        )
    }

    val enqueueIncomingSms: EnqueueIncomingSms by lazy {
        EnqueueIncomingSms(
            smsEvents = smsEventRepository,
            chatTargets = chatTargetRepository,
            relayQueue = relayQueueRepository,
            settings = settingsRepository,
            eventLog = eventLog,
            clock = clock,
        )
    }

    /** Single instance on purpose: its internal mutex serializes queue drains. */
    val processRelayQueue: ProcessRelayQueue by lazy {
        ProcessRelayQueue(
            settings = settingsRepository,
            smsEvents = smsEventRepository,
            relayQueue = relayQueueRepository,
            telegram = telegramGateway,
            formatter = messageFormatter,
            eventLog = eventLog,
            clock = clock,
        )
    }

    val addChatTarget: AddChatTarget by lazy { AddChatTarget(chatTargetRepository) }

    val sendTestMessage: SendTestMessage by lazy {
        SendTestMessage(
            chatTargets = chatTargetRepository,
            settings = settingsRepository,
            telegram = telegramGateway,
            formatter = messageFormatter,
            eventLog = eventLog,
            clock = clock,
        )
    }

    val workScheduler: RelayWorkScheduler by lazy { RelayWorkScheduler(appContext) }

    val serviceController: RelayServiceController by lazy {
        RelayServiceController(appContext, workScheduler, eventLog, applicationScope)
    }

    val systemStateInspector: SystemStateInspector by lazy { SystemStateInspector(appContext) }
}

/** Convenience accessor for entry points that only hold a [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SmsRelayerApp).container
