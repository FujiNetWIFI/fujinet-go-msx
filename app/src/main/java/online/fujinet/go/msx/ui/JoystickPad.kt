package online.fujinet.go.msx.ui

import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import online.fujinet.go.msx.SessionController
import online.fujinet.go.msx.input.Msx
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The MSX joystick view: a thumb-stick plus the two fire buttons (trigger A /
 * trigger B), laid out like the controllers in FujiNet Go Adam and 800. The MSX
 * general-purpose joystick is digital, so the stick position is thresholded into
 * the four direction bits (SessionController.joyStick) for port 1.
 */
@Composable
fun JoystickView(session: SessionController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JoystickPad(onAxis = { x, y -> session.joyStick(x, y) })
        FireButtons(session)
    }
}

/**
 * Analog thumb-stick. Drag within the pad; the position is reported as
 * [onAxis] (x, y) in -1..1 with a centre deadzone, and releasing recenters.
 * A single owning pointer so a second finger on a fire button can't steal it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun JoystickPad(onAxis: (Float, Float) -> Unit, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var nub by remember { mutableStateOf(PointF(0f, 0f)) }
    var pointerId by remember { mutableStateOf<Int?>(null) }

    fun reset() {
        pointerId = null
        nub = PointF(0f, 0f)
        onAxis(0f, 0f)
    }

    fun apply(px: Float, py: Float) {
        val ax = axis(px, padSize.width)
        val ay = axis(py, padSize.height)
        nub = PointF(ax, ay)
        onAxis(ax, ay)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
            .onSizeChanged { padSize = it }
            .pointerInteropFilter { e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (pointerId == null) {
                            pointerId = e.getPointerId(e.actionIndex)
                            apply(e.getX(e.actionIndex), e.getY(e.actionIndex))
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val pid = pointerId ?: return@pointerInteropFilter false
                        val idx = e.findPointerIndex(pid)
                        if (idx < 0) {
                            reset()
                            return@pointerInteropFilter true
                        }
                        apply(e.getX(idx), e.getY(idx))
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (e.getPointerId(e.actionIndex) == pointerId) reset()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        reset()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize(0.3f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        )
        val travel = min(padSize.width, padSize.height) * 0.2f
        Box(
            Modifier
                .offset { IntOffset((nub.x * travel).roundToInt(), (nub.y * travel).roundToInt()) }
                .fillMaxSize(0.24f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
        )
    }
}

/** The two MSX joystick fire buttons: trigger A (primary) and trigger B. */
@Composable
fun FireButtons(session: SessionController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FireButton("●", "A") { down -> session.joyButton(Msx.JOY_TRIG_A, down) }
        FireButton("○", "B") { down -> session.joyButton(Msx.JOY_TRIG_B, down) }
    }
}

@Composable
private fun FireButton(symbol: String, caption: String, size: Dp = 64.dp, onHold: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onHold(true)
                        try {
                            awaitRelease()
                        } finally {
                            onHold(false)
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Text(caption, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
    }
}

private const val DEADZONE = 0.12f

/** Normalize a touch coordinate within [extent] to -1..1 with a centre deadzone. */
private fun axis(value: Float, extent: Int): Float {
    if (extent == 0) return 0f
    val half = extent / 2f
    val n = ((value - half) / half).coerceIn(-1f, 1f)
    return if (abs(n) < DEADZONE) 0f else n
}
