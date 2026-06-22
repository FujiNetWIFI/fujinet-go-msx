package online.fujinet.go.msx.input

import android.view.KeyEvent

/**
 * Maps a physical Android key event (Bluetooth / USB / built-in keyboard) to the
 * SDL keysym + character + modifier triple openMSX expects (see [Msx]).
 *
 * Printable keys resolve their SDL keysym from the *unshifted* unicode char
 * (which equals the SDL_Keycode for letters/digits/symbols) and their character
 * from the shifted unicode char; non-printable keys map by Android keycode.
 */
object MsxKeyMapper {

    data class Mapped(val keysym: Int, val character: Int, val mods: Int)

    /** Returns the mapping for [event], or null if the key isn't handled. */
    fun map(event: KeyEvent): Mapped? {
        val mods = modsOf(event)

        named(event.keyCode)?.let { keysym ->
            return Mapped(keysym, characterFor(keysym), mods)
        }

        val base = event.getUnicodeChar(0)              // unshifted -> SDL keysym
        if (base == 0) return null
        val shifted = event.unicodeChar                 // honours current meta
        val character = if (shifted != 0) shifted else base
        return Mapped(base, character, mods)
    }

    private fun characterFor(keysym: Int): Int = when (keysym) {
        Msx.K_RETURN -> 13
        Msx.K_BACKSPACE -> 8
        Msx.K_TAB -> 9
        Msx.K_ESCAPE -> 27
        Msx.K_SPACE -> 32
        Msx.K_DELETE -> 127
        else -> 0
    }

    private fun named(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Msx.K_RETURN
        KeyEvent.KEYCODE_DEL -> Msx.K_BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Msx.K_DELETE
        KeyEvent.KEYCODE_TAB -> Msx.K_TAB
        KeyEvent.KEYCODE_ESCAPE -> Msx.K_ESCAPE
        KeyEvent.KEYCODE_SPACE -> Msx.K_SPACE
        KeyEvent.KEYCODE_DPAD_UP -> Msx.K_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Msx.K_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Msx.K_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Msx.K_RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> Msx.K_HOME
        KeyEvent.KEYCODE_INSERT -> Msx.K_INSERT
        KeyEvent.KEYCODE_F1 -> Msx.K_F1
        KeyEvent.KEYCODE_F2 -> Msx.K_F2
        KeyEvent.KEYCODE_F3 -> Msx.K_F3
        KeyEvent.KEYCODE_F4 -> Msx.K_F4
        KeyEvent.KEYCODE_F5 -> Msx.K_F5
        KeyEvent.KEYCODE_CAPS_LOCK -> Msx.K_CAPS
        KeyEvent.KEYCODE_BREAK -> Msx.K_STOP
        else -> null
    }

    private fun modsOf(event: KeyEvent): Int {
        var mods = 0
        if (event.isShiftPressed) mods = mods or Msx.MOD_SHIFT
        if (event.isCtrlPressed) mods = mods or Msx.MOD_CTRL
        if (event.isAltPressed) mods = mods or Msx.MOD_GRAPH
        return mods
    }
}
