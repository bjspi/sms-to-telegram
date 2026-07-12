package io.github.bjspi.smsrelayer.data.repository

import io.github.bjspi.smsrelayer.data.database.SmsEventDao
import io.github.bjspi.smsrelayer.data.database.SmsEventEntity
import io.github.bjspi.smsrelayer.data.database.toDomain
import io.github.bjspi.smsrelayer.data.database.toEntity
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.domain.repository.SmsEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSmsEventRepository(
    private val dao: SmsEventDao,
) : SmsEventRepository {

    override fun observeLatest(limit: Int): Flow<List<SmsEvent>> =
        dao.observeLatest(limit).map { entities -> entities.map(SmsEventEntity::toDomain) }

    override suspend fun save(event: SmsEvent): SmsEvent {
        val id = dao.insert(event.copy(id = 0L).toEntity())
        return event.copy(id = id)
    }

    override suspend fun getById(id: Long): SmsEvent? = dao.getById(id)?.toDomain()

    override suspend fun getLatest(): SmsEvent? = dao.getLatest()?.toDomain()
}
