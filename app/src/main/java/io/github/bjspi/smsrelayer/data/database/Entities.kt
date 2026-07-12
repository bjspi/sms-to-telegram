package io.github.bjspi.smsrelayer.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import io.github.bjspi.smsrelayer.domain.model.QueueStatus

@Entity(
    tableName = "telegram_targets",
    indices = [
        Index(value = ["chatId"])
    ]
)
data class TelegramChatTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val displayName: String,
    val chatId: String,
    val enabled: Boolean,
    val createdAt: Long,
    val lastTestAt: Long?,
    val lastTestSuccessful: Boolean?
)

@Entity(tableName = "sms_events")
data class SmsEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val receivedAt: Long,
    val sender: String,
    val recipientNumber: String?,
    val simSlot: Int?,
    val subscriptionId: Int?,
    val deviceName: String,
    val body: String,
    val rawDebugInfo: String?
)

@Entity(
    tableName = "relay_queue",
    indices = [
        Index(value = ["status", "nextAttemptAt"])
    ]
)
data class RelayQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val smsEventId: Long,
    val targetChatId: String,
    val targetDisplayName: String,
    val status: QueueStatus,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["timestamp"])
    ]
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val details: String?
)
