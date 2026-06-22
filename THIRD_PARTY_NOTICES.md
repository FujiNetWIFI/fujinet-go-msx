# Third-Party Notices

FujiNet Go MSX incorporates the following third-party software. See
[COMPLIANCE.md](./COMPLIANCE.md) for how these licenses interact.

## openMSX — MSX emulator
- Copyright © the openMSX team and contributors.
- License: GNU GPL v2 or later.
- Source: https://github.com/FujiNetWIFI/openMSX (`feat/fujinet` branch).
- Used as the emulator core (`libmsxcore.so`), embedded as static archives.

## FujiNet firmware (fujinet-pc, RS232 target)
- Copyright © The FujiNet project / contributors.
- License: GNU GPL v3.
- Source: https://github.com/FujiNetWIFI/fujinet-firmware
- Used as the in-process FujiNet runtime (`libfujinet.so`).

## C-BIOS
- Copyright © BouKiCHi and the C-BIOS team.
- License: freely redistributable (BSD-like); see `Contrib/README.cbios` in openMSX.
- Bundled as the default MSX / MSX2 / MSX2+ BIOS (from openMSX `Contrib/cbios`).

## SDL2
- Copyright © Sam Lantinga and contributors.
- License: zlib license. (Statically built by the openMSX Android chain.)

## Tcl
- Copyright © the Tcl Core Team and contributors.
- License: TCL license (BSD-style). (Statically built by the openMSX Android chain.)

## FreeType
- Copyright © David Turner, Robert Wilhelm, and Werner Lemberg.
- License: FreeType License (FTL) or GPLv2.

## libpng
- Copyright © the PNG Reference Library Authors.
- License: libpng license.

## libogg / libvorbis / libtheora
- Copyright © the Xiph.Org Foundation.
- License: BSD-style.

## Mbed TLS
- Copyright © The Mbed TLS Contributors.
- License: Apache-2.0.
- Source: https://github.com/Mbed-TLS/mbedtls

## libssh
- Copyright © The libssh contributors.
- License: LGPL-2.1.

## libsmb2
- Copyright © Ronnie Sahlberg and contributors.
- License: LGPL-2.1.

## libnfs
- Copyright © Ronnie Sahlberg and contributors.
- License: LGPL-2.1.

## Expat (libexpat)
- Copyright © The Expat maintainers.
- License: MIT.

## cJSON
- Copyright © Dave Gamble and cJSON contributors.
- License: MIT.

## zlib
- Copyright © Jean-loup Gailly and Mark Adler.
- License: zlib license.

## User-imported MSX system ROMs
- Real-machine / MSX turboR firmware images are copyrighted by their respective
  manufacturers, are **not** bundled, and are supplied by the end user. See the
  ROM note in [COMPLIANCE.md](./COMPLIANCE.md).
