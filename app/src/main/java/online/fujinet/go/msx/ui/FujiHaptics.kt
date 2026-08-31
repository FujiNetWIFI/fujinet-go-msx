package online.fujinet.go.msx.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Short tactile pulses for the on-screen controls, shared with the rest of
 * the Go family (mirrors FujiNet Go 800's FujiHaptics). A crisp one-shot via
 * the system [Vibrator] when one exists, otherwise the platform
 * [HapticFeedbackConstants] fallback (which honours the user's system
 * touch-feedback setting).
 */
internal enum class FujiHapticPattern(
    val durationMillis: Long,
    val amplitude: Int,
) {
    KeyPress(durationMillis = 24L, amplitude = 220),
    JoystickTick(durationMillis = 18L, amplitude = 200),

    /** The app's own controls -- toolbar, dialogs, the ROM gate. Lighter than
     * [KeyPress]: pressing a key on the emulated machine is the point of the
     * app, while tapping Settings is incidental, and the two should not feel
     * equally weighty. */
    UiTap(durationMillis = 16L, amplitude = 180),
}

@Composable
internal fun rememberFujiHaptic(pattern: FujiHapticPattern): () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val vibrator = remember(context) { context.resolveVibrator() }
    val currentVibrator = rememberUpdatedState(vibrator)
    val currentView = rememberUpdatedState(view)

    return remember(pattern) {
        {
            val resolvedVibrator = currentVibrator.value
            if (resolvedVibrator?.hasVibrator() == true) {
                resolvedVibrator.vibrate(
                    VibrationEffect.createOneShot(pattern.durationMillis, pattern.amplitude),
                )
            } else {
                currentView.value.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
}

private fun Context.resolveVibrator(): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }
}

/**
 * The chrome pulse, already gated on the user's Interface-haptics preference,
 * so call sites are a bare `blip()` with no `if (enabled)` of their own.
 *
 * Defaults to a no-op: a composable previewed or rendered outside a
 * [ProvideUiHaptics] is silent rather than crashing.
 */
internal val LocalUiHaptic = staticCompositionLocalOf<() -> Unit> { {} }

/** Whether chrome haptics are on, for the rare control that wants a pulse of a
 * different weight than [FujiHapticPattern.UiTap] but must still obey the
 * Interface-haptics preference. */
internal val LocalUiHapticsEnabled = staticCompositionLocalOf { false }

/** Installs [LocalUiHaptic] for a whole Compose tree. Dialog sub-compositions
 * inherit it, so an AlertDialog hosted inside the content is covered too. */
@Composable
internal fun ProvideUiHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    val emit = rememberFujiHaptic(FujiHapticPattern.UiTap)
    val gated = remember(enabled, emit) { { if (enabled) emit() } }
    CompositionLocalProvider(
        LocalUiHaptic provides gated,
        LocalUiHapticsEnabled provides enabled,
        content = content,
    )
}
