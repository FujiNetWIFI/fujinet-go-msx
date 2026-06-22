package online.fujinet.go.msx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// UI accent: the FS-A1 keycap-legend amber-orange (#F2871E, matching the
// on-screen keyboard), over dark surfaces.
private val FujiOrange = Color(0xFFF2871E)
private val FujiDark = Color(0xFF080814)
private val FujiPanel = Color(0xFF141428)

private val DarkColors = darkColorScheme(
    primary = FujiOrange,
    onPrimary = Color.White,
    background = FujiDark,
    surface = FujiPanel,
    onSurface = Color(0xFFE6E8F2),
)

private val LightColors = lightColorScheme(
    primary = FujiOrange,
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
