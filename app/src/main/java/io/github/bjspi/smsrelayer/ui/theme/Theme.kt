package io.github.bjspi.smsrelayer.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    secondary = Teal,
    onSecondary = Color.White,
    tertiary = BrightBlue,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    error = ErrorRed,
)

/**
 * Light-only by design: the app targets dedicated relay devices whose screen
 * is rarely looked at, and a single, high-contrast theme keeps every status
 * color unambiguous.
 */
@Composable
fun SmsRelayerTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
