package io.github.bjspi.smsrelayer.domain.usecase

import io.github.bjspi.smsrelayer.domain.model.AppSettings
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import io.github.bjspi.smsrelayer.domain.model.QueueStatus
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.testing.FakeChatTargetRepository
import io.github.bjspi.smsrelayer.testing.FakeRelayQueueRepository
import io.github.bjspi.smsrelayer.testing.FakeSettingsRepository
import io.github.bjspi.smsrelayer.testing.FakeSmsEventRepository
import io.github.bjspi.smsrelayer.testing.FixedClock
import io.github.bjspi.smsrelayer.testing.RecordingEventLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class EnqueueIncomingSmsTest {

    private val clock = FixedClock()
    private val settings = FakeSettingsRepository(AppSettings(onboardingCompleted = true))
    private val smsEvents = FakeSmsEventRepository()
    private val chatTargets = FakeChatTargetRepository()
    private val queue = FakeRelayQueueRepository()
    private val eventLog = RecordingEventLog()

    private val enqueue = EnqueueIncomingSms(smsEvents, chatTargets, queue, settings, eventLog, clock)

    private fun sms() = SmsEvent(
        id = 0L,
        receivedAt = 123L,
        sender = "+49170",
        recipientNumber = null,
        simSlot = null,
        subscriptionId = null,
        deviceName = "Relay",
        body = "hi",
        rawDebugInfo = null,
    )

    @Test
    fun `fans out one pending item per enabled target`() = runTest {
        chatTargets.add("A", "1")
        chatTargets.add("B", "2")
        chatTargets.add("Off", "3", enabled = false)

        val outcome = enqueue(sms())

        assertEquals(2, outcome.enqueuedTargets)
        assertEquals(2, queue.snapshot.size)
        assertTrue(queue.snapshot.all { it.status == QueueStatus.Pending })
        assertTrue(queue.snapshot.all { it.smsEventId == outcome.sms.id })
        assertEquals(123L, settings.state.first().lastSmsReceivedAt)
    }

    @Test
    fun `persists the sms even when no targets exist`() = runTest {
        val outcome = enqueue(sms())

        assertEquals(0, outcome.enqueuedTargets)
        assertTrue(outcome.sms.id > 0L)
        assertTrue(queue.snapshot.isEmpty())
        assertTrue(eventLog.entries.any { it.level == LogLevel.Warning })
    }
}
