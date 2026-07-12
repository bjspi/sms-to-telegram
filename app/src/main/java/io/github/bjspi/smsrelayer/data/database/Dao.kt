package io.github.bjspi.smsrelayer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TelegramTargetDao {

    @Query("SELECT * FROM telegram_targets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TelegramChatTargetEntity>>

    @Query("SELECT * FROM telegram_targets WHERE enabled = 1 ORDER BY createdAt DESC")
    fun observeEnabled(): Flow<List<TelegramChatTargetEntity>>

    @Query("SELECT * FROM telegram_targets WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabled(): List<TelegramChatTargetEntity>

    @Query("SELECT * FROM telegram_targets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TelegramChatTargetEntity?

    @Query("SELECT * FROM telegram_targets WHERE chatId = :chatId LIMIT 1")
    suspend fun getByChatId(chatId: String): TelegramChatTargetEntity?

    @Insert
    suspend fun insert(entity: TelegramChatTargetEntity): Long

    @Update
    suspend fun update(entity: TelegramChatTargetEntity): Int

    @Query("DELETE FROM telegram_targets WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

@Dao
interface SmsEventDao {

    @Insert
    suspend fun insert(entity: SmsEventEntity): Long

    @Query("SELECT * FROM sms_events WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmsEventEntity?

    @Query("SELECT * FROM sms_events ORDER BY receivedAt DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<SmsEventEntity>>

    @Query("SELECT * FROM sms_events ORDER BY receivedAt DESC LIMIT 1")
    suspend fun getLatest(): SmsEventEntity?
}

@Dao
interface RelayQueueDao {

    @Insert
    suspend fun insertAll(items: List<RelayQueueEntity>): LongArray

    @Update
    suspend fun update(entity: RelayQueueEntity): Int

    @Query(
        """
        SELECT * FROM relay_queue
        WHERE status IN ('Pending', 'FailedRetryable')
          AND nextAttemptAt <= :now
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getDue(now: Long, limit: Int): List<RelayQueueEntity>

    @Query("UPDATE relay_queue SET status = 'Sending', updatedAt = :now WHERE id IN (:ids)")
    suspend fun markSending(ids: List<Long>, now: Long)

    /**
     * Selects due items and flips them to `Sending` in one transaction, so two
     * concurrent queue processors can never claim the same item.
     */
    @Transaction
    suspend fun claimDue(now: Long, limit: Int): List<RelayQueueEntity> {
        val due = getDue(now, limit)
        if (due.isEmpty()) return emptyList()
        markSending(due.map(RelayQueueEntity::id), now)
        return due
    }

    @Query(
        """
        UPDATE relay_queue
        SET status = 'Pending', updatedAt = :now
        WHERE status = 'Sending' AND updatedAt < :cutoff
        """,
    )
    suspend fun recoverStaleSending(cutoff: Long, now: Long): Int

    @Query(
        """
        SELECT
            COUNT(CASE WHEN status = 'Pending' THEN 1 END) AS pending,
            COUNT(CASE WHEN status = 'FailedRetryable' THEN 1 END) AS retryable,
            COUNT(CASE WHEN status = 'Sent' THEN 1 END) AS sent
        FROM relay_queue
        """,
    )
    fun observeCounts(): Flow<QueueCountsRow>

    @Query(
        """
        SELECT
            COUNT(CASE WHEN status = 'Pending' THEN 1 END) AS pending,
            COUNT(CASE WHEN status = 'FailedRetryable' THEN 1 END) AS retryable,
            COUNT(CASE WHEN status = 'Sent' THEN 1 END) AS sent
        FROM relay_queue
        """,
    )
    suspend fun getCounts(): QueueCountsRow
}

/** Projection for the aggregated queue counters (single table scan). */
data class QueueCountsRow(
    val pending: Int,
    val retryable: Int,
    val sent: Int,
)

@Dao
interface LogDao {

    @Insert
    suspend fun insert(entity: LogEntryEntity): Long

    @Query("SELECT * FROM logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<LogEntryEntity>>

    @Query(
        """
        SELECT * FROM logs
        WHERE message LIKE '%' || :query || '%' ESCAPE '\'
           OR COALESCE(details, '') LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<LogEntryEntity>

    @Query("DELETE FROM logs")
    suspend fun deleteAll(): Int

    @Query(
        """
        DELETE FROM logs
        WHERE id NOT IN (
            SELECT id FROM logs
            ORDER BY timestamp DESC, id DESC
            LIMIT :maxRows
        )
        """,
    )
    suspend fun trimToLatest(maxRows: Int): Int
}
