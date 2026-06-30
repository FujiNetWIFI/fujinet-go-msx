package online.fujinet.go.msx.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import online.fujinet.go.msx.R
import online.fujinet.go.msx.SessionController

/**
 * The main app screen: the MSX video surface, a thin control bar (toggle
 * keyboard / joystick, open the FujiNet web UI, settings, shut down), and
 * the on-screen keyboard. Mirrors the other FujiNet Go targets' EmulatorScreen,
 * MSX-ised.
 */
@Composable
fun EmulatorScreen(
    session: SessionController,
    onOpenFujiNet: () -> Unit,
    onOpenSettings: () -> Unit,
    onShutdown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The keyboard and joystick are mutually exclusive: at most one input overlay
    // is shown so the emulator surface keeps as much room as possible.
    var overlay by remember { mutableStateOf(Overlay.KEYBOARD) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(modifier = modifier.fillMaxSize()) {
        ControlBar(
            keyboardActive = overlay == Overlay.KEYBOARD,
            joystickActive = overlay == Overlay.JOYSTICK,
            onToggleKeyboard = {
                overlay = if (overlay == Overlay.KEYBOARD) Overlay.NONE else Overlay.KEYBOARD
            },
            onToggleJoystick = {
                overlay = if (overlay == Overlay.JOYSTICK) Overlay.NONE else Overlay.JOYSTICK
            },
            onOpenFujiNet = onOpenFujiNet,
            onOpenSettings = onOpenSettings,
            onShutdown = onShutdown,
        )

        if (landscape && overlay == Overlay.JOYSTICK) {
            // Landscape: flank the screen with the stick (left) and fire buttons
            // (right) so the surface fills the full height between them.
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                JoystickPad(
                    onAxis = { x, y -> session.joyStick(x, y) },
                    modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 12.dp),
                )
                EmulatorSurface(
                    session = session,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                FireButtons(
                    session,
                    modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 12.dp),
                )
            }
        } else {
            // Portrait (and keyboard): the surface fills the area above a stacked
            // bottom overlay.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                EmulatorSurface(session = session, modifier = Modifier.fillMaxSize())
            }
            when (overlay) {
                Overlay.KEYBOARD -> MsxKeyboard(session = session)
                Overlay.JOYSTICK -> JoystickView(session = session)
                Overlay.NONE -> {}
            }
        }
    }
}

private enum class Overlay { NONE, KEYBOARD, JOYSTICK }

@Composable
private fun ControlBar(
    keyboardActive: Boolean,
    joystickActive: Boolean,
    onToggleKeyboard: () -> Unit,
    onToggleJoystick: () -> Unit,
    onOpenFujiNet: () -> Unit,
    onOpenSettings: () -> Unit,
    onShutdown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BarButton(Icons.Filled.Keyboard, "Keyboard", Modifier.weight(1f), keyboardActive, onToggleKeyboard)
        BarButton(Icons.Filled.Gamepad, "Joystick", Modifier.weight(1f), joystickActive, onToggleJoystick)
        FujiNetBarButton(Modifier.weight(1f), onClick = onOpenFujiNet)
        BarButton(Icons.Filled.Settings, "Settings", Modifier.weight(1f), onClick = onOpenSettings)
        BarButton(Icons.Filled.PowerSettingsNew, "Power off", Modifier.weight(1f), onClick = onShutdown)
    }
}

/**
 * The FujiNet web-UI button: the FujiNet "dot" logo, with its white tile tinted
 * to the UI accent (Modulate keeps the black centre dot black and the corners
 * transparent, recolouring only the white).
 */
@Composable
private fun FujiNetBarButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.fujinet_toolbar),
            contentDescription = "FujiNet web UI",
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary, BlendMode.Modulate),
        )
    }
}

@Composable
private fun BarButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        )
    }
}
