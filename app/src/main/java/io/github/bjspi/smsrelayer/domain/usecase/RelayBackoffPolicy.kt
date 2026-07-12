package io.github.bjspi.smsrelayer.domain.usecase

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Escalating retry schedule for failed Telegram deliveries.
 *
 * SMS are considered important, so the queue never gives up on transient
 * failures — after the ramp-up it keeps retrying every 30 minutes until the
 * item either goes through or fails permanently (e.g. invalid chat ID).
 */
object RelayBackoffPolicy {

    /** Delay before the next attempt, given the number of attempts already made. */
    fun delayAfter(attemptCount: Int): Duration = when {
        attemptCount <= 1 -> 1.minutes
        attemptCount == 2 -> 5.minutes
        attemptCount == 3 -> 15.minutes
        else -> 30.minutes
    }
}
