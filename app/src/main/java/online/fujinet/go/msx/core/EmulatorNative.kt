package online.fujinet.go.msx.core

import android.view.Surface

/**
 * JNI bridge to libmsxcore.so (openMSX embedded as static archives + the Android
 * host + the session runtime + the in-process FujiNet runtime shim).
 *
 * The native side drives openMSX one frame per `msxhost_core_run_frame()` on a
 * worker thread; the FujiNet runtime runs in-process and the two meet over
 * FujiBusPacket-over-SLIP on loopback TCP 1985 (the FujiNet runtime listens,
 * openMSX's FujiNet device connects in).
 */
object EmulatorNative {
    init {
        // libfujinet.so is dlopen'd by the native layer on demand; we only load
        // our own core, which statically links openMSX.
        System.loadLibrary("msxcore")
    }

    external fun nativeStartSession(
        runtimeRoot: String,
        configPath: String,
        sdPath: String,
        dataPath: String,
    )

    external fun nativeStopSession()
    external fun nativeIsRunning(): Boolean
    external fun nativeAttachSurface(surface: Surface)
    external fun nativeDetachSurface()
    external fun nativeRequestReset()

    /**
     * Injects a key event into the emulated MSX. [keycode] is an SDL keysym (see
     * MsxKeyMapper), [character] the unicode/ASCII code (or 0), [mods] an SDL
     * key-modifier bitmask.
     */
    external fun nativeInjectKey(down: Boolean, keycode: Int, character: Int, mods: Int)

    /** Sets a joystick direction/fire-button state for MSX [port] (0 or 1). */
    external fun nativeSetJoystickButton(port: Int, id: Int, pressed: Boolean)

    /** Sets an analog axis (0=X 1=Y) for [port], value -32768..32767. */
    external fun nativeSetJoystickAxis(port: Int, axis: Int, value: Int)

    /**
     * Blocks until [out] can be filled with a full block of interleaved stereo
     * signed-16 samples (44100 Hz), silence-padding on underrun. Returns the
     * count written (always [out].size).
     */
    external fun nativeFillAudio(out: ShortArray): Int

    /** Toggle audio drain; pass false on shutdown to unblock a waiting fill. */
    external fun nativeAudioSetActive(active: Boolean)
}
