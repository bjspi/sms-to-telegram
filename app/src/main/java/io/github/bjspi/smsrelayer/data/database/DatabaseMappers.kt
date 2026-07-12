package io.github.bjspi.smsrelayer.data.database

import io.github.bjspi.smsrelayer.domain.model.LogEntry
import io.github.bjspi.smsrelayer.domain.model.RelayQueueItem
import io.github.bjspi.smsrelayer.domain.model.SmsEvent
import io.github.bjspi.smsrelayer.domain.model.TelegramChatTarget

fun TelegramChatTargetEntity.toDomain(): TelegramChatTarget = TelegramChatTarget(
    id = id,
    displayName = displayName,
    chatId = chatId,
    enabled = enabled,
    createdAt = createdAt,
    lastTestAt = lastTestAt,
    lastTestSuccessful = lastTestSuccessful
)

fun TelegramChatTarget.toEntity(): TelegramChatTargetEntity = TelegramChatTargetEntity(
    id = id,
    displayName = displayName,
    chatId = chatId,
    enabled = enabled,
    createdAt = createdAt,
    lastTestAt = lastTestAt,
    lastTestSuccessful = lastTestSuccessful
)

fun SmsEventEntity.toDomain(): SmsEvent = SmsEvent(
    id = id,
    receivedAt = receivedAt,
    sender = sender,
    recipientNumber = recipientNumber,
    simSlot = simSlot,
    subscriptionId = subscriptionId,
    deviceName = deviceName,
    body = body,
    rawDebugInfo = rawDebugInfo
)

fun SmsEvent.toEntity(): SmsEventEntity = SmsEventEntity(
    id = id,
    receivedAt = receivedAt,
    sender = sender,
    recipientNumber = recipientNumber,
    simSlot = simSlot,
    subscriptionId = subscriptionId,
    deviceName = deviceName,
    body = body,
    rawDebugInfo = rawDebugInfo
)

fun RelayQueueEntity.toDomain(): RelayQueueItem = RelayQueueItem(
    id = id,
    smsEventId = smsEventId,
    targetChatId = targetChatId,
    targetDisplayName = targetDisplayName,
    status = status,
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun RelayQueueItem.toEntity(): RelayQueueEntity = RelayQueueEntity(
    id = id,
    smsEventId = smsEventId,
    targetChatId = targetChatId,
    targetDisplayName = targetDisplayName,
    status = status,
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    lastError = lastError,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun LogEntryEntity.toDomain(): LogEntry = LogEntry(
    id = id,
    timestamp = timestamp,
    level = level,
    category = category,
    message = message,
    details = details
)

fun LogEntry.toEntity(): LogEntryEntity = LogEntryEntity(
    id = id,
    timestamp = timestamp,
    level = level,
    category = category,
    message = message,
    details = details
)
