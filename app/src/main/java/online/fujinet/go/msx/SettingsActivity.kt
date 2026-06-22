package online.fujinet.go.msx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import online.fujinet.go.msx.ui.SettingsScreen
import online.fujinet.go.msx.ui.theme.FujiNetGoMsxTheme

/**
 * Hosts the MSX configuration UI (system type / machine profiles / system-ROM
 * import). "Apply & Restart" persists the active profile and reboots the emulator
 * so openMSX comes up on the chosen machine.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FujiNetGoMsxTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
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
}
