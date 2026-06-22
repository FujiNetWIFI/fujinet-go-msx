# Licensing & Compliance

FujiNet Go MSX is a **copyleft** project. The shipped app is built from original
glue code plus two third-party emulation/runtime components and the C-BIOS system
ROMs. Read this before distributing any build.

## Components and their licenses

### openMSX (the emulator core) — GPLv2-or-later
- openMSX © the openMSX team and contributors.
- License: **GNU GPL v2 or later.**
- Built from the FujiNetWIFI `openMSX` fork (`feat/fujinet` branch), which adds
  the `FujiNet` device (`src/serial/FujiNet.cc`, `FujiBusPacket`) and bundles the
  `FujiNet` extension + `fujinet-config.rom`. openMSX is embedded as static
  archives linked into `libmsxcore.so`.
- The Android build cross-compiles openMSX and its own 3rd-party stack via
  openMSX's Android chain (`platform-android.mk`, `LINK_MODE=3RD_STA_GLES`), plus
  a custom offscreen video/audio/input backend. These modifications are GPL and
  reproducible from `tools/openmsx/build-openmsx-core.sh`.

### FujiNet firmware / fujinet-pc (RS232 target) — GPLv3
- `libfujinet.so` is built from the FujiNet firmware (`fujinet-pc-msx` checkout,
  `FujiNetWIFI/fujinet-firmware`), which is GPLv3. The MSX FujiNet uses the
  RS232 serial bus target (fujinet-pc has no dedicated MSX target).
- The Android build applies source transforms (SHARED library target, an
  in-process entry wrapper, a `reboot()`/`exit()` guard, mbedTLS-for-Android
  wiring, web admin bound to `0.0.0.0:8055`, `[BOIP]` SLIP listener on
  `127.0.0.1:1985`). These modifications are GPLv3 and reproducible from
  `tools/fujinet/build-fujinet.sh`.

### Bundled libraries (pulled in by the FujiNet build)
- **Mbed TLS** — Apache-2.0 (or GPL-2.0); cross-compiled from source.
- **libssh** — LGPL-2.1.
- **libsmb2** — LGPL-2.1.
- **libnfs** — LGPL-2.1.
- **expat** — MIT.
- **cJSON** — MIT.

### Bundled libraries (statically built by the openMSX Android chain)
- **SDL2** — zlib license.
- **Tcl** — TCL/BSD-style license.
- **freetype** — FTL or GPLv2.
- **libpng** — libpng license.
- **libogg / libvorbis / libtheora** — BSD-style (Xiph.Org).
- **zlib** — zlib license.

### C-BIOS system ROMs — freely redistributable
The default BIOS for **MSX / MSX2 / MSX2+** is **C-BIOS** (© BouKiCHi and the
C-BIOS team), bundled from openMSX's `Contrib/cbios`. C-BIOS is **freely
redistributable** (see `Contrib/README.cbios`), so a distributed build needs no
copyrighted firmware to boot.

### User-imported system ROMs — not bundled
Real-machine and **MSX turboR** ROMs are **never** shipped with the app. They are
imported by the end user from their own storage (Settings → System ROMs) into the
app's private storage, and bound to a machine profile at boot. Manufacturer MSX
ROMs are copyrighted firmware; distributing them is the user's responsibility.

## Net effect

A combined, distributed binary is bound by openMSX's **GPLv2-or-later** and
FujiNet's **GPLv3** copyleft — the combined work is distributed under **GPLv3**
(offer corresponding source). C-BIOS is freely redistributable; no copyrighted
MSX firmware is embedded.

The original FujiNet Go MSX glue code (build scripts, `msx_host.cpp`,
`session_runtime.cpp`, `msx_core.cpp`, `fujinet_android.cpp`, the Kotlin app) is
offered under the terms in [LICENSE](./LICENSE), within the GPL obligations of the
combined work.

See [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) for attribution details.
