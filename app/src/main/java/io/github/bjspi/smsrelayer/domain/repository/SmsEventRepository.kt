package io.github.bjspi.smsrelayer.domain.repository

import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import kotlinx.coroutines.flow.Flow

/** Persistence for captured SMS events. */
interface SmsEventRepository {

    fun observeLatest(limit: Int): Flow<List<SmsEvent>>

    /** Persists the event and returns it with its generated id. */
    suspend fun save(event: SmsEvent): SmsEvent

    suspend fun getById(id: Long): SmsEvent?

    suspend fun getLatest(): SmsEvent?
}
