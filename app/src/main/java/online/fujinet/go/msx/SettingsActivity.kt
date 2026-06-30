package online.fujinet.go.msx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import online.fujinet.go.msx.ui.SettingsScreen
import online.fujinet.go.msx.ui.theme.FujiNetGoMsxTheme

/**
 * Hosts the MSX configuration UI (system type selection) as a centered dialog
 * over a translucent window, so it floats over the running emulator. "Apply &
 * Restart" persists the selected system type and reboots the emulator so openMSX
 * comes up on the chosen machine; dismissing closes the activity.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FujiNetGoMsxTheme {
                SettingsScreen(
                    onApplyRestart = {
                        SessionController.get(applicationContext).restart()
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }
}
