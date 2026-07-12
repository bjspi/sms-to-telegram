package io.github.bjspi.smsrelayer.domain.repository

import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget
import kotlinx.coroutines.flow.Flow

/** Persistence for the list of Telegram chat targets. */
interface ChatTargetRepository {

    fun observeAll(): Flow<List<TelegramChatTarget>>

    fun observeEnabled(): Flow<List<TelegramChatTarget>>

    suspend fun getEnabled(): List<TelegramChatTarget>

    suspend fun getById(id: Long): TelegramChatTarget?

    suspend fun getByChatId(chatId: String): TelegramChatTarget?

    suspend fun add(displayName: String, chatId: String, enabled: Boolean = true): TelegramChatTarget

    suspend fun update(target: TelegramChatTarget)

    suspend fun deleteById(id: Long)
}
