package online.fujinet.go.msx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import android.view.InputDevice
import online.fujinet.go.msx.fujinet.FujiNetWebViewActivity
import online.fujinet.go.msx.input.GameControllerMapper
import online.fujinet.go.msx.input.MsxKeyMapper
import online.fujinet.go.msx.ui.EmulatorScreen
import online.fujinet.go.msx.ui.theme.FujiNetGoMsxTheme

/**
 * FujiNet Go MSX main screen: the MSX display plus the on-screen keyboard and a
 * control bar. The native layer (openMSX + the in-process FujiNet runtime over
 * FujiBusPacket-over-SLIP) is owned by [EmulatorSessionService] (a foreground
 * service) so it keeps running across activity changes (e.g. the FujiNet web
 * admin) and while backgrounded. The session itself is a process singleton; the
 * Power button stops both.
 */
class MainActivity : ComponentActivity() {

    private lateinit var session: SessionController

    // Routes a Bluetooth/USB game controller to the MSX joystick.
    private val gamepad by lazy {
        GameControllerMapper(
            onDirection = { up, down, left, right -> session.joyDirection(up, down, left, right) },
            onButton = { id, pressed -> session.joyButton(id, pressed) },
        )
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == EmulatorSessionService.ACTION_SHUTDOWN) {
            shutdown()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Hold clocks steady over a long emulation session (thermals permitting)
        // rather than letting DVFS oscillate the 60Hz loop off schedule.
        window.setSustainedPerformanceMode(true)
        session = SessionController.get(applicationContext)

        maybeRequestNotificationPermission()
        EmulatorSessionService.start(this)

        setContent {
            FujiNetGoMsxTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EmulatorScreen(
                        session = session,
                        onOpenFujiNet = ::openFujiNet,
                        onOpenSettings = ::openSettings,
                        onShutdown = ::shutdown,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == EmulatorSessionService.ACTION_SHUTDOWN) {
            shutdown()
        }
    }

    private fun openFujiNet() {
        startActivity(Intent(this, FujiNetWebViewActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Stop the emulator + FujiNet and close the app. */
    private fun shutdown() {
        EmulatorSessionService.shutdown(this)
        finishAndRemoveTask()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::session.isInitialized && gamepad.onMotion(event)) return true
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::session.isInitialized) {
            // Game controller first, then a hardware keyboard. A TV remote's D-pad is
            // claimed by neither, so it falls through to Compose focus navigation.
            if (gamepad.onKey(event)) return true
            if (routeHardwareKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Route a physical keyboard key to the emulated MSX via [MsxKeyMapper]. */
    private fun routeHardwareKey(event: KeyEvent): Boolean {
        if (!event.isFromPhysicalKeyboard()) return false
        // A D-pad cluster event drives Compose focus navigation only when it comes from
        // a real D-pad device (a TV remote / gamepad, marked SOURCE_DPAD); see
        // isDpadNavigation(). Arrow keys typed on an attached keyboard carry no
        // SOURCE_DPAD, so they fall through and reach the MSX (e.g. the FujiNet CONFIG
        // selection bar).
        if (isDpadNavigation(event)) return false
        val mapped = MsxKeyMapper.map(event) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> session.keyDown(mapped.keysym, mapped.character, mapped.mods)
            KeyEvent.ACTION_UP -> session.keyUp(mapped.keysym, mapped.character, mapped.mods)
            else -> return false
        }
        return true
    }

    private fun KeyEvent.isFromPhysicalKeyboard(): Boolean {
        val d = device ?: return false
        return !d.isVirtual &&
            d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            source and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
    }

    /**
     * The keys that must navigate/activate the on-screen keyboard rather than type into
     * the MSX. The D-pad cluster (arrows, DPAD_CENTER) and a remote's "OK"/ENTER are
     * reserved only when they carry a D-pad source -- i.e. they come from a TV remote or
     * gamepad. A typing keyboard's arrows and Enter carry no SOURCE_DPAD, so they fall
     * through and reach the MSX (arrows as cursor keysyms, Enter as RETURN) -- e.g. to
     * drive the FujiNet CONFIG selection bar.
     */
    private fun isDpadNavigation(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            event.source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        else -> false
    }

    // No session.stop() here: the foreground service owns the session's lifetime
    // so it survives this activity being finished. Stopping is explicit, via the
    // Power button -> shutdown().
}
