package io.github.bjspi.smsrelayer.ui.common

import android.content.Context
import android.text.format.DateUtils
import io.github.bjspi.smsrelayer.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ABSOLUTE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** "3 min. ago"-style relative timestamp, or a localized "never". */
fun formatRelativeTime(context: Context, timestamp: Long?): String {
    if (timestamp == null) return context.getString(R.string.time_never)
    return DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

fun formatAbsoluteTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(ABSOLUTE_FORMAT)
