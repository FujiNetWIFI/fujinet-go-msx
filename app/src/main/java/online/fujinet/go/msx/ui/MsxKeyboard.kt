package online.fujinet.go.msx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import online.fujinet.go.msx.SessionController
import online.fujinet.go.msx.input.Msx

// Minimum keypress duration: long enough for the MSX matrix scan (and the FujiNet
// config's key poll) to latch even a quick tap. Longer presses release on
// finger-up, so the firmware's own typematic repeat applies -- no fixed-duration
// over-hold (the old cause of double/"bounced" keys).
private const val KEY_MIN_HOLD_MS = 80L

// --- FS-A1 colour scheme: amber/orange legends on charcoal keycaps ------------
private val KeyBg = Color(0xFF2B2B2B)        // charcoal keycap
private val KeyText = Color(0xFFF2871E)      // FS-A1 amber-orange legend
private val KeyActiveCap = Color(0xFFF2871E) // lit modifier: inverted
private val KeyActiveText = Color(0xFF161616)

/**
 * One printable key. [face] is the unshifted legend; [shiftFace] the shifted
 * legend; [kanaFace] the katakana legend shown when KANA-lock is on (JIS かな
 * layout). [code] is the SDL keysym, [ascii]/[shiftAscii] the unicode the core
 * uses in CHARACTER mapping.
 */
private data class Key(
    val face: String,
    val shiftFace: String = face,
    val kanaFace: String = "",
    val code: Int,
    val ascii: Int,
    val shiftAscii: Int = ascii,
    val weight: Float = 1f,
)

// Number row. Shifted symbols + katakana follow the MSX / FS-A1 (JIS) layout.
private val ROW1 = listOf(
    Key("1", "!", "ヌ", '1'.code, '1'.code, '!'.code),
    Key("2", "\"", "フ", '2'.code, '2'.code, '"'.code),
    Key("3", "#", "ア", '3'.code, '3'.code, '#'.code),
    Key("4", "$", "ウ", '4'.code, '4'.code, '$'.code),
    Key("5", "%", "エ", '5'.code, '5'.code, '%'.code),
    Key("6", "&", "オ", '6'.code, '6'.code, '&'.code),
    Key("7", "'", "ヤ", '7'.code, '7'.code, '\''.code),
    Key("8", "(", "ユ", '8'.code, '8'.code, '('.code),
    Key("9", ")", "ヨ", '9'.code, '9'.code, ')'.code),
    Key("0", "_", "ワ", '0'.code, '0'.code, '_'.code),
    Key("-", "=", "ホ", '-'.code, '-'.code, '='.code),
    Key("^", "~", "ヘ", '^'.code, '^'.code, '~'.code),
    Key("\\", "|", "ー", '\\'.code, '\\'.code, '|'.code),
)

private fun letter(c: Char, kana: String) =
    Key(c.uppercase(), c.uppercase(), kana, Msx.K_a + (c - 'a'), c.code, c.uppercaseChar().code)

private val ROW2 =
    listOf(
        letter('q', "タ"), letter('w', "テ"), letter('e', "イ"), letter('r', "ス"),
        letter('t', "カ"), letter('y', "ン"), letter('u', "ナ"), letter('i', "ニ"),
        letter('o', "ラ"), letter('p', "セ"),
    ) +
        Key("@", "`", "゛", '@'.code, '@'.code, '`'.code) +
        Key("[", "{", "゜", '['.code, '['.code, '{'.code)

private val ROW3 =
    listOf(
        letter('a', "チ"), letter('s', "ト"), letter('d', "シ"), letter('f', "ハ"),
        letter('g', "キ"), letter('h', "ク"), letter('j', "マ"), letter('k', "ノ"),
        letter('l', "リ"),
    ) +
        Key(";", "+", "レ", ';'.code, ';'.code, '+'.code) +
        Key(":", "*", "ケ", ':'.code, ':'.code, '*'.code) +
        Key("]", "}", "ム", ']'.code, ']'.code, '}'.code)

private val ROW4 =
    listOf(
        letter('z', "ツ"), letter('x', "サ"), letter('c', "ソ"), letter('v', "ヒ"),
        letter('b', "コ"), letter('n', "ミ"), letter('m', "モ"),
    ) +
        Key(",", "<", "ネ", ','.code, ','.code, '<'.code) +
        Key(".", ">", "ル", '.'.code, '.'.code, '>'.code) +
        Key("/", "?", "メ", '/'.code, '/'.code, '?'.code)

/**
 * On-screen MSX2 keyboard modeled on the Panasonic FS-A1: an F1-F5 function row
 * (F6-F10 when SHIFT is lit), the typewriter block, and a modifier row with the
 * cursor diamond.
 *
 * SHIFT / CTRL / GRAPH / CODE are sticky one-shot modifiers (lit until the next
 * key); CAP and かな (KANA) are locks that toggle the MSX caps/kana-lock keys.
 * A lit SHIFT/GRAPH/CAP/KANA also switches the visible keycap legends. Modifiers
 * are injected into openMSX as real matrix key presses around the main key, so
 * the emulated firmware produces the authentic shifted / graphic / katakana
 * character (rather than us guessing the codepoint).
 */
@Composable
fun MsxKeyboard(session: SessionController, modifier: Modifier = Modifier) {
    var shift by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var graph by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }
    var kana by remember { mutableStateOf(false) }

    fun clearOneShot() { shift = false; ctrl = false; graph = false; code = false }

    // Each press returns the chord of keysyms it pressed (main key first, then any
    // injected modifiers); the keycap hands that same chord back on release. This
    // keeps press/release self-contained per keycap (correct under recomposition
    // and multi-touch) -- the modifier state is read live from the remembered vars.

    // Printable key with the lit modifiers. GRAPH/CODE/KANA/CTRL route through the
    // matrix (unicode 0) with the modifier keys injected as real presses, so the
    // firmware maps the graphic/katakana glyph. The plain/shift case uses the
    // unicode codepoint instead -- CHARACTER mapping then yields the exact char and
    // encodes SHIFT itself, so we must NOT also inject the SHIFT matrix key there.
    fun pressKey(k: Key): List<Int> {
        val useMatrix = graph || code || kana || ctrl
        val ch = when {
            useMatrix -> 0
            shift -> k.shiftAscii
            else -> k.ascii
        }
        val inject = if (useMatrix) buildList {
            if (graph) add(Msx.K_GRAPH)
            if (code) add(Msx.K_CODE)
            if (ctrl) add(Msx.K_LCTRL)
            if (shift) add(Msx.K_LSHIFT)
        } else emptyList()
        for (m in inject) session.keyDown(m, 0, 0)
        session.keyDown(k.code, ch, if (shift && !useMatrix) Msx.MOD_SHIFT else 0)
        clearOneShot()
        return listOf(k.code) + inject
    }

    // Special (non-printable) key, optionally with SHIFT injected so the firmware
    // sees e.g. SHIFT+F1 = F6 or SHIFT+HOME = CLS.
    fun pressSpecial(sym: Int, withShift: Boolean): List<Int> {
        val inject = if (withShift && shift) listOf(Msx.K_LSHIFT) else emptyList()
        for (m in inject) session.keyDown(m, 0, 0)
        session.keyDown(sym, 0, if (shift && withShift) Msx.MOD_SHIFT else 0)
        clearOneShot()
        return listOf(sym) + inject
    }

    // A plain key with no modifier handling (SPACE/RET/TAB/BS).
    fun pressPlain(sym: Int, ascii: Int): List<Int> {
        session.keyDown(sym, ascii, 0); clearOneShot(); return listOf(sym)
    }

    fun releaseChord(chord: List<Int>) {
        if (chord.isEmpty()) return
        session.keyUp(chord.first(), 0, 0)                          // main key
        for (m in chord.drop(1).reversed()) session.keyUp(m, 0, 0)  // modifiers
    }

    fun faceOf(k: Key): String = when {
        kana && k.kanaFace.isNotEmpty() -> k.kanaFace
        shift -> k.shiftFace
        graph -> k.face            // graphic glyphs are MSX-font; show base legend
        caps && k.code in Msx.K_a..Msx.K_z -> k.face.uppercase()
        else -> if (k.code in Msx.K_a..Msx.K_z && !caps) k.face.lowercase() else k.face
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Function + editing row. F1-F5 become F6-F10 while SHIFT is lit.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val fKeys = listOf(Msx.K_F1, Msx.K_F2, Msx.K_F3, Msx.K_F4, Msx.K_F5)
            fKeys.forEachIndexed { i, sym ->
                val label = if (shift) "F${i + 6}" else "F${i + 1}"
                KeyCap(label, Modifier.weight(1f),
                    onDown = { pressSpecial(sym, withShift = true) }, onUp = { releaseChord(it) })
            }
            KeyCap("STOP", Modifier.weight(1.2f),
                onDown = { pressSpecial(Msx.K_STOP, false) }, onUp = { releaseChord(it) })
            KeyCap("SEL", Modifier.weight(1.2f),
                onDown = { pressSpecial(Msx.K_SELECT, false) }, onUp = { releaseChord(it) })
            KeyCap("INS", Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_INSERT, false) }, onUp = { releaseChord(it) })
            KeyCap("DEL", Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_DELETE, false) }, onUp = { releaseChord(it) })
        }

        for (rowKeys in listOf(ROW1, ROW2, ROW3, ROW4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (k in rowKeys) {
                    KeyCap(faceOf(k), Modifier.weight(k.weight),
                        onDown = { pressKey(k) }, onUp = { releaseChord(it) })
                }
            }
        }

        // Modifier row: CTRL / SHIFT / GRAPH / CODE + ESC / SPACE / RET.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ModKey("CTRL", ctrl, Modifier.weight(1.2f)) { ctrl = !ctrl }
            ModKey("SHIFT", shift, Modifier.weight(1.2f)) { shift = !shift }
            ModKey("GRAPH", graph, Modifier.weight(1.3f)) { graph = !graph }
            ModKey("CODE", code, Modifier.weight(1.2f)) { code = !code }
            KeyCap("ESC", Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_ESCAPE, false) }, onUp = { releaseChord(it) })
            KeyCap("SPACE", Modifier.weight(3f),
                onDown = { pressPlain(Msx.K_SPACE, 32) },
                onUp = { releaseChord(it) })
            KeyCap("RET", Modifier.weight(1.6f),
                onDown = { pressPlain(Msx.K_RETURN, 13) },
                onUp = { releaseChord(it) })
        }

        // Bottom: CAP / KANA locks, TAB / BS, HOME (CLS when SHIFT) + cursor diamond.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ModKey("CAP", caps, Modifier.weight(1f)) { caps = !caps; session.tapKey(Msx.K_CAPS, 0, 0) }
            ModKey("かな", kana, Modifier.weight(1f)) { kana = !kana; session.tapKey(Msx.K_CODE, 0, 0) }
            KeyCap("TAB", Modifier.weight(1f),
                onDown = { pressPlain(Msx.K_TAB, 9) }, onUp = { releaseChord(it) })
            KeyCap("BS", Modifier.weight(1f),
                onDown = { pressPlain(Msx.K_BACKSPACE, 8) }, onUp = { releaseChord(it) })
            KeyCap(if (shift) "CLS" else "HOME", Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_HOME, withShift = true) }, onUp = { releaseChord(it) })
            KeyCap("←", Modifier.weight(1f), onDown = { pressSpecial(Msx.K_LEFT, false) }, onUp = { releaseChord(it) })
            KeyCap("↓", Modifier.weight(1f), onDown = { pressSpecial(Msx.K_DOWN, false) }, onUp = { releaseChord(it) })
            KeyCap("↑", Modifier.weight(1f), onDown = { pressSpecial(Msx.K_UP, false) }, onUp = { releaseChord(it) })
            KeyCap("→", Modifier.weight(1f), onDown = { pressSpecial(Msx.K_RIGHT, false) }, onUp = { releaseChord(it) })
        }
    }
}

/**
 * A momentary keycap: presses on touch-down and releases on touch-up, so the
 * emulated key is held exactly as long as the finger is down (matching real
 * hardware and avoiding the multi-frame "bounce" of a fixed-duration tap). A
 * plain Box (not a Button) so its own click handling doesn't swallow the
 * press/release gesture.
 */
@Composable
private fun KeyCap(label: String, modifier: Modifier = Modifier, onDown: () -> List<Int>, onUp: (List<Int>) -> Unit) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KeyBg)
            .pointerInput(label) {
                detectTapGestures(onPress = {
                    val chord = onDown()
                    val downAt = System.currentTimeMillis()
                    tryAwaitRelease()
                    // Hold at least KEY_MIN_HOLD_MS so the MSX keyboard matrix scan
                    // (~2 vertical interrupts) latches the press even for a very
                    // quick tap; longer presses release on finger-up, so the
                    // firmware's own typematic repeat behaves naturally (no fixed
                    // multi-frame "bounce").
                    val dt = System.currentTimeMillis() - downAt
                    if (dt < KEY_MIN_HOLD_MS) delay(KEY_MIN_HOLD_MS - dt)
                    onUp(chord)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = KeyText, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun ModKey(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        colors = if (active) {
            ButtonDefaults.buttonColors(containerColor = KeyActiveCap, contentColor = KeyActiveText)
        } else {
            ButtonDefaults.buttonColors(containerColor = KeyBg, contentColor = KeyText)
        },
    ) {
        Text(label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
