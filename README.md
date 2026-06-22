# FujiNet Go MSX

Android MSX emulation with integrated FujiNet, in the spirit of
[FujiNet Go 800](https://github.com/mozzwald/fujinet-go-800) (Atari 8-bit),
FujiNet Go Apple2 (Apple ][), FujiNet Go Adam (Coleco ADAM) and FujiNet Go CoCo
(TRS-80 Color Computer).

This repository fuses two desktop programs into one cohesive mobile app:

- **openMSX** — the MSX emulator (FujiNetWIFI `feat/fujinet` fork, which adds the
  `FujiNet` extension + `fujinet-config.rom`). It is embedded as a native library
  and driven frame-by-frame into an Android `Surface` through a custom offscreen
  video/audio/input backend.
- **fujinet-pc (RS232 target)** — the FujiNet firmware/PC port built as
  `libfujinet.so` and run in-process as a background runtime. The MSX FujiNet
  rides a serial/RS232-style bus, so the build uses fujinet-pc's `RS232` target
  (from the `fujinet-pc-msx` checkout); over Bus-over-IP its Becker socket
  listens on the loopback endpoint openMSX connects to.

The two halves talk over **FujiBusPacket-over-SLIP on loopback TCP 1985**.
Direction is the mirror of the SmartPort targets: the FujiNet runtime runs the
SLIP **listener**, and openMSX's `FujiNet` device (`src/serial/FujiNet.cc`) is
the **client** that connects out to `127.0.0.1:1985`. To the user it is
transparent — boot the MSX and the FujiNet CONFIG cartridge is just there.

## System selection, C-BIOS and profiles

- Boots **C-BIOS** (the freely-redistributable BIOS, bundled from openMSX's
  `Contrib/cbios`) for **MSX / MSX2 / MSX2+** out of the box — no copyrighted
  ROMs required.
- **Import your own system ROMs** (Storage Access Framework) to run real
  machines, including **MSX turboR** (which C-BIOS does not cover).
- **Machine profiles** let you name and switch between configurations (system
  type + C-BIOS vs imported ROMs). The active profile's openMSX machine id is
  written to `machine.id` and booted on the next (re)start.

See the **Cfg** button in the control bar.

## Architecture

| Concern | Component |
|---|---|
| Emulator core | openMSX, driven one frame per `msxhost_core_run_frame()` on a worker thread |
| App native lib | `libmsxcore.so` (openMSX static archives + Android host + session + JNI) |
| Android host | `app/src/main/cpp/msx_host.cpp` (offscreen openMSX backend: software renderer → Surface, Mixer → AudioTrack, injected keyboard/joystick) |
| FujiNet runtime | `libfujinet.so` (fujinet-pc RS232 target), `dlopen`'d in-process |
| Transport | FujiBusPacket-over-SLIP, TCP 1985 (FujiNet listens, openMSX connects) |
| FujiNet web UI | served on `0.0.0.0:8055`; the **FujiNet** tab opens `http://127.0.0.1:8055/` |
| UI | Jetpack Compose (emulator surface, on-screen MSX2 / FS-A1 keyboard, joystick, settings, FujiNet WebUI) |

> **Phase status.** This is delivered in milestones. The Compose shell, settings/
> profiles/ROM-import, MSX keyboard + joystick, native host glue and both build
> scripts are in place; `msx_host.cpp` currently ships a **Phase 1 stub** (an
> MSX-blue placeholder frame + silence) so the app builds and runs. Phase 2
> cross-compiles openMSX; Phase 3 brings up the real offscreen backend; Phase 4
> validates the FujiNet end-to-end path. See `app/src/main/cpp/msx_host.h`.

## Sources

The native components are built from local checkouts (not pinned GitHub
tarballs), so unpushed changes are used as-is:

- openMSX: `~/Workspace/openMSX` (branch `feat/fujinet`)
- FujiNet: `~/Workspace/fujinet-pc-msx`

Override with `OPENMSX_SRC=` / `FUJINET_SRC=` when running the build scripts.

## Build requirements

- JDK 21+ for the Gradle daemon, Android SDK (compile SDK 36) + an installed NDK
- `bash`, `git`, `python3`, `cmake`, `rsync`
- openMSX's own Android 3rd-party chain downloads + statically builds SDL2, Tcl,
  freetype, libpng, ogg/vorbis/theora and zlib (no host copies needed)
- The FujiNet build also clones and cross-compiles Mbed TLS

`local.properties` records `sdk.dir` and `ndk.dir`.

## Build

```bash
# Kotlin shell + native stub core (Phase 1; no heavy native cross-compile):
./gradlew :app:compileDebugKotlin
./gradlew :app:externalNativeBuildDebug -PmsxAbi=arm64-v8a \
    -x prepareFujiNetRuntime -x prepareOpenMsxCore

# FujiNet runtime (libfujinet.so + assets):
bash tools/fujinet/build-fujinet.sh --abi arm64-v8a

# openMSX core (stage + bundle C-BIOS/FujiNet share; native build gated):
bash tools/openmsx/build-openmsx-core.sh --abi arm64-v8a            # stage + share
OPENMSX_DO_NATIVE_BUILD=1 bash tools/openmsx/build-openmsx-core.sh --abi arm64-v8a

# Full app (all packaged ABIs: arm64-v8a, armeabi-v7a, x86_64 -- x86-32 is
# excluded, see CHANGELOG) once the native builds are wired in:
./gradlew assembleDebug
./gradlew assembleDebug -PmsxAbi=arm64-v8a              # fast single-ABI dev build
./gradlew assembleDebug -PmsxAbi=arm64-v8a,x86_64       # comma list for a subset
```

The application id / package is `online.fujinet.go.msx`.

## Generated (uncommitted) directories

- `app/src/main/cpp-generated/openmsx/` — staged openMSX sources + per-ABI archives
- `app/src/main/assets-generated/` — FujiNet + openMSX runtime assets (C-BIOS, FujiNet extension)
- `app/src/main/jniLibs-generated/` — `libfujinet.so` per ABI
- `tools/openmsx/work/`, `tools/fujinet/work/`

## Licensing

This is a copyleft project — see [COMPLIANCE.md](./COMPLIANCE.md) and
[THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md). **openMSX is GPLv2-or-later**
and FujiNet is GPLv3, so the combined work is distributed under **GPLv3**
([LICENSE](./LICENSE)). C-BIOS is freely redistributable; user-imported system
ROMs are the user's own and are never bundled.
