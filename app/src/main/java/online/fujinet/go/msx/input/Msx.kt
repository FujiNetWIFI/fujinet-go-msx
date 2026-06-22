package online.fujinet.go.msx.input

/**
 * SDL2 key/modifier constants + MSX joystick ids used to drive openMSX from the
 * Compose UI. openMSX consumes the same SDL_Keyboard / SDL_Joystick events SDL
 * would post, so the on-screen keyboard and the hardware-keyboard router emit
 * SDL_Keycode values and the native host (msx_host, Phase 3) replays them through
 * openMSX's EventDistributor into the emulated MSX matrix keyboard.
 *
 * Non-ASCII keys use SDL2's SDL_SCANCODE_TO_KEYCODE encoding (scancode |
 * 0x40000000). The MSX-specific function keys (GRAPH/CODE/CAPS/STOP/SELECT/DEAD)
 * are mapped here to the host keys openMSX's default unicode keymap binds them
 * to; the exact bindings are finalised against share/keymaps in Phase 3.
 */
object Msx {
    private const val SCANCODE_MASK = 0x40000000
    private fun sc(scancode: Int) = scancode or SCANCODE_MASK

    // --- printable / control (ASCII == SDL_Keycode) -------------------------
    const val K_BACKSPACE = 8
    const val K_TAB = 9
    const val K_RETURN = 13
    const val K_ESCAPE = 27
    const val K_SPACE = 32
    const val K_0 = 48
    const val K_9 = 57
    const val K_a = 97
    const val K_z = 122
    const val K_DELETE = 127

    // --- editing / cursor ---------------------------------------------------
    val K_INSERT = sc(73)     // SDL_SCANCODE_INSERT  (MSX INS)
    val K_HOME = sc(74)       // SDL_SCANCODE_HOME    (MSX HOME/CLS)
    val K_RIGHT = sc(79)
    val K_LEFT = sc(80)
    val K_DOWN = sc(81)
    val K_UP = sc(82)

    // --- function row -------------------------------------------------------
    val K_F1 = sc(58)
    val K_F2 = sc(59)
    val K_F3 = sc(60)
    val K_F4 = sc(61)
    val K_F5 = sc(62)

    // --- MSX special keys (host-key approximations; refined in Phase 3) ------
    val K_CAPS = sc(57)       // SDL_SCANCODE_CAPSLOCK
    val K_GRAPH = sc(226)     // SDL_SCANCODE_LALT
    val K_CODE = sc(230)      // SDL_SCANCODE_RALT
    val K_STOP = sc(72)       // SDL_SCANCODE_PAUSE  (MSX STOP/BREAK)
    val K_SELECT = sc(70)     // SDL_SCANCODE_PRINTSCREEN (MSX SELECT)
    const val K_DEAD = 96     // '`' dead/accent key

    val K_LSHIFT = sc(225)
    val K_LCTRL = sc(224)

    // --- SDL key modifiers (KMOD_*) -----------------------------------------
    const val MOD_NONE = 0
    const val MOD_SHIFT = 0x0001   // KMOD_LSHIFT
    const val MOD_CTRL = 0x0040    // KMOD_LCTRL
    const val MOD_GRAPH = 0x0100   // KMOD_LALT (GRAPH)
    const val MOD_CODE = 0x0200    // KMOD_RALT (CODE)

    // --- MSX general-purpose joystick (per port, mapped to JoystickDevice bits
    // by msx_host in Phase 3). Direction ids 0..3, two fire buttons 4..5. -----
    const val JOY_UP = 0
    const val JOY_DOWN = 1
    const val JOY_LEFT = 2
    const val JOY_RIGHT = 3
    const val JOY_TRIG_A = 4
    const val JOY_TRIG_B = 5

    // Analog axis indices (for the touch stick), value -32768..32767.
    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_MAX = 32767
}
