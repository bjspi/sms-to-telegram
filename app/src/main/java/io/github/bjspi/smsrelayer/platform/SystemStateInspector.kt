package io.github.bjspi.smsrelayer.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * Read-only access to platform state (permissions, battery exemption, network,
 * notification channel). Centralizing these checks keeps onboarding, the
 * diagnostics screen and the watchdog consistent with each other.
 */
class SystemStateInspector(private val context: Context) {

    fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun isInternetAvailable(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun notificationChannelExists(channelId: String): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channelId) != null

    /** Runtime permissions the relay needs, in the order they are requested. */
    fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun allRuntimePermissionsGranted(): Boolean =
        requiredRuntimePermissions().all(::hasPermission)
}
