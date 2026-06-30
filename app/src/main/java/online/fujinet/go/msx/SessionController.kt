package online.fujinet.go.msx

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import online.fujinet.go.msx.core.EmulatorNative
import online.fujinet.go.msx.input.Msx
import online.fujinet.go.msx.settings.SystemTypeStore
import kotlin.concurrent.thread

/**
 * Owns the lifetime of one MSX session: stages the FujiNet runtime assets, starts
 * the native emulator (openMSX) and the in-process FujiNet runtime (joined over
 * FujiBusPacket-over-SLIP on loopback TCP 1985, where the FujiNet runtime listens
 * and openMSX's FujiNet device connects in), streams audio, and forwards input.
 *
 * A process-wide singleton so the activity and the foreground service share one
 * running session (a relaunched activity reuses it instead of starting a second
 * emulator), mirroring the other FujiNet Go targets.
 */
class SessionController private constructor(private val context: Context) {

    @Volatile private var started = false
    @Volatile private var sdlReady = false
    @Volatile private var paths: RuntimeInstaller.Paths? = null
    private val audio = AudioOutput()
    private val lock = Any()

    private val prefs = context.getSharedPreferences("fujimsx", Context.MODE_PRIVATE)

    /** Live haptic-feedback toggles (persisted; no session restart). */
    var keyboardHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_HAPTICS, true)
        set(value) { prefs.edit().putBoolean(KEY_KEYBOARD_HAPTICS, value).apply() }

    var joystickHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_JOYSTICK_HAPTICS, true)
        set(value) { prefs.edit().putBoolean(KEY_JOYSTICK_HAPTICS, value).apply() }

    /**
     * openMSX is an SDL app; SDL's Android audio/video drivers need their JNI
     * references set up (normally done by SDLActivity). Touch EmulatorNative first
     * so libmsxcore.so is loaded (its JNI_OnLoad registers SDL's native methods),
     * then run SDL's JNI setup once. Idempotent; safe to call from the UI thread.
     */
    private fun ensureSdl() {
        if (sdlReady) return
        synchronized(lock) {
            if (sdlReady) return
            EmulatorNative.nativeIsRunning()
            try {
                org.libsdl.app.SDL.setupJNI()
                org.libsdl.app.SDL.setContext(context.applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "SDL JNI setup failed", t)
            }
            sdlReady = true
        }
    }

    /** The FujiNet SD directory (where imported media lands), once staged. */
    val sdPath: String? get() = paths?.sdPath

    fun startIfNeeded() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        thread(name = "msx-bootstrap") { launch() }
    }

    private fun launch() {
        try {
            val installer = RuntimeInstaller(context.applicationContext)
            val p = paths ?: installer.install().also { paths = it }
            // Boot the C-BIOS machine the selected system type maps to;
            // session_runtime reads this before starting the core.
            val machineId = SystemTypeStore(context.applicationContext).activeMachineId()
            installer.writeMachineId(machineId)
            ensureSdl()
            EmulatorNative.nativeStartSession(p.runtimeRoot, p.configPath, p.sdPath, p.dataPath)
            audio.start()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start session", t)
            synchronized(lock) { started = false }
        }
    }

    fun stop() {
        synchronized(lock) { if (!started) return }
        audio.stop()
        EmulatorNative.nativeStopSession()
        synchronized(lock) { started = false }
    }

    /**
     * Restart the emulator so a changed machine profile takes effect. Runs off the
     * caller's thread because stop() blocks until the native session has fully torn
     * down (openMSX quit + thread joins), which must not happen on the UI thread.
     */
    fun restart() {
        thread(name = "msx-restart") {
            stop()
            startIfNeeded()
        }
    }

    fun attachSurface(surface: Surface) {
        ensureSdl()
        // Hand the Surface to SDL so openMSX's Android video driver renders openMSX
        // straight onto it (SDLActivity.getNativeSurface returns mExternalSurface).
        org.libsdl.app.SDLActivity.mExternalSurface = surface
        EmulatorNative.nativeAttachSurface(surface)
    }

    /**
     * Replicates the parts of SDLSurface.surfaceChanged that SDL's Android video
     * driver needs: publish the Surface + its dimensions (so Android_SurfaceWidth/
     * Height are set) and mark it ready. openMSX's later SDL_CreateWindow then
     * builds its GLES2 surface on it.
     */
    fun surfaceChanged(surface: Surface, width: Int, height: Int) {
        ensureSdl()
        org.libsdl.app.SDLActivity.mExternalSurface = surface
        EmulatorNative.nativeAttachSurface(surface)
        try {
            org.libsdl.app.SDLActivity.nativeSetScreenResolution(width, height, width, height, 60f)
            org.libsdl.app.SDLActivity.onNativeResize()
            org.libsdl.app.SDLActivity.onNativeSurfaceChanged()
        } catch (t: Throwable) {
            Log.w(TAG, "SDL surface-changed notify failed", t)
        }
    }

    fun detachSurface() {
        try {
            org.libsdl.app.SDLActivity.onNativeSurfaceDestroyed()
        } catch (t: Throwable) {
            Log.w(TAG, "SDL surface-destroyed notify failed", t)
        }
        org.libsdl.app.SDLActivity.mExternalSurface = null
        EmulatorNative.nativeDetachSurface()
    }

    private val keyHandler = Handler(Looper.getMainLooper())

    // --- keyboard (SDL keysyms; see Msx) ------------------------------------
    /**
     * Tap a key: press, then release after a short hold. The MSX scans its
     * keyboard matrix only every ~2 vertical interrupts (~40ms), so an instant
     * down+up would be missed entirely (openMSX's own touch-keyboard rescheduling
     * lives in a #if PLATFORM_ANDROID block we don't compile). Holding ~80ms lets
     * the matrix scan catch the press.
     */
    fun tapKey(keycode: Int, character: Int, mods: Int) {
        EmulatorNative.nativeInjectKey(true, keycode, character, mods)
        keyHandler.postDelayed({
            EmulatorNative.nativeInjectKey(false, keycode, character, mods)
        }, KEY_HOLD_MS)
    }

    fun keyDown(keycode: Int, character: Int, mods: Int) =
        EmulatorNative.nativeInjectKey(true, keycode, character, mods)

    fun keyUp(keycode: Int, character: Int, mods: Int) =
        EmulatorNative.nativeInjectKey(false, keycode, character, mods)

    /** Front-panel reset (reboot the MSX). */
    fun reset() = EmulatorNative.nativeRequestReset()

    // --- joystick (MSX general-purpose joystick, 2 ports) -------------------
    /** Press/release a joystick direction or fire button (Msx.JOY_*) on [port]. */
    fun joyButton(id: Int, pressed: Boolean, port: Int = 0) =
        EmulatorNative.nativeSetJoystickButton(port, id, pressed)

    /**
     * Drive the MSX joystick on [port] from a normalized stick vector (-1..1).
     * Forward the raw axes; openMSX's MSXJoystick binding applies its own dead-zone
     * to turn them into the MSX's digital direction bits (so we must not also drive
     * the direction "buttons" here -- that would fight the analog axis values).
     */
    fun joyStick(x: Float, y: Float, port: Int = 0) {
        EmulatorNative.nativeSetJoystickAxis(port, Msx.AXIS_X, axisValue(x))
        EmulatorNative.nativeSetJoystickAxis(port, Msx.AXIS_Y, axisValue(y))
    }

    /**
     * Drive the joystick from four d-pad direction bits (e.g. a game controller).
     * Opposing directions share one axis on the native side (left/right -> axis 0,
     * up/down -> axis 1), so we resolve each axis to a single value and forward it
     * via [joyStick] -- driving them as separate buttons would let the second of a
     * pair (e.g. right=false) clobber the first (left=true) and swallow the press.
     */
    fun joyDirection(up: Boolean, down: Boolean, left: Boolean, right: Boolean, port: Int = 0) {
        val x = (if (right) 1f else 0f) - (if (left) 1f else 0f)
        val y = (if (down) 1f else 0f) - (if (up) 1f else 0f)
        joyStick(x, y, port)
    }

    private fun axisValue(v: Float): Int =
        (v.coerceIn(-1f, 1f) * Msx.AXIS_MAX).toInt()

    companion object {
        @Volatile private var instance: SessionController? = null
        private const val KEY_HOLD_MS = 80L
        private const val KEY_KEYBOARD_HAPTICS = "keyboardHaptics"
        private const val KEY_JOYSTICK_HAPTICS = "joystickHaptics"

        fun get(context: Context): SessionController =
            instance ?: synchronized(this) {
                instance ?: SessionController(context.applicationContext).also { instance = it }
            }

        private const val TAG = "FujiMsx"
    }
}
