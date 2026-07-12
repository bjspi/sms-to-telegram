package io.github.bjspi.smsrelayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.bjspi.smsrelayer.R
import io.github.bjspi.smsrelayer.app.MainActivity

/**
 * Single owner of notification channels and notification layouts, so the
 * foreground service and the expedited delivery worker present a consistent
 * surface and never race on channel creation.
 */
object RelayNotifications {

    const val SERVICE_CHANNEL_ID = "sms_relayer_service"
    const val DELIVERY_CHANNEL_ID = "sms_relayer_delivery"
    const val SERVICE_NOTIFICATION_ID = 1001
    const val DELIVERY_NOTIFICATION_ID = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_service_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                DELIVERY_CHANNEL_ID,
                context.getString(R.string.channel_delivery_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_delivery_description)
                setShowBadge(false)
            },
        )
    }

    fun serviceNotification(
        context: Context,
        deviceName: String,
        enabledTargets: Int,
        queueDue: Int,
    ): Notification {
        val text = context.getString(
            R.string.notification_service_text,
            deviceName,
            enabledTargets,
            queueDue,
        )
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(R.string.notification_service_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openAppIntent(context))
            .addAction(
                0,
                context.getString(R.string.notification_action_send_queue),
                drainQueueIntent(context),
            )
            .build()
    }

    /** Shown when expedited delivery work runs as a foreground service (API < 31). */
    fun deliveryNotification(context: Context): Notification =
        NotificationCompat.Builder(context, DELIVERY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(context.getString(R.string.notification_delivery_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun drainQueueIntent(context: Context): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            1,
            Intent(context, RelayForegroundService::class.java)
                .setAction(RelayForegroundService.ACTION_DRAIN_QUEUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
