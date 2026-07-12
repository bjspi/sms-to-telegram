package io.github.bjspi.smsrelayer.domain.repository

import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.LogEntry
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import kotlinx.coroutines.flow.Flow

/**
 * Structured, persistent diagnostics log.
 *
 * [log] is contractually infallible: implementations swallow storage errors so
 * that diagnostics can never break the operation being diagnosed. Callers may
 * therefore log freely without wrapping every call in error handling.
 */
interface EventLog {

    suspend fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        details: String? = null,
    )

    fun observeLatest(limit: Int): Flow<List<LogEntry>>

    suspend fun search(query: String, limit: Int): List<LogEntry>

    suspend fun clear()

    suspend fun debug(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.Debug, category, message, details)

    suspend fun info(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.Info, category, message, details)

    suspend fun warn(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.Warning, category, message, details)

    suspend fun error(category: LogCategory, message: String, details: String? = null) =
        log(LogLevel.Error, category, message, details)
}
