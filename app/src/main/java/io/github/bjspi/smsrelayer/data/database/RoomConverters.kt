package io.github.bjspi.smsrelayer.data.database

import androidx.room.TypeConverter
import io.github.bjspi.smsrelayer.domain.model.LogCategory
import io.github.bjspi.smsrelayer.domain.model.LogLevel
import io.github.bjspi.smsrelayer.domain.model.QueueStatus

class RoomConverters {

    @TypeConverter
    fun fromQueueStatus(value: QueueStatus): String = value.name

    @TypeConverter
    fun toQueueStatus(value: String): QueueStatus = QueueStatus.valueOf(value)

    @TypeConverter
    fun fromLogLevel(value: LogLevel): String = value.name

    @TypeConverter
    fun toLogLevel(value: String): LogLevel = LogLevel.valueOf(value)

    @TypeConverter
    fun fromLogCategory(value: LogCategory): String = value.name

    @TypeConverter
    fun toLogCategory(value: String): LogCategory = LogCategory.valueOf(value)
}
