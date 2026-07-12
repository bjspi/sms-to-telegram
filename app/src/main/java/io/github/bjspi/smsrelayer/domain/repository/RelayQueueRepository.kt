package io.github.bjspi.smsrelayer.domain.repository

import io.github.bjspi.smsrelayer.domain.model.QueueCounts
import io.github.bjspi.smsrelayer.domain.model.RelayQueueItem
import kotlinx.coroutines.flow.Flow

/** Persistence and claim semantics for the relay delivery queue. */
interface RelayQueueRepository {

    suspend fun enqueue(items: List<RelayQueueItem>): List<RelayQueueItem>

    /**
     * Atomically claims up to [limit] due items (Pending or FailedRetryable with
     * `nextAttemptAt <= now`) by flipping them to `Sending` inside a single
     * transaction. Concurrent processors can therefore never pick up the same
     * item twice.
     */
    suspend fun claimDue(now: Long, limit: Int): List<RelayQueueItem>

    /**
     * Returns items stuck in `Sending` (e.g. process death mid-delivery) back to
     * `Pending` when they have not been touched since [cutoff]. Returns the
     * number of recovered items.
     */
    suspend fun recoverStaleSending(cutoff: Long, now: Long): Int

    suspend fun update(item: RelayQueueItem)

    fun observeCounts(): Flow<QueueCounts>

    suspend fun getCounts(): QueueCounts
}
