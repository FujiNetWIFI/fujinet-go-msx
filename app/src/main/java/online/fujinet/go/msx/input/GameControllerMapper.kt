package online.fujinet.go.msx.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Maps a Bluetooth/USB game controller to the MSX general-purpose joystick (a
 * digital 8-way stick with two fire buttons).
 *
 * The left analog stick and the d-pad (reported either as AXIS_HAT_X/Y motion or
 * KEYCODE_DPAD_* keys) both produce the four direction bits via a threshold; the
 * two primary face buttons map to the MSX fire buttons: A/X -> trigger A
 * (primary fire), B/Y -> trigger B. Direction state is pushed via [onDirection]
 * and buttons via [onButton] (an Msx.JOY_TRIG_* id); each handler returns true
 * when it consumed the event.
 */
class GameControllerMapper(
    private val deadzone: Float = DEFAULT_DEADZONE,
    private val onDirection: (up: Boolean, down: Boolean, left: Boolean, right: Boolean) -> Unit,
    private val onButton: (id: Int, pressed: Boolean) -> Unit,
) {
    private var stickX = 0f
    private var stickY = 0f
    private var hatX = 0f      // d-pad as AXIS_HAT (motion), -1/0/1
    private var hatY = 0f
    private var dpadUp = false // d-pad as key events
    private var dpadDown = false
    private var dpadLeft = false
    private var dpadRight = false

    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromController() || event.action != MotionEvent.ACTION_MOVE) return false
        stickX = scale(event.getAxisValue(MotionEvent.AXIS_X))
        stickY = scale(event.getAxisValue(MotionEvent.AXIS_Y))
        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        pushDirection()
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!event.isFromController()) return false
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dpadUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadDown = pressed
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadRight = pressed
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X -> {
                onButton(Msx.JOY_TRIG_A, pressed); return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y -> {
                onButton(Msx.JOY_TRIG_B, pressed); return true
            }
            else -> return false
        }
        pushDirection()
        return true
    }

    /** Recenter the stick (e.g. when a controller disconnects). */
    fun reset() {
        stickX = 0f; stickY = 0f; hatX = 0f; hatY = 0f
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        onDirection(false, false, false, false)
    }

    private fun pushDirection() {
        val x = if (stickX != 0f) stickX else hatX
        val y = if (stickY != 0f) stickY else hatY
        onDirection(
            dpadUp || y <= -THRESHOLD,
            dpadDown || y >= THRESHOLD,
            dpadLeft || x <= -THRESHOLD,
            dpadRight || x >= THRESHOLD,
        )
    }

    /** Deadzone with rescale so motion past the deadzone ramps smoothly from 0. */
    private fun scale(value: Float): Float {
        val v = value.coerceIn(-1f, 1f)
        if (abs(v) < deadzone) return 0f
        return v
    }

    private fun MotionEvent.isFromController(): Boolean =
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD

    private fun KeyEvent.isFromController(): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private companion object {
        const val DEFAULT_DEADZONE = 0.18f
        const val THRESHOLD = 0.4f
    }
}
