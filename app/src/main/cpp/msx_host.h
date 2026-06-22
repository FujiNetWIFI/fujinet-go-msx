#pragma once

#include <cstdint>

// Android host for openMSX.
//
// libmsxcore.so embeds openMSX (the MSX emulator) built as a library via its own
// Android 3rd-party static chain. Unlike AppleWin/atari800 (library-style cores
// driven one frame per call), openMSX is a full SDL2 application: it owns a
// Reactor event loop and renders through a Display/VisibleSurface. This host
// replaces openMSX's SDL window / audio / input with the Android sinks the
// session runtime already understands:
//   * video  -- the software renderer (SDLRasterizer -> pixel surface) is copied
//               into a tightly packed XRGB8888 buffer and pushed to the frame
//               sink (blitted to an ANativeWindow by session_runtime).
//   * audio  -- openMSX's Mixer output (signed-16 stereo) is drained at 44100Hz
//               by the JNI audio feeder via msxhost_fill_audio.
//   * input  -- msxhost_inject_key / msxhost_set_joystick_* are translated into
//               openMSX EventDistributor events (the same events SDL would post),
//               so the existing MSX Keyboard / JoystickDevice handling is reused.
//
// The openMSX FujiNet device (src/serial/FujiNet.cc) is a socket *client*: it
// connects out to 127.0.0.1:1985 and speaks FujiBusPacket. The in-process
// FujiNet runtime (libfujinet.so) listens there, so the two meet on loopback.
//
// PHASE 1: openMSX is not yet staged/linked; msx_host.cpp ships a self-contained
// stub (an MSX-blue placeholder frame + silence) so the app builds and runs. The
// stub satisfies this whole ABI; Phases 2-3 replace its body with the real
// openMSX driver without changing this header or session_runtime.cpp.

extern "C" {

// One decoded video frame (tightly packed XRGB8888, pitch == width*4). Invoked
// on the emulator thread from within msxhost_core_run_frame().
typedef void (*MsxFrameSink)(const uint32_t* xrgb8888, int width, int height, void* user);
void msxhost_set_frame_sink(MsxFrameSink sink, void* user);

// Select the openMSX machine to boot (e.g. "C-BIOS_MSX2+", a real-machine id, or
// a user-imported-ROM profile id). Must be called before msxhost_core_start; the
// session reads the selection the Kotlin layer wrote into the runtime root.
void msxhost_select_machine(const char* machine_id);

// --- core lifecycle (call on the emulator thread) ---------------------------
// Boots openMSX headless with the selected machine + the FujiNet extension, and
// reads the initial AV geometry. Returns false on failure.
bool msxhost_core_start(void);
void msxhost_core_run_frame(void);   // advance ~one 60Hz frame, emit one frame
void msxhost_core_stop(void);
void msxhost_core_reset(void);       // reset the MSX (front-panel reset)
void msxhost_get_geometry(int* width, int* height);

// --- audio: drained by the JNI audio feeder ---------------------------------
// Blocks (bounded) until maxSamples interleaved stereo signed-16 samples
// (44100 Hz) are available, then copies a full block into out, silence-padding
// the remainder on underrun. Always returns maxSamples so the consumer writes a
// full, real-time-paced AudioTrack block.
int  msxhost_fill_audio(int16_t* out, int maxSamples);
// Toggle audio production drain; set 0 on shutdown to unblock a waiting fill.
void msxhost_audio_set_active(int active);
void msxhost_clear_audio(void);

// --- input (fed from Kotlin via JNI) ----------------------------------------
// Keyboard: Android keycode + character + modifiers, translated to an openMSX
// key event (SDL keysym) for the emulated MSX matrix keyboard.
void msxhost_inject_key(int down, unsigned keycode, uint32_t character, uint16_t mods);
// MSX general-purpose joystick port (0 or 1): digital direction bits + 2 fire
// buttons, expressed as joypad button ids / analog axes per port.
void msxhost_set_joystick_button(int port, int id, int pressed);
void msxhost_set_joystick_axis(int port, int axis, int16_t value);

}  // extern "C"
