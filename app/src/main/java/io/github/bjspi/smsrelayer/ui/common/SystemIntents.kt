package io.github.bjspi.smsrelayer.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens the system dialog to exempt the app from battery optimizations, with a
 * fallback to the generic list screen for OEMs that block the direct request.
 */
fun openBatteryOptimizationSettings(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(direct) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
}
