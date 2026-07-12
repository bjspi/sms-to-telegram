package io.github.bjspi.smsrelayer.domain

/**
 * Source of wall-clock time, injected instead of calling [System.currentTimeMillis]
 * directly so that time-dependent logic (retry backoff, staleness checks, log
 * timestamps) stays deterministic in unit tests.
 */
fun interface Clock {

    fun now(): Long

    companion object {
        /** Production clock backed by the system wall clock. */
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
