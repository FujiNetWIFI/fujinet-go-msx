package online.fujinet.go.msx.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.fujinet.go.msx.SessionController
import online.fujinet.go.msx.input.Msx

// Contact-jitter debounce: ignore a second press of the same key within this many
// ms of the last (a real finger can flicker down->micro-lift->down; that re-press
// is far sooner than any intentional re-tap of the same key).
private const val KEY_DEBOUNCE_MS = 120L

// --- FS-A1 colour scheme: amber/orange legends on charcoal keycaps ------------
private val KeyBg = Color(0xFF2B2B2B)        // charcoal keycap
private val KeyText = Color(0xFFF2871E)      // FS-A1 amber-orange legend
private val KeyActiveCap = Color(0xFFF2871E) // lit modifier: inverted
private val KeyActiveText = Color(0xFF161616)
private val FocusAmber = Color(0xFFFFC107)   // D-pad / TV-remote focused key

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
 * Sticky-modifier state for the MSX keyboard: SHIFT/CTRL/GRAPH/CODE are one-shot
 * (lit until the next key); CAP and かな (KANA) are locks. Modifiers are injected into
 * openMSX as real matrix key presses around the main key so the firmware produces the
 * authentic shifted / graphic / katakana character. Hoisted into a holder -- along with
 * the press/release chord logic and the legend picker -- so the portrait keyboard and
 * the split landscape halves share one source of truth (a modifier lit on one flank
 * applies to a key on the other).
 */
private class MsxKeyboardModifiers {
    var shift by mutableStateOf(false)
    var ctrl by mutableStateOf(false)
    var graph by mutableStateOf(false)
    var code by mutableStateOf(false)
    var caps by mutableStateOf(false)
    var kana by mutableStateOf(false)

    fun clearOneShot() { shift = false; ctrl = false; graph = false; code = false }

    // Each press returns the chord of keysyms it pressed (main key first, then any
    // injected modifiers); the keycap hands that same chord back on release. This keeps
    // press/release self-contained per keycap (correct under recomposition and
    // multi-touch) -- the modifier state is read live from these vars.

    // Printable key with the lit modifiers. GRAPH/CODE/KANA/CTRL route through the
    // matrix (unicode 0) with the modifier keys injected as real presses, so the
    // firmware maps the graphic/katakana glyph. The plain/shift case uses the unicode
    // codepoint instead -- CHARACTER mapping then yields the exact char and encodes
    // SHIFT itself, so we must NOT also inject the SHIFT matrix key there.
    fun pressKey(session: SessionController, k: Key): List<Int> {
        val useMatrix = graph || code || kana || ctrl
        val ch = when {
            useMatrix -> 0
            shift -> k.shiftAscii
            else -> k.ascii
        }
        val inject = buildList {
            if (graph) add(Msx.K_GRAPH)
            if (code) add(Msx.K_CODE)
            if (ctrl) add(Msx.K_LCTRL)
            if (shift) add(Msx.K_LSHIFT)
        }
        for (m in inject) session.keyDown(m, 0, 0)
        session.keyDown(k.code, ch, if (shift) Msx.MOD_SHIFT else 0)
        clearOneShot()
        return listOf(k.code) + inject
    }

    // Special (non-printable) key, optionally with SHIFT injected so the firmware sees
    // e.g. SHIFT+F1 = F6 or SHIFT+HOME = CLS.
    fun pressSpecial(session: SessionController, sym: Int, withShift: Boolean): List<Int> {
        val inject = if (withShift && shift) listOf(Msx.K_LSHIFT) else emptyList()
        for (m in inject) session.keyDown(m, 0, 0)
        session.keyDown(sym, 0, if (shift && withShift) Msx.MOD_SHIFT else 0)
        clearOneShot()
        return listOf(sym) + inject
    }

    // A plain key with no modifier handling (SPACE/RET/TAB/BS).
    fun pressPlain(session: SessionController, sym: Int, ascii: Int): List<Int> {
        session.keyDown(sym, ascii, 0); clearOneShot(); return listOf(sym)
    }

    fun releaseChord(session: SessionController, chord: List<Int>) {
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
}

@Composable
private fun rememberMsxKeyboardModifiers() = remember { MsxKeyboardModifiers() }

/**
 * On-screen MSX2 keyboard modeled on the Panasonic FS-A1: an F1-F5 function row
 * (F6-F10 when SHIFT is lit), the typewriter block, and a modifier row with the
 * cursor diamond.
 *
 * SHIFT / CTRL / GRAPH / CODE are sticky one-shot modifiers (lit until the next
 * key); CAP and かな (KANA) are locks that toggle the MSX caps/kana-lock keys.
 * A lit SHIFT/GRAPH/CAP/KANA also switches the visible keycap legends.
 *
 * Full-width; used in portrait and as the fallback for landscapes too narrow for
 * [LandscapeSplitKeyboard].
 */
@Composable
fun MsxKeyboard(session: SessionController, modifier: Modifier = Modifier, hapticsEnabled: Boolean = true) {
    val mods = rememberMsxKeyboardModifiers()
    val shift = mods.shift

    val emitHaptic = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    val onHaptic = { if (hapticsEnabled) emitHaptic() }

    fun pressKey(k: Key) = mods.pressKey(session, k)
    fun pressSpecial(sym: Int, withShift: Boolean) = mods.pressSpecial(session, sym, withShift)
    fun pressPlain(sym: Int, ascii: Int) = mods.pressPlain(session, sym, ascii)
    fun releaseChord(chord: List<Int>) = mods.releaseChord(session, chord)
    fun faceOf(k: Key) = mods.faceOf(k)

    // Every keycap routes its gesture through KeyCap/ModKey; rather than thread an
    // onHaptic through all the call sites, expose the gated pulse via a CompositionLocal
    // that those composables read and fire on press.
    CompositionLocalProvider(LocalKeyHaptic provides onHaptic) {
    Column(
        modifier = modifier.fillMaxWidth().padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Function + editing row. F1-F5 become F6-F10 while SHIFT is lit.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val fKeys = listOf(Msx.K_F1, Msx.K_F2, Msx.K_F3, Msx.K_F4, Msx.K_F5)
            fKeys.forEachIndexed { i, sym ->
                val label = if (shift) "F${i + 6}" else "F${i + 1}"
                KeyCap(label, sym, Modifier.weight(1f),
                    onDown = { pressSpecial(sym, withShift = true) }, onUp = { releaseChord(it) })
            }
            KeyCap("STOP", Msx.K_STOP, Modifier.weight(1.2f),
                onDown = { pressSpecial(Msx.K_STOP, false) }, onUp = { releaseChord(it) })
            KeyCap("SEL", Msx.K_SELECT, Modifier.weight(1.2f),
                onDown = { pressSpecial(Msx.K_SELECT, false) }, onUp = { releaseChord(it) })
            KeyCap("INS", Msx.K_INSERT, Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_INSERT, false) }, onUp = { releaseChord(it) })
            KeyCap("DEL", Msx.K_DELETE, Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_DELETE, false) }, onUp = { releaseChord(it) })
        }

        for (rowKeys in listOf(ROW1, ROW2, ROW3, ROW4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (k in rowKeys) {
                    KeyCap(faceOf(k), k.code, Modifier.weight(k.weight),
                        onDown = { pressKey(k) }, onUp = { releaseChord(it) })
                }
            }
        }

        // Modifier row: CTRL / SHIFT / GRAPH / CODE + ESC / SPACE / RET.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ModKey("CTRL", mods.ctrl, Modifier.weight(1.2f)) { mods.ctrl = !mods.ctrl }
            ModKey("SHIFT", mods.shift, Modifier.weight(1.2f)) { mods.shift = !mods.shift }
            ModKey("GRAPH", mods.graph, Modifier.weight(1.3f)) { mods.graph = !mods.graph }
            ModKey("CODE", mods.code, Modifier.weight(1.2f)) { mods.code = !mods.code }
            KeyCap("ESC", Msx.K_ESCAPE, Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_ESCAPE, false) }, onUp = { releaseChord(it) })
            KeyCap("SPACE", Msx.K_SPACE, Modifier.weight(3f),
                onDown = { pressPlain(Msx.K_SPACE, 32) },
                onUp = { releaseChord(it) })
            KeyCap("RET", Msx.K_RETURN, Modifier.weight(1.6f),
                onDown = { pressPlain(Msx.K_RETURN, 13) },
                onUp = { releaseChord(it) })
        }

        // Bottom: CAP / KANA locks, TAB / BS, HOME (CLS when SHIFT) + cursor diamond.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ModKey("CAP", mods.caps, Modifier.weight(1f)) { mods.caps = !mods.caps; session.tapKey(Msx.K_CAPS, 0, 0) }
            ModKey("かな", mods.kana, Modifier.weight(1f)) { mods.kana = !mods.kana; session.tapKey(Msx.K_CODE, 0, 0) }
            KeyCap("TAB", Msx.K_TAB, Modifier.weight(1f),
                onDown = { pressPlain(Msx.K_TAB, 9) }, onUp = { releaseChord(it) })
            KeyCap("BS", Msx.K_BACKSPACE, Modifier.weight(1f),
                onDown = { pressPlain(Msx.K_BACKSPACE, 8) }, onUp = { releaseChord(it) })
            KeyCap(if (shift) "CLS" else "HOME", Msx.K_HOME, Modifier.weight(1f),
                onDown = { pressSpecial(Msx.K_HOME, withShift = true) }, onUp = { releaseChord(it) })
            KeyCap("←", Msx.K_LEFT, Modifier.weight(1f), onDown = { pressSpecial(Msx.K_LEFT, false) }, onUp = { releaseChord(it) })
            KeyCap("↓", Msx.K_DOWN, Modifier.weight(1f), onDown = { pressSpecial(Msx.K_DOWN, false) }, onUp = { releaseChord(it) })
            KeyCap("↑", Msx.K_UP, Modifier.weight(1f), onDown = { pressSpecial(Msx.K_UP, false) }, onUp = { releaseChord(it) })
            KeyCap("→", Msx.K_RIGHT, Modifier.weight(1f), onDown = { pressSpecial(Msx.K_RIGHT, false) }, onUp = { releaseChord(it) })
        }
    }
    }
}

/** Minimum flank width worth splitting into; below this we keep the stacked keyboard. */
private val MIN_HALF_WIDTH = 150.dp
/** Cap so keys don't grow absurdly tall on very tall landscape panels. */
private val MAX_SPLIT_KEY_HEIGHT = 60.dp
/** The MSX keyboard has seven rows, stacked to fill each flank. */
private const val SPLIT_ROWS = 7

/**
 * Landscape MSX keyboard split into two halves placed in the pillar-box margins beside
 * the 4:3 picture, so the display keeps full height between them. Both halves share one
 * [MsxKeyboardModifiers]. Falls back to the stacked [MsxKeyboard] when too narrow.
 */
@Composable
fun LandscapeSplitKeyboard(
    session: SessionController,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
) {
    val mods = rememberMsxKeyboardModifiers()
    val emitHaptic = rememberFujiHaptic(FujiHapticPattern.KeyPress)
    val onHaptic = { if (hapticsEnabled) emitHaptic() }

    BoxWithConstraints(modifier = modifier.background(Color.Black)) {
        val sideWidth = (maxWidth - maxHeight * FRAME_RATIO) / 2
        if (sideWidth < MIN_HALF_WIDTH) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    EmulatorSurface(session = session, modifier = Modifier.fillMaxSize())
                }
                MsxKeyboard(session = session, hapticsEnabled = hapticsEnabled)
            }
        } else {
            val gap = 2.dp
            val keyH = ((maxHeight - gap * (SPLIT_ROWS - 1)) / SPLIT_ROWS).coerceAtMost(MAX_SPLIT_KEY_HEIGHT)
            val font: TextUnit = 14.sp
            CompositionLocalProvider(
                LocalKeyHaptic provides onHaptic,
                LocalKeyHeight provides keyH,
                LocalKeyFont provides font,
            ) {
                Row(Modifier.fillMaxSize()) {
                    MsxKeyboardHalf(true, mods, session, Modifier.width(sideWidth))
                    EmulatorSurface(session = session, modifier = Modifier.weight(1f).fillMaxHeight())
                    MsxKeyboardHalf(false, mods, session, Modifier.width(sideWidth))
                }
            }
        }
    }
}

/**
 * One half of the split MSX keyboard: the seven rows stacked to fill the flank. The
 * typewriter rows (ROW1..ROW4) are sliced down the middle; the function, modifier and
 * bottom rows are hand-split across the two flanks.
 */
@Composable
private fun MsxKeyboardHalf(
    left: Boolean,
    mods: MsxKeyboardModifiers,
    session: SessionController,
    modifier: Modifier,
) {
    val shift = mods.shift
    // Slice the typewriter rows at ~half each (ROW1 is 13 keys -> 7/6, the rest even).
    fun <T> half(list: List<T>): List<T> {
        val n = (list.size + 1) / 2
        return if (left) list.take(n) else list.drop(n)
    }
    val gap = 2.dp
    Column(
        modifier = modifier.padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
    ) {
        val fKeys = listOf(Msx.K_F1, Msx.K_F2, Msx.K_F3, Msx.K_F4, Msx.K_F5)
        // Function + editing row.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (left) {
                fKeys.take(3).forEachIndexed { i, sym ->
                    KeyCap(if (shift) "F${i + 6}" else "F${i + 1}", sym, Modifier.weight(1f),
                        onDown = { mods.pressSpecial(session, sym, true) }, onUp = { mods.releaseChord(session, it) })
                }
                KeyCap("STOP", Msx.K_STOP, Modifier.weight(1.3f),
                    onDown = { mods.pressSpecial(session, Msx.K_STOP, false) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("SEL", Msx.K_SELECT, Modifier.weight(1.3f),
                    onDown = { mods.pressSpecial(session, Msx.K_SELECT, false) }, onUp = { mods.releaseChord(session, it) })
            } else {
                fKeys.drop(3).forEachIndexed { j, sym ->
                    val i = j + 3
                    KeyCap(if (shift) "F${i + 6}" else "F${i + 1}", sym, Modifier.weight(1f),
                        onDown = { mods.pressSpecial(session, sym, true) }, onUp = { mods.releaseChord(session, it) })
                }
                KeyCap("INS", Msx.K_INSERT, Modifier.weight(1.2f),
                    onDown = { mods.pressSpecial(session, Msx.K_INSERT, false) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("DEL", Msx.K_DELETE, Modifier.weight(1.2f),
                    onDown = { mods.pressSpecial(session, Msx.K_DELETE, false) }, onUp = { mods.releaseChord(session, it) })
            }
        }

        // Typewriter rows.
        for (rowKeys in listOf(ROW1, ROW2, ROW3, ROW4)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (k in half(rowKeys)) {
                    KeyCap(mods.faceOf(k), k.code, Modifier.weight(k.weight),
                        onDown = { mods.pressKey(session, k) }, onUp = { mods.releaseChord(session, it) })
                }
            }
        }

        // Modifier row: CTRL/SHIFT/GRAPH/CODE (left) -- ESC/SPACE/RET (right).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (left) {
                ModKey("CTRL", mods.ctrl, Modifier.weight(1f)) { mods.ctrl = !mods.ctrl }
                ModKey("SHIFT", mods.shift, Modifier.weight(1f)) { mods.shift = !mods.shift }
                ModKey("GRAPH", mods.graph, Modifier.weight(1.1f)) { mods.graph = !mods.graph }
                ModKey("CODE", mods.code, Modifier.weight(1f)) { mods.code = !mods.code }
            } else {
                KeyCap("ESC", Msx.K_ESCAPE, Modifier.weight(1f),
                    onDown = { mods.pressSpecial(session, Msx.K_ESCAPE, false) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("SPACE", Msx.K_SPACE, Modifier.weight(2.4f),
                    onDown = { mods.pressPlain(session, Msx.K_SPACE, 32) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("RET", Msx.K_RETURN, Modifier.weight(1.6f),
                    onDown = { mods.pressPlain(session, Msx.K_RETURN, 13) }, onUp = { mods.releaseChord(session, it) })
            }
        }

        // Bottom: CAP/KANA/TAB/BS (left) -- HOME + cursor diamond (right).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (left) {
                ModKey("CAP", mods.caps, Modifier.weight(1f)) { mods.caps = !mods.caps; session.tapKey(Msx.K_CAPS, 0, 0) }
                ModKey("かな", mods.kana, Modifier.weight(1f)) { mods.kana = !mods.kana; session.tapKey(Msx.K_CODE, 0, 0) }
                KeyCap("TAB", Msx.K_TAB, Modifier.weight(1f),
                    onDown = { mods.pressPlain(session, Msx.K_TAB, 9) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("BS", Msx.K_BACKSPACE, Modifier.weight(1f),
                    onDown = { mods.pressPlain(session, Msx.K_BACKSPACE, 8) }, onUp = { mods.releaseChord(session, it) })
            } else {
                KeyCap(if (shift) "CLS" else "HOME", Msx.K_HOME, Modifier.weight(1.2f),
                    onDown = { mods.pressSpecial(session, Msx.K_HOME, true) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("←", Msx.K_LEFT, Modifier.weight(1f), onDown = { mods.pressSpecial(session, Msx.K_LEFT, false) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("↓", Msx.K_DOWN, Modifier.weight(1f), onDown = { mods.pressSpecial(session, Msx.K_DOWN, false) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("↑", Msx.K_UP, Modifier.weight(1f), onDown = { mods.pressSpecial(session, Msx.K_UP, false) }, onUp = { mods.releaseChord(session, it) })
                KeyCap("→", Msx.K_RIGHT, Modifier.weight(1f), onDown = { mods.pressSpecial(session, Msx.K_RIGHT, false) }, onUp = { mods.releaseChord(session, it) })
            }
        }
    }
}

/** The gated key-press haptic pulse, supplied by [MsxKeyboard] and fired by each key. */
private val LocalKeyHaptic = staticCompositionLocalOf<() -> Unit> { {} }
/** Split-layout key sizing, supplied by [LandscapeSplitKeyboard]; null = compact defaults. */
private val LocalKeyHeight = staticCompositionLocalOf<Dp?> { null }
private val LocalKeyFont = staticCompositionLocalOf<TextUnit?> { null }

/**
 * A momentary keycap: presses on touch-down and releases on touch-up, so the
 * emulated key is pressed once per tap, for a fixed repeat-safe span. A plain
 * Box (not a Button) so its own click handling doesn't swallow the gesture.
 */
@Composable
private fun KeyCap(label: String, keyId: Any, modifier: Modifier = Modifier, onDown: () -> List<Int>, onUp: (List<Int>) -> Unit) {
    val compact = compactKeyboard()
    // Split landscape supplies an explicit height/font so keys fill the tall flank.
    val h = LocalKeyHeight.current ?: if (compact) 28.dp else 46.dp
    val fsize = LocalKeyFont.current ?: if (compact) 11.sp else 13.sp
    // Timestamp of the last accepted press, for contact-jitter debounce. A plain
    // holder (not Compose state) so updating it doesn't trigger recomposition.
    val lastDownMs = remember { longArrayOf(0L) }
    // The press handlers are read live (rememberUpdatedState) because pointerInput
    // is keyed on the stable [keyId], not the (changing) label -- so the gesture
    // coroutine survives a label change mid-press.
    val downHandler by rememberUpdatedState(onDown)
    val upHandler by rememberUpdatedState(onUp)
    // KeyCap uses pointerInput (a one-tap press/release), which isn't focusable on
    // its own, so on a TV the D-pad couldn't reach it. Make it focusable, fire the
    // same one-tap on OK (DPAD_CENTER/Enter), and show a bright amber + white-outline
    // highlight so the focused key reads clearly from across the room.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    // Read live (the gesture's pointerInput is keyed on the stable keyId and won't
    // restart), so toggling haptics takes effect without re-showing the keyboard.
    val haptic by rememberUpdatedState(LocalKeyHaptic.current)
    Box(
        modifier = modifier
            .height(h)
            .clip(shape)
            .background(if (focused) FocusAmber else KeyBg)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .focusable(interactionSource = interaction)
            .onKeyEvent { ev ->
                if (ev.key == ComposeKey.DirectionCenter || ev.key == ComposeKey.Enter ||
                    ev.key == ComposeKey.NumPadEnter
                ) {
                    if (ev.type == KeyEventType.KeyUp) { haptic(); upHandler(downHandler()) }
                    true
                } else {
                    false
                }
            }
            // Key on [keyId] (stable per key), NOT [label]: SHIFT/GRAPH/KANA change a
            // key's label, and keying on it would restart pointerInput and cancel the
            // in-flight gesture before touch-up -- leaving the key stuck down (endless
            // auto-repeat, e.g. SHIFT then '!').
            .pointerInput(keyId) {
                detectTapGestures(onPress = {
                    val now = System.currentTimeMillis()
                    if (now - lastDownMs[0] >= KEY_DEBOUNCE_MS) {
                        lastDownMs[0] = now
                        haptic()
                        // Inject press + release back-to-back, regardless of how long the
                        // finger rests on the key. openMSX's (re-enabled) touch-keyboard
                        // release-delay in EventDelay correlates the two and holds the
                        // matrix latched ~2 vertical interrupts -- long enough for the MSX
                        // to register exactly one key-press, then released before the
                        // firmware's auto-repeat onset. (See the EventDelay patch in
                        // tools/openmsx/build-openmsx-core.sh.) This makes one tap exactly
                        // one character on C-BIOS, whose auto-repeat is otherwise too fast
                        // to leave a safe fixed hold window.
                        val chord = downHandler()
                        upHandler(chord)
                    } else {
                        tryAwaitRelease()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Color.Black else KeyText,
            fontSize = fsize,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ModKey(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // A Box (not a Material Button, whose 40dp minimum height blocks the compact
    // TV layout), styled to match KeyCap with the FS-A1 inverted "lit" state.
    // clickable is already D-pad-focusable; add the bright focus highlight too.
    val compact = compactKeyboard()
    val h = LocalKeyHeight.current ?: if (compact) 28.dp else 46.dp
    val fsize = LocalKeyFont.current ?: if (compact) 10.sp else 11.sp
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val haptic = LocalKeyHaptic.current
    val cap = when {
        focused -> FocusAmber
        active -> KeyActiveCap
        else -> KeyBg
    }
    val txt = when {
        focused -> Color.Black
        active -> KeyActiveText
        else -> KeyText
    }
    Box(
        modifier = modifier
            .height(h)
            .clip(shape)
            .background(cap)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = ripple()) { haptic(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = txt,
            fontSize = fsize,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The on-screen keyboard's keys shrink to a compact height on TV and other short
 * screens (e.g. landscape) so it doesn't fill most of the display.
 */
@Composable
private fun compactKeyboard(): Boolean {
    val config = LocalConfiguration.current
    val isTv = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    return isTv || config.screenHeightDp < 480
}
