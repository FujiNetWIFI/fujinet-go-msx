// PHASE 1 STUB host for openMSX.
//
// openMSX is not yet staged/cross-compiled (that is Phase 2) or wired to the
// Android sinks (Phase 3). This file implements the full msx_host.h ABI without
// any openMSX/SDL dependency so the app builds, installs and runs end-to-end:
//   * video -- emits a static MSX-blue placeholder frame (VDP border colour 4)
//              with a thin frame, at the standard 272x240 MSX overscan size.
//   * audio -- silence, using the same bounded-blocking ring the real driver
//              will fill from openMSX's Mixer (so session_runtime's audio feeder
//              is exercised unchanged).
//   * input -- key / joystick state is recorded (and logged) so the wiring is
//              proven now; Phase 3 forwards it into openMSX's EventDistributor.
//
// Replacing the body of msxhost_core_* (and feeding g_audio from the Mixer +
// the frame sink from the software renderer) is all Phase 3 needs; this header
// ABI and session_runtime.cpp do not change.

#include "msx_host.h"

#ifdef MSX_HAVE_OPENMSX
// Phase 3: openMSX is staged + linked. These verify the header integration
// (flat includes across all src/ subdirs + the generated config headers) and are
// the surface msx_host drives: boot the Reactor, capture the software-rendered
// frame, tap the Mixer, and inject input via the EventDistributor.
#include "Reactor.hh"
#include "MSXMotherBoard.hh"
#include "MSXEventDistributor.hh"
#include "CommandLineParser.hh"
#include "EventDistributor.hh"
#include "Event.hh"
#include "Display.hh"
#include "RenderSettings.hh"
#include "CommandController.hh"
#include "GlobalCommandController.hh"
#include "Interpreter.hh"
#include "Thread.hh"
#include "MSXException.hh"
#include <SDL.h>
#include <GLES3/gl3.h>
#endif

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

#include <atomic>
#include <span>
#include <thread>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "MsxHost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 1: openMSX renders via GLES2 into SDL's "offscreen" headless EGL pbuffer; the
//    frame is read back (glReadPixels in __wrap_SDL_GL_SwapWindow) and blitted to
//    our SurfaceView by the session. SDL's offscreen driver was patched (see
//    build-openmsx-core.sh) to use the default EGL display when Adreno lacks
//    EGL_EXT_device_enumeration.
// 0: run openMSX headless (the session blits the placeholder frame).
//
// NOTE: a *failed* renderer init must not be followed by powerOn() -- openMSX's
// VDP::reset() then derefs a null renderer and crashes -- so on failure we abort
// the boot rather than degrade.
#ifndef MSX_VIDEO
#define MSX_VIDEO 1
#endif

namespace {

// --- frame sink -------------------------------------------------------------
MsxFrameSink g_frame_sink = nullptr;
void* g_frame_user = nullptr;

// Standard MSX overscan-ish size; the real geometry is reported by openMSX in
// Phase 3 (256x212 active, 272x240 with border). The Compose surface letterboxes
// to 4:3 regardless, so the exact size here only affects the placeholder.
int g_width = 272;
int g_height = 240;
std::vector<uint32_t> g_frame;     // XRGB8888 placeholder (emulator thread only)

#ifdef MSX_HAVE_OPENMSX
// glReadPixels scratch (openMSX thread only): GL gives bottom-up RGBA; we flip to
// top-down and repack to XRGB8888 (0x00RRGGBB) for the frame sink.
std::vector<uint32_t> g_readback;
std::vector<uint32_t> g_capture;
#endif

std::string g_machine = "C-BIOS_MSX2+";

#ifdef MSX_HAVE_OPENMSX
// --- openMSX boot (runs the Reactor on its own thread) ----------------------
// session_runtime sets OPENMSX_SYSTEM_DATA to the bundled share tree (machines
// incl. C-BIOS, the FujiNet extension + fujinet-config.rom) before start.
std::thread g_omsx_thread;
std::atomic<bool> g_omsx_running{false};  // true between powerOn and run() returning
std::atomic<bool> g_omsx_thread_done{true}; // false while openmsx_thread_main runs
openmsx::Reactor* g_reactor = nullptr;   // valid only on the openMSX thread

// Input injection is cross-thread: msxhost_inject_key / msxhost_set_joystick_*
// run on the UI/JNI thread, but the MSX devices read from the per-motherboard
// MSXEventDistributor, which is NOT thread-safe and must be driven on the openMSX
// thread. (We deliberately bypass the global EventDistributor: openMSX's ImGui
// layer sits above the MSX keyboard there and swallows key events when it wants
// the keyboard.) So the UI thread only enqueues openMSX events; drain_pending_events()
// dispatches them on the openMSX thread, once per rendered frame from the
// SDL_GL_SwapWindow hook (MSXEventDistributor::distributeEvent at the motherboard's
// current time). openMSX Event objects just wrap a POD SDL_Event, so building them
// off-thread is safe.
std::mutex g_event_mutex;
std::vector<openmsx::Event> g_event_queue;

// MSX joystick binding ids (must match the Kotlin Msx.JOY_* constants).
enum { MSX_JOY_UP = 0, MSX_JOY_DOWN, MSX_JOY_LEFT, MSX_JOY_RIGHT,
       MSX_JOY_TRIG_A, MSX_JOY_TRIG_B };

void openmsx_thread_main() {
    using namespace openmsx;
    pthread_setname_np(pthread_self(), "msx-openmsx");
    g_omsx_thread_done.store(false);
    // SDL on Android reads env vars via JNI (SDLActivity), not libc, so select
    // drivers via SDL's hint store (no JNI needed). Video: SDL's Android driver
    // renders openMSX (GLES2) directly onto our Compose SurfaceView, whose Surface
    // the Kotlin layer published to SDL (SDLActivity.mExternalSurface); audio uses
    // SDL's Android driver too. Both are made functional by the SDL JNI setup
    // SessionController runs. Set MSX_VIDEO=0 to run headless ("dummy" video).
    SDL_SetHintWithPriority(SDL_HINT_VIDEODRIVER, MSX_VIDEO ? "offscreen" : "dummy", SDL_HINT_OVERRIDE);
    // SDL on Android normally has SDLActivity.nativeRunMain call SDL_SetMainReady;
    // we boot openMSX directly (no SDLActivity), so SDL_InitSubSystem would refuse
    // to run ("Application didn't initialize properly"). Signal readiness here.
    SDL_SetMainReady();
    // The Reactor owns the whole session. Heap-allocate it so a boot exception
    // doesn't run its destructor on a half-constructed object (which crashes in
    // ~Subject<Setting>) and mask the real error: we only destruct it after a
    // *clean* run() exit (cleanRun below), which is what makes stop()/restart work.
    Reactor* reactor = nullptr;
    bool cleanRun = false;
    try {
        Thread::setMainThread();
        reactor = new Reactor();
        g_reactor = reactor;

        // Boot the selected machine with the FujiNet extension inserted; openMSX's
        // FujiNet device then connects out to the in-process runtime on :1985.
        std::vector<std::string> argv = {
            "openmsx", "-machine", g_machine, "-ext", "FujiNet",
        };
        std::vector<char*> cargv;
        cargv.reserve(argv.size());
        for (auto& s : argv) cargv.push_back(s.data());

        CommandLineParser parser(*reactor);
        parser.parse(std::span<char*>(cargv.data(), cargv.size()));
        if (parser.getParseStatus() != CommandLineParser::Status::EXIT) {
            LOGI("boot: runStartupScripts...");
            reactor->runStartupScripts(parser);
            LOGI("boot: runStartupScripts done");

#if MSX_VIDEO
            // Initialise the renderer (mirrors main.cc): create the visible surface
            // + GLES2 context so openMSX draws into the offscreen pbuffer.
            try {
                auto& display = reactor->getDisplay();
                auto& renderSetting = display.getRenderSettings().getRendererSetting();
                if (renderSetting.getEnum() == openmsx::RenderSettings::RendererID::UNINITIALIZED) {
                    renderSetting.setValue(renderSetting.getDefaultValue());
                    reactor->getEventDistributor().deliverEvents();
                }
                LOGI("boot: renderer=%s", std::string(renderSetting.getString()).c_str());
            } catch (FatalError& e) {
                // Must NOT continue to powerOn(): openMSX's VDP::reset() would deref
                // a null renderer and crash. Abort the boot instead.
                LOGE("renderer init failed (%s); aborting boot", e.getMessage().c_str());
                g_omsx_running.store(false);
                return;
            }
#endif

            reactor->powerOn();
            g_omsx_running.store(true);
            LOGI("openMSX booted: machine=%s + FujiNet ext", g_machine.c_str());

            // Bind the two MSX joystick ports to the synthetic "joy1"/"joy2" the
            // host injects (msxhost_set_joystick_*). The default config is empty
            // because no real SDL joystick is present; this gives MSXJoystick a
            // fixed axis/button mapping to match our injected events against.
            // Each direction's value is a Tcl *list of bindings*; a single binding
            // ("joy1 -axis1") has a space, so it must be its own list element --
            // hence the double braces {{...}}. This must never abort the boot.
            try {
                auto& cc = reactor->getCommandController();
                cc.executeCommand(
                    "set msxjoystick1_config {UP {{joy1 -axis1}} DOWN {{joy1 +axis1}} "
                    "LEFT {{joy1 -axis0}} RIGHT {{joy1 +axis0}} A {{joy1 button0}} B {{joy1 button1}}}");
                cc.executeCommand(
                    "set msxjoystick2_config {UP {{joy2 -axis1}} DOWN {{joy2 +axis1}} "
                    "LEFT {{joy2 -axis0}} RIGHT {{joy2 +axis0}} A {{joy2 button0}} B {{joy2 button1}}}");
                LOGI("MSX joystick bindings configured (joy1/joy2)");
            } catch (...) {
                LOGW("joystick config failed (continuing without joystick bindings)");
            }
            reactor->run();   // blocks until a QuitEvent (msxhost_core_stop)
            cleanRun = true;  // run() returned normally -> safe to destruct
        }
    } catch (FatalError& e) {
        LOGE("openMSX FatalError: %s", e.getMessage().c_str());
    } catch (MSXException& e) {
        LOGE("openMSX boot failed: %s", e.getMessage().c_str());
    } catch (std::exception& e) {
        LOGE("openMSX boot failed: %s", e.what());
    }
    g_omsx_running.store(false);
    g_reactor = nullptr;          // no more inject/drain access past this point
    {   // drop any input queued but never dispatched, so it can't leak into a restart
        std::lock_guard<std::mutex> lk(g_event_mutex);
        g_event_queue.clear();
    }
    if (cleanRun && reactor) {
        delete reactor;           // tears down SDL video/audio for a fresh restart
    }
    LOGI("openMSX thread exited (clean=%d)", cleanRun ? 1 : 0);
    g_omsx_thread_done.store(true);
}
#endif // MSX_HAVE_OPENMSX

// --- audio ring (interleaved stereo int16 @ 44100) --------------------------
// Same contract as the other targets: the emulator thread is the producer (here:
// silence), the Kotlin audio feeder is the consumer pulling fixed full blocks.
std::mutex g_audio_mutex;
std::condition_variable g_audio_cv;
std::vector<int16_t> g_audio;
bool g_audio_active = true;
[[maybe_unused]] constexpr size_t kAudioCapSamples = (44100 / 4) * 2;  // ~250ms cap (Phase 3 producer)

// --- input state (recorded; consumed by openMSX in Phase 3) -----------------
constexpr int kMaxPorts = 2;
constexpr int kJoypadIds = 16;
int16_t g_buttons[kMaxPorts][kJoypadIds] = {};
int16_t g_axes[kMaxPorts][4] = {};

void render_placeholder() {
    const size_t pixels = static_cast<size_t>(g_width) * g_height;
    if (g_frame.size() != pixels) g_frame.resize(pixels);
    // MSX palette colour 4 (medium blue) field with a lighter border, XRGB8888.
    constexpr uint32_t kField  = 0x00'3B'3B'CF;  // medium blue
    constexpr uint32_t kBorder = 0x00'7F'7F'EF;  // light blue
    for (int y = 0; y < g_height; ++y) {
        for (int x = 0; x < g_width; ++x) {
            const bool edge = (x < 8 || x >= g_width - 8 || y < 8 || y >= g_height - 8);
            g_frame[static_cast<size_t>(y) * g_width + x] = edge ? kBorder : kField;
        }
    }
}

}  // namespace

#ifdef MSX_HAVE_OPENMSX
// Linker-wrapped (CMakeLists: -Wl,--wrap,SDL_GL_SwapWindow). openMSX calls
// SDL_GL_SwapWindow once per rendered frame; with the offscreen pbuffer there is
// nothing to present, so we read the GLES framebuffer back and push it to the
// frame sink (the session blits it to the SurfaceView). Runs on the openMSX
// thread with the GL context current.
// Dispatch queued input events into the active MSX. Runs on the openMSX thread
// (from the swap hook), so MSXEventDistributor::distributeEvent is safe here.
static void drain_pending_events() {
    if (!g_reactor) return;
    std::vector<openmsx::Event> pending;
    {
        std::lock_guard<std::mutex> lk(g_event_mutex);
        if (g_event_queue.empty()) return;
        pending.swap(g_event_queue);
    }
    openmsx::MSXMotherBoard* board = g_reactor->getMotherBoard();
    if (!board) return;
    auto& dist = board->getMSXEventDistributor();
    auto now = board->getCurrentTime();
    for (auto& e : pending) {
        dist.distributeEvent(e, now);
    }
}

extern "C" void __wrap_SDL_GL_SwapWindow(SDL_Window* window) {
    drain_pending_events();
    if (!g_frame_sink || !window) return;
    int w = 0, h = 0;
    SDL_GL_GetDrawableSize(window, &w, &h);
    if (w <= 0 || h <= 0) return;
    const size_t n = static_cast<size_t>(w) * h;
    if (g_readback.size() != n) g_readback.resize(n);
    if (g_capture.size() != n) g_capture.resize(n);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, g_readback.data());
    // GL origin is bottom-left: flip vertically, repack RGBA (little-endian
    // 0xAABBGGRR) -> XRGB8888 (0x00RRGGBB) for the frame sink.
    for (int y = 0; y < h; ++y) {
        const uint32_t* src = g_readback.data() + static_cast<size_t>(h - 1 - y) * w;
        uint32_t* dst = g_capture.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            const uint32_t p = src[x];
            const uint32_t r = p & 0xFFu, g = (p >> 8) & 0xFFu, b = (p >> 16) & 0xFFu;
            dst[x] = (r << 16) | (g << 8) | b;
        }
    }
    g_frame_sink(g_capture.data(), w, h, g_frame_user);
}
#endif

// --- C API ------------------------------------------------------------------
extern "C" {

void msxhost_set_frame_sink(MsxFrameSink sink, void* user) {
    g_frame_sink = sink;
    g_frame_user = user;
}

void msxhost_select_machine(const char* machine_id) {
    if (machine_id && *machine_id) g_machine = machine_id;
    LOGI("machine selected: %s", g_machine.c_str());
}

bool msxhost_core_start(void) {
#if !defined(MSX_HAVE_OPENMSX) || !MSX_VIDEO
    // Headless/stub: show the placeholder via the frame sink (session blit). With
    // MSX_VIDEO, SDL owns the Surface and presents openMSX directly -- the session
    // must not also blit, so no placeholder here.
    render_placeholder();
    if (g_frame_sink && !g_frame.empty()) {
        g_frame_sink(g_frame.data(), g_width, g_height, g_frame_user);
    }
#endif
#ifdef MSX_HAVE_OPENMSX
    g_omsx_thread = std::thread(openmsx_thread_main);
    LOGI("openMSX core starting (machine=%s); frames captured via glReadPixels in "
         "the SDL_GL_SwapWindow hook", g_machine.c_str());
#else
    LOGI("PHASE 1 stub core started: %dx%d, machine=%s (openMSX not linked)",
         g_width, g_height, g_machine.c_str());
#endif
    return true;
}

void msxhost_core_run_frame(void) {
#ifndef MSX_HAVE_OPENMSX
    // Stub: re-emit the placeholder each frame.
    if (g_frame_sink && !g_frame.empty()) {
        g_frame_sink(g_frame.data(), g_width, g_height, g_frame_user);
    }
#endif
    // With openMSX, frames are pushed from __wrap_SDL_GL_SwapWindow on the openMSX
    // thread; nothing to do on the session's pacing thread.
}

void msxhost_core_stop(void) {
#ifdef MSX_HAVE_OPENMSX
    // Clean cross-thread shutdown: unblock any audio waiter, ask the Reactor to
    // quit (thread-safe via the global EventDistributor -> Reactor sets running=
    // false -> run() returns -> the thread destructs the Reactor), then join so a
    // subsequent start() boots a fresh machine. Bounded so a wedged core can't
    // hang the caller; fall back to detach in that (unexpected) case.
    msxhost_audio_set_active(0);
    if (g_reactor && g_omsx_running.load()) {
        g_reactor->getEventDistributor().distributeEvent(openmsx::QuitEvent());
    }
    if (g_omsx_thread.joinable()) {
        for (int i = 0; i < 300 && !g_omsx_thread_done.load(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        if (g_omsx_thread_done.load()) g_omsx_thread.join();
        else { LOGW("openMSX thread did not exit in 3s; detaching"); g_omsx_thread.detach(); }
    }
    g_omsx_running.store(false);
#endif
    msxhost_clear_audio();
}

void msxhost_core_reset(void) { LOGI("reset requested (stub)"); }

void msxhost_get_geometry(int* width, int* height) {
    if (width) *width = g_width;
    if (height) *height = g_height;
}

int msxhost_fill_audio(int16_t* out, int maxSamples) {
    if (!out || maxSamples <= 0) return 0;
    const size_t want = static_cast<size_t>(maxSamples);
    std::unique_lock<std::mutex> lock(g_audio_mutex);
    const auto budget = std::chrono::microseconds(
        (want * 1'000'000ULL) / (44100ULL * 2ULL) + 8'000ULL);
    g_audio_cv.wait_for(lock, budget, [&] {
        return !g_audio_active || g_audio.size() >= want;
    });
    const size_t have = std::min(g_audio.size(), want);
    if (have > 0) {
        std::memcpy(out, g_audio.data(), have * sizeof(int16_t));
        g_audio.erase(g_audio.begin(), g_audio.begin() + have);
    }
    if (have < want) {
        std::memset(out + have, 0, (want - have) * sizeof(int16_t));
    }
    return static_cast<int>(want);
}

void msxhost_audio_set_active(int active) {
    {
        std::lock_guard<std::mutex> lock(g_audio_mutex);
        g_audio_active = active != 0;
    }
    g_audio_cv.notify_all();
}

void msxhost_clear_audio(void) {
    {
        std::lock_guard<std::mutex> lock(g_audio_mutex);
        g_audio.clear();
    }
    g_audio_cv.notify_all();
}

void msxhost_inject_key(int down, unsigned keycode, uint32_t character, uint16_t mods) {
#ifdef MSX_HAVE_OPENMSX
    // Called from the Kotlin/JNI (UI) thread. Build the SDL keyboard event openMSX
    // expects -- keysym (matrix position), modifiers, and the unicode/char
    // (KeyEvent::getUnicode reads keysym.unused) -- and enqueue it. The openMSX
    // thread drains it (drain_pending_events) and injects into the MSX Keyboard.
    if (!g_omsx_running.load()) return;
    SDL_Event ev;
    ev.key = SDL_KeyboardEvent{};
    ev.key.type = down ? SDL_KEYDOWN : SDL_KEYUP;
    ev.key.timestamp = SDL_GetTicks();
    ev.key.state = down ? SDL_PRESSED : SDL_RELEASED;
    ev.key.keysym.sym = static_cast<SDL_Keycode>(keycode);
    // A non-UNKNOWN scancode is mandatory: openMSX's Keyboard::processQueuedEvent
    // drops any key event whose scancode == SDL_SCANCODE_UNKNOWN (a Japanese-Kanji
    // workaround) before it ever reaches the matrix. Derive it from the keysym.
    ev.key.keysym.scancode = SDL_GetScancodeFromKey(static_cast<SDL_Keycode>(keycode));
    ev.key.keysym.mod = static_cast<Uint16>(mods);
    ev.key.keysym.unused = character;  // unicode codepoint (0 for non-text keys)
    {
        std::lock_guard<std::mutex> lk(g_event_mutex);
        if (down) g_event_queue.emplace_back(openmsx::KeyDownEvent(ev));
        else      g_event_queue.emplace_back(openmsx::KeyUpEvent(ev));
    }
#else
    LOGI("key %s code=%u char=%u mods=%u (stub)",
         down ? "down" : "up", keycode, character, mods);
#endif
}

#ifdef MSX_HAVE_OPENMSX
// Enqueue a synthetic SDL joystick axis motion for openMSX's MSXJoystick. The
// joystick id ('which') is the openMSX JoystickId: port 0 -> "joy1", 1 -> "joy2".
// msxhost_select_joysticks() configures the MSX joystick bindings to match these.
static void enqueue_joy_axis(int port, uint8_t axis, int16_t value) {
    SDL_Event ev;
    ev.jaxis = SDL_JoyAxisEvent{};
    ev.jaxis.type = SDL_JOYAXISMOTION;
    ev.jaxis.timestamp = SDL_GetTicks();
    ev.jaxis.which = static_cast<SDL_JoystickID>(port);
    ev.jaxis.axis = axis;
    ev.jaxis.value = value;
    std::lock_guard<std::mutex> lk(g_event_mutex);
    g_event_queue.emplace_back(openmsx::JoystickAxisMotionEvent(ev));
}

static void enqueue_joy_button(int port, uint8_t button, bool pressed) {
    SDL_Event ev;
    ev.jbutton = SDL_JoyButtonEvent{};
    ev.jbutton.type = pressed ? SDL_JOYBUTTONDOWN : SDL_JOYBUTTONUP;
    ev.jbutton.timestamp = SDL_GetTicks();
    ev.jbutton.which = static_cast<SDL_JoystickID>(port);
    ev.jbutton.button = button;
    ev.jbutton.state = pressed ? SDL_PRESSED : SDL_RELEASED;
    std::lock_guard<std::mutex> lk(g_event_mutex);
    if (pressed) g_event_queue.emplace_back(openmsx::JoystickButtonDownEvent(ev));
    else         g_event_queue.emplace_back(openmsx::JoystickButtonUpEvent(ev));
}
#endif

void msxhost_set_joystick_button(int port, int id, int pressed) {
    if (port >= 0 && port < kMaxPorts && id >= 0 && id < kJoypadIds) {
        g_buttons[port][id] = pressed ? 1 : 0;
    }
#ifdef MSX_HAVE_OPENMSX
    if (!g_omsx_running.load() || port < 0 || port >= kMaxPorts) return;
    // The MSX joystick is digital; directions map to full-throw axis motion (so
    // the binding's dead-zone is comfortably exceeded), fire buttons to buttons.
    constexpr int16_t kFull = 32767;
    switch (id) {
        case MSX_JOY_LEFT:  enqueue_joy_axis(port, 0, pressed ? -kFull : 0); break;
        case MSX_JOY_RIGHT: enqueue_joy_axis(port, 0, pressed ?  kFull : 0); break;
        case MSX_JOY_UP:    enqueue_joy_axis(port, 1, pressed ? -kFull : 0); break;
        case MSX_JOY_DOWN:  enqueue_joy_axis(port, 1, pressed ?  kFull : 0); break;
        case MSX_JOY_TRIG_A: enqueue_joy_button(port, 0, pressed != 0); break;
        case MSX_JOY_TRIG_B: enqueue_joy_button(port, 1, pressed != 0); break;
        default: break;
    }
#endif
}

void msxhost_set_joystick_axis(int port, int axis, int16_t value) {
    if (port >= 0 && port < kMaxPorts && axis >= 0 && axis < 4) {
        g_axes[port][axis] = value;
    }
#ifdef MSX_HAVE_OPENMSX
    if (!g_omsx_running.load() || port < 0 || port >= kMaxPorts) return;
    if (axis == 0 || axis == 1) enqueue_joy_axis(port, static_cast<uint8_t>(axis), value);
#endif
}

}  // extern "C"
