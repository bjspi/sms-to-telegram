package io.github.bjspi.smsrelayer.data.repository

import io.github.bjspi.smsrelayer.data.database.RelayQueueDao
import io.github.bjspi.smsrelayer.data.database.toDomain
import io.github.bjspi.smsrelayer.data.database.toEntity
import io.github.bjspi.smsrelayer.domain.model.QueueCounts
import io.github.bjspi.smsrelayer.domain.model.QueueStatus
import io.github.bjspi.smsrelayer.domain.model.RelayQueueItem
import io.github.bjspi.smsrelayer.domain.repository.RelayQueueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomRelayQueueRepository(
    private val dao: RelayQueueDao,
) : RelayQueueRepository {

    override suspend fun enqueue(items: List<RelayQueueItem>): List<RelayQueueItem> {
        if (items.isEmpty()) return emptyList()
        val ids = dao.insertAll(items.map { it.copy(id = 0L).toEntity() })
        return items.mapIndexed { index, item -> item.copy(id = ids[index]) }
    }

    override suspend fun claimDue(now: Long, limit: Int): List<RelayQueueItem> =
        dao.claimDue(now, limit).map { entity ->
            entity.toDomain().copy(status = QueueStatus.Sending, updatedAt = now)
        }

    override suspend fun recoverStaleSending(cutoff: Long, now: Long): Int =
        dao.recoverStaleSending(cutoff, now)

    override suspend fun update(item: RelayQueueItem) {
        dao.update(item.toEntity())
    }

    override fun observeCounts(): Flow<QueueCounts> =
        dao.observeCounts()
            .map { row -> QueueCounts(pending = row.pending, retryable = row.retryable, sent = row.sent) }
            .distinctUntilChanged()

    override suspend fun getCounts(): QueueCounts =
        dao.getCounts().let { QueueCounts(pending = it.pending, retryable = it.retryable, sent = it.sent) }
}
