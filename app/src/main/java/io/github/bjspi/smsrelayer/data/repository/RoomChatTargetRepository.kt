package io.github.bjspi.smsrelayer.data.repository

import io.github.bjspi.smsrelayer.data.database.TelegramChatTargetEntity
import io.github.bjspi.smsrelayer.data.database.TelegramTargetDao
import io.github.bjspi.smsrelayer.data.database.toDomain
import io.github.bjspi.smsrelayer.data.database.toEntity
import io.github.bjspi.smsrelayer.domain.Clock
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import io.github.bjspi.smsrelayer.domain.repository.ChatTargetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChatTargetRepository(
    private val dao: TelegramTargetDao,
    private val clock: Clock,
) : ChatTargetRepository {

    override fun observeAll(): Flow<List<TelegramChatTarget>> =
        dao.observeAll().map { entities -> entities.map(TelegramChatTargetEntity::toDomain) }

    override fun observeEnabled(): Flow<List<TelegramChatTarget>> =
        dao.observeEnabled().map { entities -> entities.map(TelegramChatTargetEntity::toDomain) }

    override suspend fun getEnabled(): List<TelegramChatTarget> =
        dao.getEnabled().map(TelegramChatTargetEntity::toDomain)

    override suspend fun getById(id: Long): TelegramChatTarget? =
        dao.getById(id)?.toDomain()

    override suspend fun getByChatId(chatId: String): TelegramChatTarget? =
        dao.getByChatId(chatId)?.toDomain()

    override suspend fun add(displayName: String, chatId: String, enabled: Boolean): TelegramChatTarget {
        val entity = TelegramChatTargetEntity(
            id = 0L,
            displayName = displayName,
            chatId = chatId,
            enabled = enabled,
            createdAt = clock.now(),
            lastTestAt = null,
            lastTestSuccessful = null,
        )
        return entity.copy(id = dao.insert(entity)).toDomain()
    }

    override suspend fun update(target: TelegramChatTarget) {
        dao.update(target.toEntity())
    }

    override suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}
