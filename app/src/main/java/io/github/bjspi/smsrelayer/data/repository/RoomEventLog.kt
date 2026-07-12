package io.github.bjspi.smsrelayer.data.repository

import io.github.bjspi.smsrelayer.data.database.LogDao
import io.github.bjspi.smsrelayer.data.database.LogEntryEntity
import io.github.bjspi.smsrelayer.data.database.toDomain
import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.LogEntry
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.bjspi.smsrelayer.domain.repository.EventLog

class RoomEventLog(
    private val dao: LogDao,
    private val clock: Clock,
    private val maxRows: Int = MAX_ROWS,
) : EventLog {

    private val insertsSinceTrim = AtomicInteger(0)

    override suspend fun log(level: LogLevel, category: LogCategory, message: String, details: String?) {
        try {
            dao.insert(
                LogEntryEntity(
                    id = 0L,
                    timestamp = clock.now(),
                    level = level,
                    category = category,
                    message = message,
                    details = details,
                ),
            )
            // Trimming on every insert would turn each log write into a table
            // scan; amortizing it keeps writes cheap while bounding growth.
            if (insertsSinceTrim.incrementAndGet() >= TRIM_EVERY) {
                insertsSinceTrim.set(0)
                dao.trimToLatest(maxRows)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Contract: logging must never fail the operation being logged.
        }
    }

    override fun observeLatest(limit: Int): Flow<List<LogEntry>> =
        dao.observeLatest(limit).map { entities -> entities.map(LogEntryEntity::toDomain) }

    override suspend fun search(query: String, limit: Int): List<LogEntry> =
        dao.search(escapeLikePattern(query), limit).map(LogEntryEntity::toDomain)

    override suspend fun clear() {
        dao.deleteAll()
    }

    /** Escapes LIKE wildcards so user searches match literally. */
    private fun escapeLikePattern(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val MAX_ROWS = 10_000
        const val TRIM_EVERY = 64
    }
}
