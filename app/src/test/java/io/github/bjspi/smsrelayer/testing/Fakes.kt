package io.github.bjspi.smsrelayer.testing

import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.model.AppSettings
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.LogEntry
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import io.github.bjspi.smsrelayer.domain.model.QueueCounts
import io.github.bjspi.smsrelayer.domain.model.QueueStatus
import io.github.bjspi.smsrelayer.domain.model.RelayQueueItem
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.domain.model.TelegramBotInfo
import io.github.bjspi.smsrelayer.domain.model.TelegramChatCandidate
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.domain.repository.ChatTargetRepository
import io.github.bjspi.smsrelayer.domain.repository.EventLog
import io.github.bjspi.smsrelayer.domain.repository.RelayQueueRepository
import io.github.bjspi.smsrelayer.domain.repository.SettingsRepository
import io.github.bjspi.smsrelayer.domain.repository.SmsEventRepository
import io.github.bjspi.smsrelayer.domain.telegram.TelegramGateway
import io.github.bjspi.smsrelayer.domain.telegram.TelegramSendOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class FixedClock(var currentTime: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = currentTime
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    override suspend fun updateDeviceName(name: String) {
        state.value = state.value.copy(deviceName = name)
    }

    override suspend fun updateTelegramBotToken(token: String?) {
        state.value = state.value.copy(telegramBotToken = token)
    }

    override suspend fun markOnboardingCompleted() {
        state.value = state.value.copy(onboardingCompleted = true)
    }

    override suspend fun updateLastSuccessfulTelegramSendAt(timestamp: Long) {
        state.value = state.value.copy(lastSuccessfulTelegramSendAt = timestamp)
    }

    override suspend fun updateLastSmsReceivedAt(timestamp: Long) {
        state.value = state.value.copy(lastSmsReceivedAt = timestamp)
    }

    override suspend fun updateLastServiceHeartbeatAt(timestamp: Long) {
        state.value = state.value.copy(lastServiceHeartbeatAt = timestamp)
    }
}

class FakeSmsEventRepository : SmsEventRepository {

    private val events = MutableStateFlow<List<SmsEvent>>(emptyList())
    private var nextId = 1L

    override fun observeLatest(limit: Int): Flow<List<SmsEvent>> =
        events.map { it.sortedByDescending(SmsEvent::receivedAt).take(limit) }

    override suspend fun save(event: SmsEvent): SmsEvent {
        val stored = event.copy(id = nextId++)
        events.value += stored
        return stored
    }

    override suspend fun getById(id: Long): SmsEvent? = events.value.find { it.id == id }

    override suspend fun getLatest(): SmsEvent? = events.value.maxByOrNull(SmsEvent::receivedAt)
}

class FakeChatTargetRepository : ChatTargetRepository {

    private val targets = MutableStateFlow<List<TelegramChatTarget>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<TelegramChatTarget>> = targets

    override fun observeEnabled(): Flow<List<TelegramChatTarget>> =
        targets.map { list -> list.filter(TelegramChatTarget::enabled) }

    override suspend fun getEnabled(): List<TelegramChatTarget> =
        targets.value.filter(TelegramChatTarget::enabled)

    override suspend fun getById(id: Long): TelegramChatTarget? =
        targets.value.find { it.id == id }

    override suspend fun getByChatId(chatId: String): TelegramChatTarget? =
        targets.value.find { it.chatId == chatId }

    override suspend fun add(displayName: String, chatId: String, enabled: Boolean): TelegramChatTarget {
        val target = TelegramChatTarget(
            id = nextId++,
            displayName = displayName,
            chatId = chatId,
            enabled = enabled,
            createdAt = 0L,
            lastTestAt = null,
            lastTestSuccessful = null,
        )
        targets.value += target
        return target
    }

    override suspend fun update(target: TelegramChatTarget) {
        targets.value = targets.value.map { if (it.id == target.id) target else it }
    }

    override suspend fun deleteById(id: Long) {
        targets.value = targets.value.filterNot { it.id == id }
    }
}

/** In-memory queue that mirrors the transactional claim semantics of the DAO. */
class FakeRelayQueueRepository : RelayQueueRepository {

    private val items = MutableStateFlow<List<RelayQueueItem>>(emptyList())
    private var nextId = 1L

    val snapshot: List<RelayQueueItem> get() = items.value

    override suspend fun enqueue(newItems: List<RelayQueueItem>): List<RelayQueueItem> {
        val stored = newItems.map { it.copy(id = nextId++) }
        items.value += stored
        return stored
    }

    override suspend fun claimDue(now: Long, limit: Int): List<RelayQueueItem> {
        val due = items.value
            .filter {
                (it.status == QueueStatus.Pending || it.status == QueueStatus.FailedRetryable) &&
                    it.nextAttemptAt <= now
            }
            .sortedBy(RelayQueueItem::createdAt)
            .take(limit)
            .map { it.copy(status = QueueStatus.Sending, updatedAt = now) }
        due.forEach { claimed -> replace(claimed) }
        return due
    }

    override suspend fun recoverStaleSending(cutoff: Long, now: Long): Int {
        val stale = items.value.filter { it.status == QueueStatus.Sending && it.updatedAt < cutoff }
        stale.forEach { replace(it.copy(status = QueueStatus.Pending, updatedAt = now)) }
        return stale.size
    }

    override suspend fun update(item: RelayQueueItem) {
        replace(item)
    }

    override fun observeCounts(): Flow<QueueCounts> = items.map { counts(it) }

    override suspend fun getCounts(): QueueCounts = counts(items.value)

    private fun counts(list: List<RelayQueueItem>) = QueueCounts(
        pending = list.count { it.status == QueueStatus.Pending },
        retryable = list.count { it.status == QueueStatus.FailedRetryable },
        sent = list.count { it.status == QueueStatus.Sent },
    )

    private fun replace(item: RelayQueueItem) {
        items.value = items.value.map { if (it.id == item.id) item else it }
    }
}

class RecordingEventLog : EventLog {

    val entries = mutableListOf<LogEntry>()

    override suspend fun log(level: LogLevel, category: LogCategory, message: String, details: String?) {
        entries += LogEntry(
            id = entries.size + 1L,
            timestamp = 0L,
            level = level,
            category = category,
            message = message,
            details = details,
        )
    }

    override fun observeLatest(limit: Int): Flow<List<LogEntry>> = flowOf(entries.take(limit))

    override suspend fun search(query: String, limit: Int): List<LogEntry> =
        entries.filter { it.message.contains(query) || it.details?.contains(query) == true }

    override suspend fun clear() {
        entries.clear()
    }
}

/** Scripted gateway: hand out predefined outcomes per chat id, record every send. */
class FakeTelegramGateway(
    var defaultOutcome: TelegramSendOutcome = TelegramSendOutcome.Delivered(200),
) : TelegramGateway {

    val sentMessages = mutableListOf<Pair<String, String>>()
    val outcomesByChat = mutableMapOf<String, TelegramSendOutcome>()

    override suspend fun getMe(token: String): Result<TelegramBotInfo> =
        Result.success(TelegramBotInfo(id = 1L, isBot = true, firstName = "TestBot", username = "test_bot"))

    override suspend fun sendMessage(token: String, chatId: String, htmlText: String): TelegramSendOutcome {
        sentMessages += chatId to htmlText
        return outcomesByChat[chatId] ?: defaultOutcome
    }

    override suspend fun getUpdates(token: String): Result<List<TelegramChatCandidate>> =
        Result.success(emptyList())
}
