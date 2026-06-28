package online.fujinet.go.msx.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Android-keycode -> SDL keysym mapping for the named (non-printable) keys
 * a hardware keyboard sends to openMSX. The KEYCODE_* values are compile-time
 * constants (inlined), so this runs under plain JUnit without an Android device.
 * Printable keys are resolved from the event's Unicode char at runtime and aren't
 * covered here.
 */
class MsxKeyMapperTest {

    @Test
    fun namedKeysMapToSdlKeysyms() {
        assertEquals(Msx.K_RETURN, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_ENTER))
        assertEquals(Msx.K_RETURN, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(Msx.K_BACKSPACE, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_DEL))
        assertEquals(Msx.K_DELETE, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(Msx.K_TAB, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_TAB))
        assertEquals(Msx.K_ESCAPE, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(Msx.K_SPACE, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_SPACE))
        assertEquals(Msx.K_HOME, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(Msx.K_F1, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_F1))
        assertEquals(Msx.K_STOP, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_BREAK))
    }

    @Test
    fun cursorKeysMapAndUnknownKeysAreNotForwarded() {
        // Cursor keys typed on a keyboard reach the MSX (e.g. the FujiNet CONFIG
        // selection bar). MainActivity's isDpadNavigation() routes a TV remote's D-pad
        // (SOURCE_DPAD) to focus navigation before this keycode lookup is consulted.
        assertEquals(Msx.K_LEFT, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Msx.K_RIGHT, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(Msx.K_UP, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(Msx.K_DOWN, MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_DPAD_DOWN))
        // DPAD_CENTER has no MSX equivalent; unknown keys aren't forwarded either.
        assertNull(MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(MsxKeyMapper.specialKeysym(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
