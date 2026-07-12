package io.github.bjspi.smsrelayer.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TelegramChatTargetEntity::class,
        SmsEventEntity::class,
        RelayQueueEntity::class,
        LogEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class SmsRelayerDatabase : RoomDatabase() {

    abstract fun telegramTargetDao(): TelegramTargetDao

    abstract fun smsEventDao(): SmsEventDao

    abstract fun relayQueueDao(): RelayQueueDao

    abstract fun logDao(): LogDao

    companion object {

        private const val DATABASE_NAME = "sms_relayer.db"

        /**
         * Builds the database. Instance ownership lives in the app container —
         * this factory intentionally does not cache anything.
         */
        fun build(context: Context): SmsRelayerDatabase =
            Room.databaseBuilder(context.applicationContext, SmsRelayerDatabase::class.java, DATABASE_NAME)
                .build()
    }
}
