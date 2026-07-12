package io.github.bjspi.smsrelayer.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class RelayBackoffPolicyTest {

    @Test
    fun `escalates through the documented schedule`() {
        assertEquals(1.minutes, RelayBackoffPolicy.delayAfter(1))
        assertEquals(5.minutes, RelayBackoffPolicy.delayAfter(2))
        assertEquals(15.minutes, RelayBackoffPolicy.delayAfter(3))
        assertEquals(30.minutes, RelayBackoffPolicy.delayAfter(4))
    }

    @Test
    fun `caps at thirty minutes forever`() {
        assertEquals(30.minutes, RelayBackoffPolicy.delayAfter(50))
        assertEquals(30.minutes, RelayBackoffPolicy.delayAfter(Int.MAX_VALUE))
    }

    @Test
    fun `tolerates out-of-range attempt counts`() {
        assertEquals(1.minutes, RelayBackoffPolicy.delayAfter(0))
        assertEquals(1.minutes, RelayBackoffPolicy.delayAfter(-3))
    }
}
