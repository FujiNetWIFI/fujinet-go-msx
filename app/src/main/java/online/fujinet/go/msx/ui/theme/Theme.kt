package online.fujinet.go.msx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Accent derived from the FujiNet Go MSX launcher icon's blue background
// (#0000FF -- the classic MSX blue), with dark blue-tinted surfaces to match.
private val FujiBlue = Color(0xFF0000FF)
private val FujiDark = Color(0xFF080814)
private val FujiPanel = Color(0xFF141428)

private val DarkColors = darkColorScheme(
    primary = FujiBlue,
    onPrimary = Color.White,
    background = FujiDark,
    surface = FujiPanel,
    onSurface = Color(0xFFE6E8F2),
)

private val LightColors = lightColorScheme(
    primary = FujiBlue,
    onPrimary = Color.White,
)

@Composable
fun FujiNetGoMsxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
