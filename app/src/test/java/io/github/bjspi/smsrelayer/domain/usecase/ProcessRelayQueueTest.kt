package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.domain.model.AppSettings
import io.github.bjspi.smsrelayer.domain.model.QueueStatus
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.domain.telegram.TelegramMessageFormatter
import io.github.bjspi.smsrelayer.domain.telegram.TelegramSendOutcome
import io.github.bjspi.smsrelayer.testing.FakeChatTargetRepository
import io.github.bjspi.smsrelayer.testing.FakeRelayQueueRepository
import io.github.bjspi.smsrelayer.testing.FakeSettingsRepository
import io.github.bjspi.smsrelayer.testing.FakeSmsEventRepository
import io.github.bjspi.smsrelayer.testing.FakeTelegramGateway
import io.github.bjspi.smsrelayer.testing.FixedClock
import io.github.bjspi.smsrelayer.testing.RecordingEventLog
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ProcessRelayQueueTest {

    private val clock = FixedClock()
    private val settings = FakeSettingsRepository(
        AppSettings(onboardingCompleted = true, deviceName = "Relay", telegramBotToken = "1:AAAAAAAAAAAAAAAAAAAAAAAA"),
    )
    private val smsEvents = FakeSmsEventRepository()
    private val chatTargets = FakeChatTargetRepository()
    private val queue = FakeRelayQueueRepository()
    private val gateway = FakeTelegramGateway()
    private val eventLog = RecordingEventLog()

    private val enqueue = EnqueueIncomingSms(smsEvents, chatTargets, queue, settings, eventLog, clock)
    private val process = ProcessRelayQueue(
        settings = settings,
        smsEvents = smsEvents,
        relayQueue = queue,
        telegram = gateway,
        formatter = TelegramMessageFormatter(ZoneOffset.UTC),
        eventLog = eventLog,
        clock = clock,
    )

    private fun sms(body: String = "Hello world") = SmsEvent(
        id = 0L,
        receivedAt = clock.now(),
        sender = "+491701234567",
        recipientNumber = null,
        simSlot = null,
        subscriptionId = null,
        deviceName = "Relay",
        body = body,
        rawDebugInfo = null,
    )

    @Test
    fun `delivers due items to every enabled target`() = runTest {
        chatTargets.add("Alice", "111")
        chatTargets.add("Team", "222")
        chatTargets.add("Disabled", "333", enabled = false)
        enqueue(sms(body = "secret code 42"))

        val summary = process()

        assertEquals(2, summary.sent)
        assertEquals(0, summary.retryable)
        assertTrue(summary.fullyDrained)
        assertEquals(setOf("111", "222"), gateway.sentMessages.map { it.first }.toSet())
        assertTrue(gateway.sentMessages.all { "secret code 42" in it.second })
        assertTrue(queue.snapshot.all { it.status == QueueStatus.Sent })
        assertEquals(clock.now(), settings.state.first().lastSuccessfulTelegramSendAt)
    }

    @Test
    fun `skips processing when bot token is missing`() = runTest {
        settings.updateTelegramBotToken(null)
        chatTargets.add("Alice", "111")
        enqueue(sms())

        val summary = process()

        assertEquals(0, summary.processed)
        assertTrue(gateway.sentMessages.isEmpty())
        assertEquals(QueueStatus.Pending, queue.snapshot.single().status)
    }

    @Test
    fun `transient failure schedules retry with backoff`() = runTest {
        chatTargets.add("Alice", "111")
        gateway.defaultOutcome = TelegramSendOutcome.RetryLater(httpCode = 502, reason = "bad gateway")
        enqueue(sms())

        val summary = process()

        assertEquals(1, summary.retryable)
        val item = queue.snapshot.single()
        assertEquals(QueueStatus.FailedRetryable, item.status)
        assertEquals(1, item.attemptCount)
        assertEquals(clock.now() + 1.minutes.inWholeMilliseconds, item.nextAttemptAt)
        assertEquals("bad gateway", item.lastError)
    }

    @Test
    fun `rate limit retry-after overrides shorter backoff`() = runTest {
        chatTargets.add("Alice", "111")
        gateway.defaultOutcome = TelegramSendOutcome.RetryLater(
            httpCode = 429,
            reason = "too many requests",
            retryAfter = 600.seconds,
        )
        enqueue(sms())

        process()

        val item = queue.snapshot.single()
        assertEquals(clock.now() + 600.seconds.inWholeMilliseconds, item.nextAttemptAt)
    }

    @Test
    fun `permanent rejection never retries`() = runTest {
        chatTargets.add("Alice", "111")
        gateway.defaultOutcome = TelegramSendOutcome.Rejected(httpCode = 400, reason = "chat not found")
        enqueue(sms())

        val summary = process()

        assertEquals(1, summary.failedPermanently)
        assertEquals(QueueStatus.FailedPermanent, queue.snapshot.single().status)

        // A second drain finds nothing to do.
        assertEquals(0, process().processed)
    }

    @Test
    fun `one broken target does not block the others`() = runTest {
        chatTargets.add("Broken", "111")
        chatTargets.add("Healthy", "222")
        gateway.outcomesByChat["111"] = TelegramSendOutcome.Rejected(400, "chat not found")
        enqueue(sms())

        val summary = process()

        assertEquals(1, summary.sent)
        assertEquals(1, summary.failedPermanently)
        val byChat = queue.snapshot.associateBy { it.targetChatId }
        assertEquals(QueueStatus.FailedPermanent, byChat.getValue("111").status)
        assertEquals(QueueStatus.Sent, byChat.getValue("222").status)
    }

    @Test
    fun `items whose backoff has not elapsed are left untouched`() = runTest {
        chatTargets.add("Alice", "111")
        gateway.defaultOutcome = TelegramSendOutcome.RetryLater(502, "boom")
        enqueue(sms())
        process()
        gateway.defaultOutcome = TelegramSendOutcome.Delivered(200)

        // Not yet due.
        assertEquals(0, process().processed)

        // Due after the backoff window.
        clock.currentTime += 1.minutes.inWholeMilliseconds
        assertEquals(1, process().sent)
    }
}
