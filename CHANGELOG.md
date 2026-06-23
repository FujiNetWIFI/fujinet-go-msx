# Changelog

## 0.1.0 (in progress)

First scaffold of FujiNet Go MSX: the openMSX emulator and the FujiNet firmware
(MSX PC target) fused into one Android app, in the spirit of FujiNet Go 800,
Apple2, Adam and CoCo. Delivered in milestones — Phase 1 (this entry) is the
complete app scaffold with the openMSX core stubbed; Phases 2–4 cross-compile
openMSX, bring up the real offscreen backend, and validate the FujiNet
end-to-end path.

### App shell (Jetpack Compose)
- Package `online.fujinet.go.msx`; launcher icon + UI accent are the MSX-blue
  (`#0000FF`) from the provided adaptive icon set.
- Emulator surface (4:3 letterboxed), control bar (keyboard / joystick / reset /
  FujiNet / settings / power), foreground service owning the session.
- On-screen **MSX2 keyboard** modeled on the **Panasonic FS-A1** (F1–F5, STOP,
  SELECT, GRAPH, CODE, CAP, cursor diamond), emitting SDL keysyms; a hardware
  keyboard router (`MsxKeyMapper`); a touch joystick + hardware-gamepad mapper
  driving the two MSX general-purpose joystick ports (8-way + 2 fire buttons).
- **FujiNet** tab opens the device web UI at `http://127.0.0.1:8055/`.

### System selection, C-BIOS and profiles
- Settings screen to pick the system type (**MSX / MSX2 / MSX2+ / MSX turboR**),
  switch between named **machine profiles**, toggle booting imported ROMs, and
  **import system ROMs** via the Storage Access Framework.
- Boots **C-BIOS** (bundled from openMSX `Contrib/cbios`) by default; the active
  profile's openMSX machine id is written to `machine.id` and booted by the core.

### Native (`libmsxcore.so`)
- `msx_host.{h,cpp}` — the openMSX Android host ABI (frame sink, audio fill,
  key/joystick injection, machine selection). Phase 1 ships a self-contained
  **stub** (MSX-blue placeholder frame + silence) so the app builds and runs;
  the real offscreen openMSX driver replaces the stub body in Phase 3.
- `session_runtime.{h,cpp}` + `msx_core.cpp` — emulator/render/audio threads,
  `ANativeWindow` presentation (XRGB→RGBA), the JNI surface, and the in-process
  FujiNet runtime joined over FujiBusPacket-over-SLIP on loopback TCP 1985 (the
  FujiNet runtime listens; openMSX's `FujiNet` device connects in).

### Build pipeline
- `tools/openmsx/build-openmsx-core.sh` stages openMSX from the local
  `feat/fujinet` checkout, bundles the runtime share tree (C-BIOS machines, the
  `FujiNet` extension + `fujinet-config.rom`), and cross-compiles openMSX + its
  3rd-party stack per Android ABI (gated behind `OPENMSX_DO_NATIVE_BUILD=1`).
- `tools/fujinet/build-fujinet.sh` builds the FujiNet **MSX** runtime to
  `libfujinet.so` from the local `fujinet-pc-msx` checkout, with the shared
  Android transforms, web admin on `0.0.0.0:8055`, and `[BOIP]` SLIP listener on
  `127.0.0.1:1985`.

### openMSX cross-compile + embedding (Phases 2-3 foundation, arm64-v8a)
- `tools/openmsx/build-openmsx-core.sh` now cross-compiles openMSX for Android.
  Several upstream Android gaps were closed via idempotent staged-source patches:
  pkg-config's bundled glib pinned to `-std=gnu11` (host gcc C23), the unfinished
  `#if PLATFORM_ANDROID` code forced off (we drive openMSX via our own backend),
  `std::aligned_alloc` -> `posix_memalign` (API < 28), and a `GL/glew.h` -> GLES3
  shim (openMSX's GL OSD/GUI is non-optional; we render via the software
  rasterizer). Result: the full 3rd-party stack (SDL2, SDL2_ttf, Tcl, freetype,
  libpng, ogg/vorbis/theora, zlib) + all 570 openMSX objects compile, and
  `openmsx.so` links.
- `libopenmsx.a` + the 3rd-party archives are linked into `libmsxcore.so` (CMake
  `MSX_HAVE_OPENMSX` path; C++23, all 32 src include dirs, generated config
  headers).
- `msx_host.cpp` boots openMSX headless on its own thread (`Reactor` +
  `-machine <C-BIOS/profile>` + `-ext FujiNet` + `powerOn` + `run`), pointed at
  the bundled share tree via `OPENMSX_SYSTEM_DATA`. openMSX symbols are embedded
  (libmsxcore.so ~34 MB). Offscreen video capture, the Mixer audio tap, and
  EventDistributor input injection are the next increment (need on-device bring-up).

### On-device bring-up (arm64-v8a, validated on hardware)
- **openMSX boots and runs on-device**: C-BIOS MSX2 + the FujiNet extension,
  driven headless on its own thread from `msx_host.cpp`.
- **FujiNet end-to-end is live**: the RS232/Becker runtime listens on
  `127.0.0.1:1985` (openMSX's FujiNet device connects in) and serves the web UI
  on `:8055`.
- **Audio works**: SDL2-on-Android needs its `org.libsdl.app.*` Java companion
  classes + `SDL.setupJNI()`; without them `JNI_OnLoad` aborts (FindClass) and
  the SDL audio thread aborts (NULL jclass). Both are now wired (the classes are
  vendored by `build-openmsx-core.sh`, the setup runs in `SessionController`), so
  openMSX sound plays through SDL's Android AudioTrack.
- Misc device fixes: `SDL_SetMainReady()` (no SDLActivity), the openMSX share
  tree is bundled in full (`init.tcl` + `scripts/`), `RuntimeInstaller` keys its
  completeness check on `init.tcl`.
- **Video works**: openMSX renders the MSX display on-device (the FujiNet CONFIG
  screen, with a live host list, confirmed on hardware). Path: openMSX's GL
  renderer (SDLGL-PP) draws GLES2 into SDL's headless **offscreen** EGL pbuffer;
  `__wrap_SDL_GL_SwapWindow` reads the frame back with `glReadPixels` and the
  session blits it to the Compose `SurfaceView`. Getting there took three fixes:
  - openMSX hardcoded `OPENGL_VERSION=OPENGL_2_1` (desktop GL), so it asked SDL
    for `libGL.so` (absent on Android). Forced `OPENGL_ES_2_0`.
  - SDL's offscreen driver required `EGL_EXT_device_enumeration`
    (`eglQueryDevicesEXT`), missing on Adreno. Patched SDL to fall back to
    `eglGetDisplay(EGL_DEFAULT_DISPLAY)` (`tools/openmsx/patches/SDL2-2.30.7.diff`,
    applied by openMSX's 3rd-party chain).
  - A *failed* renderer init must not reach `powerOn()` (openMSX's `VDP::reset()`
    derefs a null renderer); on failure the boot now aborts cleanly.
  - (The android-direct path -- `SDLActivity.mExternalSurface` +
    `nativeSetScreenResolution`/`onNativeResize`/`onNativeSurfaceChanged` -- was
    also explored; it needs more SDLActivity lifecycle, so we use offscreen.)
- **Keyboard input works**: the on-screen MSX keyboard drives the emulated MSX
  end-to-end (typing `c` opens the FujiNet **config** screen, confirmed on
  hardware). Input is injected on the UI thread into a lock-guarded queue and
  dispatched on the openMSX thread once per frame (from the `SDL_GL_SwapWindow`
  hook) straight into the motherboard's `MSXEventDistributor` -- the same endpoint
  the built-in `type` command uses. Two non-obvious openMSX gotchas were the
  blockers:
  - The global `EventDistributor` routes through openMSX's **ImGui** layer
    (`Priority::IMGUI`), which swallows key events above the MSX keyboard. We
    bypass it by distributing into the per-motherboard `MSXEventDistributor`
    directly (on the emulator thread, where it is safe).
  - `Keyboard::processQueuedEvent` **silently drops** any key whose
    `scancode == SDL_SCANCODE_UNKNOWN` (a Japanese-Kanji workaround). Injected
    events must carry a real scancode, so we derive it with
    `SDL_GetScancodeFromKey`.
- **Joystick wired**: the two MSX general-purpose joystick ports (auto-plugged
  `msxjoystick1`/`2`) are bound at boot to synthetic `joy1`/`joy2` axis+button
  inputs (the default binding is empty without a real SDL joystick), and the
  touch stick / fire buttons inject `JoystickAxisMotion`/`JoystickButton` events
  through the same queue. Exercised on device without crashing; full play-test
  awaits joystick-reading software loaded over FujiNet.
- **Clean stop / restart** (machine-profile switching), verified on hardware:
  `stop()` posts a `QuitEvent` to the Reactor (thread-safe), so `run()` returns,
  the openMSX thread destructs the Reactor (only after a *clean* exit, so a boot
  exception still can't trigger the `~Subject` crash), and the host joins it
  (bounded, with a detach fallback). `restart()` runs off the UI thread and does
  stop -> start, which fully tears down + re-binds the FujiNet runtime (SLIP
  :1985) and re-boots openMSX on the newly selected machine, all in-process. A
  round-trip (MSX2 -> MSX1 -> MSX2) was confirmed on device (`openMSX thread
  exited (clean=1)` -> `Session stopped` -> `Session started` -> `openMSX
  booted`, same pid throughout).
- Settings fix: the "Apply & Restart" / "Close" bottom bar now applies
  `navigationBarsPadding()` so it is no longer drawn under the system navigation
  bar (it was unreachable under 3-button nav).
- **Multi-ABI**: openMSX + FujiNet now cross-compile and package for
  **arm64-v8a, armeabi-v7a and x86_64** (the live device + emulator matrix).
  `x86` (32-bit) is intentionally excluded -- openMSX's `libvorbis` dependency
  does not build for it under clang (`-mno-ieee-fp`), and no live x86-32 Android
  devices exist. Two build-script bugs were fixed along the way: the openMSX
  stage step's `rsync --delete` was wiping already-built ABIs' `install/include`
  trees (now excluded), and `-PmsxAbi` now accepts a comma-separated ABI list.
- **BoIP port 1985 -> 1986** (both sides), so a co-installed FujiNet Go target
  (e.g. Apple2, which uses 1985) doesn't clash on the same device. Changed
  openMSX's FujiNet device (`FUJINET_DEFAULT_PORT`) and `kSlipPort`; the
  FujiNet-PC side needed `CONFIG_DEFAULT_BOIP_PORT` itself moved to 1986
  (build-fujinet.sh transform): fujinet-pc only *persists* the `[BOIP]` port when
  it differs from that default, so `fnconfig.ini` keeps an empty `port=` and the
  listener always falls back to the compiled-in default -- which was 1985 (the
  "Apple dev relay" port). Setting `fnconfig.ini` alone was not enough; the
  `listenPort` runtime arg is also ignored by the entry shim. Verified on
  hardware: libfujinet now listens on `127.0.0.1:1986`, the openMSX FujiNet
  device connects (ESTABLISHED socket), and the config's host list populates.
- **FS-A1 keyboard overhaul**:
  - **Look**: amber-orange legends on charcoal keycaps (was blue/white).
  - **Inject through EventDelay (the real fix for the double-keypress bugs)**:
    keys/joystick are forwarded through openMSX's *global* EventDistributor ->
    EventDelay -> device -- the exact path desktop openMSX uses for SDL input --
    instead of being injected straight into the per-motherboard
    MSXEventDistributor. The bypass skipped EventDelay's emulation-time
    scheduling, which made a held key get read twice across FujiNet-config screen
    transitions: ENTER both confirming a host-slot edit *and* opening the slot,
    plus occasional double letters / backspaces. Same symptom never occurred with
    the same config ROM on desktop openMSX over BoIP, which pointed straight at
    the injection path. (The original reason for bypassing -- ImGui "eating" keys
    -- was really the SDL_SCANCODE_UNKNOWN drop, since fixed.)
  - **Stable gesture key (fixes SHIFT-then-symbol repeating endlessly)**: each
    keycap's `pointerInput` is keyed on a *stable* id, not its label. SHIFT/GRAPH/
    KANA change a key's label, and Compose restarts `pointerInput` when its key
    changes -- cancelling the in-flight gesture before touch-up, so the keycap's
    release never fired and the key stuck down (the firmware then auto-repeated it
    forever, e.g. SHIFT then `!`). The down/up callbacks are held via
    `rememberUpdatedState` so the long-lived handler always calls the current ones.
  - **One tap = one character, via a precise emulated-time matrix hold (the real
    fix for doubled/dropped keys)**: the on-screen keyboard now injects a key press
    and release *back-to-back* (the finger's own dwell time is ignored), and openMSX
    holds the matrix latched for exactly ~2 vertical interrupts of **emulated** time
    before releasing. This re-enables openMSX's own touch-keyboard release-delay
    (`EventDelay`), which lived behind `#if PLATFORM_ANDROID` (forced off here) and
    referenced a refactored-away `TimedEvent`/`Keys::K_MASK` API; it is ported to the
    current event API and made unconditional via an idempotent staged-source patch in
    `tools/openmsx/build-openmsx-core.sh`. Crucially the hold is scheduled in
    *emulated* time (`setSyncPoint(pressTime + 2/50 s)`), **not** the host clock or
    the coarse/irregular RealTime sync cadence, so it is a precise 2 interrupts
    regardless of emulation speed -- long enough for the MSX matrix scan to latch one
    press, short enough to release before the firmware auto-repeats. This matters
    because the bundled **C-BIOS auto-repeats aggressively** (≈ every 78 ms with
    almost no initial delay; a 1 s hold yields ~13 characters), unlike a real MSX
    BIOS's ~0.5 s onset -- so any finger-duration or fixed real-time hold doubles
    unpredictably, whereas the precise 2-interrupt hold is exactly one character
    (verified on device: clean letter runs, and SHIFT+1/SHIFT+2 each produce a single
    `!`/`"`). Trade-off: press-and-hold no longer auto-repeats; tap repeatedly to
    repeat.
  - **Keep emulation at real-time** (`throttle on`, `fullspeedwhenloading off` in
    `msx_host.cpp`): the FujiNet device holds the MSX in a "loading" state while it
    streams, so openMSX would otherwise run the CPU flat-out (emulated time racing
    ahead of wall-clock). Pinning to real-time keeps the machine's speed/audio
    correct. (The keyboard hold above is emulated-time based, so it is robust either
    way; this is general-correctness.)
  - **Contact-jitter debounce**: a real finger can flicker (down -> micro-lift
    -> down), which the tap detector reports as two presses. A second press of
    the same key within 120 ms of the last is ignored -- far sooner than any
    intentional re-tap, so deliberately-spaced double letters still register.
  - **SHIFT layer**: F1-F5 show **F6-F10**, HOME shows **CLS**, and the number /
    symbol legends switch to their shifted faces while SHIFT is lit.
  - **GRAPH / CODE / かな(KANA) / CAP layers**: modifiers are injected into
    openMSX as real matrix key presses around the main key, so the emulated
    firmware produces the authentic graphic / katakana / shifted glyph rather
    than us guessing the codepoint. KANA shows the JIS katakana legends; CAP
    shows upper-case. (Katakana/graphic glyphs render fully on a Japanese machine
    ROM; C-BIOS is roman-only.)
- **Control bar icons + accent** (matching fujinet-go-apple2): the text labels
  (Joy / Reset / FujiNet / Cfg / Power) are now Material icons (Keyboard,
  Gamepad, RestartAlt, Settings, PowerSettingsNew) plus the **FujiNet logo**
  (`fujinet_toolbar.png`, tinted to the accent via `BlendMode.Modulate`); needs
  `material-icons-extended`. The **UI accent** is now the FS-A1 keycap amber-
  orange (`#F2871E`) instead of MSX blue, so the toolbar, the FujiNet logo, and
  Material widgets (radios/buttons) all match the on-screen keyboard.
- **Resync to upstream**: rebuilt the openMSX core from the updated `feat/fujinet`
  checkout (bundling its newer `fujinet-config.rom`) and `libfujinet` from the
  updated `fujinet-pc-msx` (now reports FN VER v1.6.2-dev). build-fujinet.sh gained
  a transform to **skip the PC unit tests on Android cross-builds** -- upstream
  added a `ctest` step that aborts the build because the target-arch test binaries
  can't run on the host ("Exec format error"). All ABIs rebuilt; on-device the new
  config ROM boots, connects over BoIP :1986 (ESTABLISHED), and the keyboard drives
  it (c -> Configuration).

### Verified
- openMSX + FujiNet cross-compile and link for all three packaged ABIs
  (arm64-v8a, armeabi-v7a, x86_64); `:app:assembleDebug` packages
  `libmsxcore.so` + `libfujinet.so` for each.
- The multi-ABI APK installs and runs stably on hardware (Motorola Razr,
  Android 15, arm64-v8a slice): boots C-BIOS MSX2 + the FujiNet extension,
  renders the config screen, the on-screen keyboard drives the MSX, and
  Settings "Apply & Restart" cleanly reboots the core in-process.
