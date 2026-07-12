package io.github.bjspi.smsrelayer.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import io.github.bjspi.smsrelayer.ui.AppRoot
import io.github.bjspi.smsrelayer.ui.theme.SmsRelayerTheme

/**
 * AppCompatActivity (not plain ComponentActivity) on purpose: it is what makes
 * the per-app language preference work on API < 33.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsRelayerTheme {
                AppRoot()
            }
        }
    }
}
