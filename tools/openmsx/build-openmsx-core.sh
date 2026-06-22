#!/usr/bin/env bash
#
# Stage + cross-compile openMSX for the packaged Android ABIs, and bundle its
# runtime share tree, for FujiNet Go MSX.
#
# openMSX is embedded as static archives linked into libmsxcore.so (see
# app/src/main/cpp/CMakeLists.txt). Unlike AppleWin (a CMake project we
# add_subdirectory), openMSX has its own Make build that also downloads + builds
# its 3rd-party stack (SDL2, Tcl, freetype, libpng, ogg/vorbis/theora, zlib)
# statically -- platform-android.mk selects LINK_MODE=3RD_STA_GLES for exactly
# this. So this script drives that native build per ABI.
#
# Outputs (consumed by CMakeLists.txt + RuntimeInstaller):
#   app/src/main/cpp-generated/openmsx/                 staged openMSX source
#   app/src/main/cpp-generated/openmsx/.source-info     branch/commit stamp
#   app/src/main/cpp-generated/openmsx/install/<abi>/   libopenmsx.a + 3rd-party
#                                                       .a archives + headers
#   app/src/main/assets-generated/openmsx/              runtime share tree:
#       machines/ (C-BIOS MSX1/MSX2/MSX2+ from Contrib/cbios + roms),
#       extensions/ (incl. FujiNet.xml + fujinet-config.rom),
#       systemroms/, keymaps/, unicodemaps/
#
# PHASE BOUNDARY: the staging + share bundling below run today. The per-ABI
# native build is the heavy Phase 2 step; the offscreen video/audio/input backend
# (a custom openMSX VideoSystem) is injected into the staged source in Phase 3.
# Each is guarded so a not-yet-ready environment fails loudly with guidance.

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
PROJECT_ROOT=$(cd -- "${SCRIPT_DIR}/../.." &>/dev/null && pwd)
STAGE_DIR="${PROJECT_ROOT}/app/src/main/cpp-generated/openmsx"
INSTALL_ROOT="${STAGE_DIR}/install"
SHARE_OUT="${PROJECT_ROOT}/app/src/main/assets-generated/openmsx"

# Local openMSX checkout (feat/fujinet) -- override with OPENMSX_SRC=/path ...
OPENMSX_SRC="${OPENMSX_SRC:-${HOME}/Workspace/openMSX}"

# x86 (32-bit) is excluded by default: openMSX's libvorbis dependency does not
# cross-compile for it under clang ('-mno-ieee-fp'), and no live x86-32 Android
# devices exist (x86_64 covers emulators). Pass '--abi x86' explicitly to attempt
# it (needs a libvorbis configure patch first).
DEFAULT_ANDROID_ABIS=("armeabi-v7a" "arm64-v8a" "x86_64")
ANDROID_API=21

REQUESTED_ABIS=()
BUILD_ALL_ABIS=0
REFRESH=0

fail() { echo "build-openmsx-core.sh: $*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Usage: bash tools/openmsx/build-openmsx-core.sh (--all-abis | --abi <abi> ...) [--refresh]

Stages + cross-compiles openMSX for the packaged Android ABIs from the local
openMSX checkout (OPENMSX_SRC, default ~/Workspace/openMSX).
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --all-abis) BUILD_ALL_ABIS=1 ;;
        --abi) shift; [[ $# -gt 0 ]] || fail "--abi needs a value"; REQUESTED_ABIS+=("$1") ;;
        --refresh) REFRESH=1 ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown argument: $1" ;;
    esac
    shift
done

resolve_abis() {
    if [[ "${BUILD_ALL_ABIS}" -eq 1 ]]; then
        printf '%s\n' "${DEFAULT_ANDROID_ABIS[@]}"; return
    fi
    [[ "${#REQUESTED_ABIS[@]}" -gt 0 ]] || fail "Specify --all-abis or at least one --abi"
    printf '%s\n' "${REQUESTED_ABIS[@]}"
}

# ABI -> (openMSX OPENMSX_TARGET_CPU, clang triple prefix used by the NDK).
abi_cpu()    { case "$1" in armeabi-v7a) echo arm ;; arm64-v8a) echo aarch64 ;; x86) echo x86 ;; x86_64) echo x86_64 ;; *) fail "Unsupported ABI: $1" ;; esac; }
abi_triple() { case "$1" in armeabi-v7a) echo armv7a-linux-androideabi ;; arm64-v8a) echo aarch64-linux-android ;; x86) echo i686-linux-android ;; x86_64) echo x86_64-linux-android ;; *) fail "Unsupported ABI: $1" ;; esac; }

read_ndk_dir() {
    if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then printf '%s\n' "${ANDROID_NDK_ROOT}"; return; fi
    if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then printf '%s\n' "${ANDROID_NDK_HOME}"; return; fi
    local props="${PROJECT_ROOT}/local.properties"
    if [[ -f "${props}" ]]; then
        local v; v=$(grep -E '^ndk\.dir=' "${props}" | head -1 | cut -d= -f2-)
        [[ -n "${v}" ]] && { printf '%s\n' "${v}"; return; }
    fi
    [[ -d /opt/android-ndk ]] && { printf '%s\n' /opt/android-ndk; return; }
    fail "Android NDK not found (set ANDROID_NDK_ROOT or ndk.dir in local.properties)"
}

stage_source() {
    if [[ "${REFRESH}" -eq 1 ]]; then rm -rf "${STAGE_DIR}"; fi
    [[ -d "${OPENMSX_SRC}/src" ]] || fail "openMSX source not found at ${OPENMSX_SRC} (set OPENMSX_SRC)"
    mkdir -p "${STAGE_DIR}"
    echo "Staging openMSX source -> ${STAGE_DIR}"
    # Exclude our own build outputs from the --delete sweep: 'derived' (per-ABI
    # object/3rdparty trees) and 'install' (per-ABI archives + headers). Without
    # excluding 'install', re-staging for one ABI deletes the headers of every
    # already-built ABI (the *.a archives happen to survive via the '*.a' exclude,
    # but the install/<abi>/include trees would be wiped, breaking their builds).
    rsync -a --delete \
        --exclude='.git' --exclude='derived' --exclude='install' \
        --exclude='*.o' --exclude='*.a' \
        "${OPENMSX_SRC}/" "${STAGE_DIR}/"

    local branch commit
    branch=$(git -C "${OPENMSX_SRC}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)
    commit=$(git -C "${OPENMSX_SRC}" rev-parse HEAD 2>/dev/null || echo unknown)
    printf 'source_branch=%s\nsource_commit=%s\n' "${branch}" "${commit}" > "${STAGE_DIR}/.source-info"
}

patch_staged_source() {
    # Idempotent edits to the staged openMSX tree so its Android 3rd-party chain
    # builds on a modern host toolchain (cf. build-applewin-core.sh's CMake edits).
    local mk="${STAGE_DIR}/build/3rdparty.mk"

    # Install our 3rd-party source patches; openMSX's chain applies
    # build/3rdparty/<pkg>.diff right after extracting that package. The SDL2 patch
    # makes the "offscreen" video driver fall back to the default EGL display when
    # the GPU lacks EGL_EXT_device_enumeration (e.g. Adreno) -- required for the
    # headless GLES2 rendering openMSX uses on Android.
    if compgen -G "${SCRIPT_DIR}/patches/*.diff" >/dev/null 2>&1; then
        mkdir -p "${STAGE_DIR}/build/3rdparty"
        cp -a "${SCRIPT_DIR}/patches/"*.diff "${STAGE_DIR}/build/3rdparty/"
        echo "Installed 3rd-party source patches into ${STAGE_DIR}/build/3rdparty"
    fi

    # openMSX builds pkg-config (a HOST tool) with its bundled ancient glib, which
    # uses `bool` as an identifier -- a hard error under host gcc's C23 default
    # (gcc 15+/gnu23). Pin that host build to gnu11 so `bool` stays an identifier.
    if [[ -f "${mk}" ]] && ! grep -q 'std=gnu11' "${mk}"; then
        sed -i 's/CFLAGS="-Wno-error=int-conversion"/CFLAGS="-Wno-error=int-conversion -std=gnu11"/' "${mk}"
        echo "Patched ${mk}: pkg-config host build pinned to -std=gnu11"
    fi

    # openMSX's internal Android code paths (#if PLATFORM_ANDROID in EventDelay /
    # Reactor / HotKey / InputEventGenerator / Display / RenderSettings / file ops)
    # are an unfinished, bit-rotted port (on-screen keyboard, SDL-android display)
    # that references refactored-away APIs and no longer compiles. We don't want
    # them anyway: we drive openMSX through our own offscreen video/input backend.
    # Force PLATFORM_ANDROID=0 so the standard desktop code path compiles, while
    # still targeting the Android toolchain/libs via OPENMSX_TARGET_OS=android.
    local bic="${STAGE_DIR}/build/buildinfo2code.py"
    if [[ -f "${bic}" ]] && ! grep -q 'platformAndroid = False' "${bic}"; then
        sed -i "s/platformAndroid = targetPlatform == 'android'/platformAndroid = False  # forced off: embed via our own backend/" "${bic}"
        echo "Patched ${bic}: PLATFORM_ANDROID forced to 0"
    fi

    # openMSX hardcodes OPENGL_VERSION to OPENGL_2_1 (desktop GL 2.1) in
    # src/video/GLUtil.hh, so it asks SDL for a desktop GL context (libGL.so) which
    # does not exist on Android -> "Could not initialize OpenGL / GLES library".
    # Android is GLES: force OPENGL_ES_2_0 so SDL loads libGLESv2.so and openMSX
    # emits GLES2 shaders.
    local glu="${STAGE_DIR}/src/video/GLUtil.hh"
    if [[ -f "${glu}" ]] && grep -q '#define OPENGL_VERSION OPENGL_2_1' "${glu}"; then
        sed -i 's/#define OPENGL_VERSION OPENGL_2_1/#define OPENGL_VERSION OPENGL_ES_2_0/' "${glu}"
        echo "Patched ${glu}: OPENGL_VERSION -> OPENGL_ES_2_0"
    fi

    # std::aligned_alloc is only exposed by Android libc++ at API >= 28 (guarded
    # by _LIBCPP_USING_IF_EXISTS); we target API 21 for broad device support.
    # posix_memalign exists at every API level and is free()-compatible.
    local mo="${STAGE_DIR}/src/utils/MemoryOps.cc"
    if [[ -f "${mo}" ]] && grep -q 'std::aligned_alloc' "${mo}"; then
        sed -i 's#void\* result = std::aligned_alloc(alignment, size);#void* result = nullptr; if (posix_memalign(\&result, alignment, size) != 0) result = nullptr;#' "${mo}"
        echo "Patched ${mo}: aligned_alloc -> posix_memalign (API < 28)"
    fi

    # FujiNet device connects to 127.0.0.1:FUJINET_DEFAULT_PORT. Move it off the
    # FujiNet-PC default 1985 to 1986 so it matches our runtime's [BOIP] listener
    # and avoids a loopback clash with a co-installed FujiNet Go target (e.g.
    # Apple2) using 1985 on the same device.
    local fn="${STAGE_DIR}/src/serial/FujiNet.cc"
    if [[ -f "${fn}" ]] && grep -q '#define FUJINET_DEFAULT_PORT *1985' "${fn}"; then
        sed -i 's/#define FUJINET_DEFAULT_PORT     1985/#define FUJINET_DEFAULT_PORT     1986/' "${fn}"
        echo "Patched ${fn}: FUJINET_DEFAULT_PORT -> 1986"
    fi

    # GLES3 shim so openMSX's (now non-optional) GL OSD/GUI code compiles against
    # the NDK GLES3 headers; we render via the software rasterizer at runtime.
    local shim="${STAGE_DIR}/android-gl-shim/GL"
    mkdir -p "${shim}"
    cat > "${shim}/glew.h" <<'GLEW'
// GLES3 shim for <GL/glew.h> on Android (FujiNet Go MSX). See build-openmsx-core.sh.
#ifndef OPENMSX_ANDROID_GLEW_SHIM_H
#define OPENMSX_ANDROID_GLEW_SHIM_H
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#ifndef GLEW_OK
#define GLEW_OK 0
#endif
#ifndef GLEW_ERROR_NO_GLX_DISPLAY
#define GLEW_ERROR_NO_GLX_DISPLAY 4
#endif
#define GLEW_VERSION_1_1 1
#define GLEW_VERSION_2_1 1
#define GLEW_VERSION_3_0 1
#define GLEW_VERSION_3_3 1
static unsigned char glewExperimental = 0;
static inline GLenum glewInit(void) { return GLEW_OK; }
static inline const GLubyte* glewGetErrorString(GLenum err) {
    (void)err; return (const GLubyte*)"GLEW shim (Android GLES3)";
}
#ifndef GL_LUMINANCE
#define GL_LUMINANCE 0x1909
#endif
#ifndef GL_LUMINANCE_ALPHA
#define GL_LUMINANCE_ALPHA 0x190A
#endif
#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
#ifndef GL_CLAMP_TO_BORDER
#define GL_CLAMP_TO_BORDER 0x812D
#endif
#endif
GLEW
    cat > "${shim}/gl.h" <<'GLH'
#ifndef OPENMSX_ANDROID_GL_SHIM_H
#define OPENMSX_ANDROID_GL_SHIM_H
#include <GLES3/gl32.h>
#endif
GLH
    echo "Wrote GLES3 shim to ${shim}"

    # Add the shim include dir + the imgui GLES3 backend define to the Android
    # target flags.
    local pmk="${STAGE_DIR}/build/platform-android.mk"
    if [[ -f "${pmk}" ]] && ! grep -q 'android-gl-shim' "${pmk}"; then
        sed -i 's#^TARGET_FLAGS:=-DANDROID -fPIC#TARGET_FLAGS:=-DANDROID -fPIC -Iandroid-gl-shim -DIMGUI_IMPL_OPENGL_ES3#' "${pmk}"
        echo "Patched ${pmk}: shim include + IMGUI GLES3 define"
    fi
}

bundle_share() {
    echo "Bundling openMSX runtime share tree -> ${SHARE_OUT}"
    rm -rf "${SHARE_OUT}"
    mkdir -p "${SHARE_OUT}"

    # The COMPLETE openMSX share/ tree -- openMSX boots by executing
    # <systemdatadir>/init.tcl, which pulls in share/scripts/*. A subset is not
    # enough (the boot fails with "Couldn't find init.tcl"). It is only ~7MB
    # (init.tcl, scripts/, machines/, extensions/, systemroms/, unicodemaps/,
    # settings.xml, softwaredb.xml, skins/, shaders/, ...).
    cp -a "${OPENMSX_SRC}/share/." "${SHARE_OUT}/"

    # Overlay C-BIOS machine configs + ROMs (the default, freely-redistributable
    # BIOS for MSX1/MSX2/MSX2+; openMSX's install.py copies Contrib/cbios ->
    # share/machines).
    cp -a "${OPENMSX_SRC}/Contrib/cbios/." "${SHARE_OUT}/machines/"

    # The FujiNet extension (cartridge) + its config ROM are required every boot
    # (already part of share/, copied above; guard that they are present).
    [[ -f "${SHARE_OUT}/extensions/FujiNet.xml" ]] \
        || fail "FujiNet.xml missing under ${OPENMSX_SRC}/share/extensions"
    [[ -f "${SHARE_OUT}/extensions/fujinet-config.rom" ]] \
        || fail "fujinet-config.rom missing under ${OPENMSX_SRC}/share/extensions"
}

build_abi() {
    local abi="$1" cpu triple ndk install
    cpu=$(abi_cpu "${abi}"); triple=$(abi_triple "${abi}"); ndk=$(read_ndk_dir)
    install="${INSTALL_ROOT}/${abi}"

    local toolchain="${ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    [[ -d "${toolchain}" ]] || fail "NDK toolchain bin not found at ${toolchain}"

    echo "==> Building openMSX for ${abi} (cpu=${cpu}, api=${ANDROID_API})"
    mkdir -p "${install}/lib" "${install}/include"

    # openMSX's Make build auto-selects the cross compiler from OPENMSX_TARGET_CPU
    # (platform-android.mk). Put the NDK clang wrappers on PATH and pin the API.
    export PATH="${toolchain}:${PATH}"
    export CXX="${toolchain}/${triple}${ANDROID_API}-clang++"
    export CC="${toolchain}/${triple}${ANDROID_API}-clang"
    [[ -x "${CXX}" ]] || fail "NDK C++ compiler not found: ${CXX}"

    # The heavy native build (3rd-party static libs + openMSX objects). This is
    # the Phase 2 integration boundary -- it needs the NDK + openMSX's 3rdparty
    # downloads working for android. Gated behind OPENMSX_DO_NATIVE_BUILD so the
    # Phase 1 scaffold (which only needs staging + share bundling) does not block
    # on it; CMakeLists builds the stub core until install/<abi>/lib has archives.
    if [[ "${OPENMSX_DO_NATIVE_BUILD:-0}" != "1" ]]; then
        echo "    (skipping native compile; set OPENMSX_DO_NATIVE_BUILD=1 to enable)"
        return 0
    fi

    ( cd "${STAGE_DIR}" && make \
        OPENMSX_TARGET_OS=android \
        OPENMSX_TARGET_CPU="${cpu}" \
        OPENMSX_FLAVOUR=opt \
        CXX="${CXX}" PYTHON=python3 \
        3rdparty ) || fail "openMSX 3rd-party build failed for ${abi}"

    # Build openMSX itself (compiles src/ into obj/*.o). 3RDPARTY_FLAG=true makes
    # the build use the static 3rd-party libs just built and pins BUILD_PATH to
    # the matching '-3rd' suffix. The final openmsx.so link expects the SDL/Android
    # entry we replace in Phase 3, so a link failure here is tolerated as long as
    # the objects were produced -- we archive those.
    ( cd "${STAGE_DIR}" && make \
        OPENMSX_TARGET_OS=android \
        OPENMSX_TARGET_CPU="${cpu}" \
        OPENMSX_FLAVOUR=opt \
        3RDPARTY_FLAG=true \
        CXX="${CXX}" PYTHON=python3 ) || echo "    (openMSX final link incomplete; archiving objects)"

    # Collect: openMSX objects (minus the executable's main) into libopenmsx.a,
    # plus the statically built 3rd-party archives, plus headers. With 3rd-party
    # static linking the build dir carries the '-3rd' suffix.
    local derived="${STAGE_DIR}/derived/${cpu}-android-opt-3rd"
    local objs
    objs=$(find "${derived}/obj" -name '*.o' ! -name 'main.o' 2>/dev/null | wc -l)
    [[ "${objs}" -gt 0 ]] || fail "no openMSX objects produced under ${derived}/obj"
    find "${derived}/obj" -name '*.o' ! -name 'main.o' -print0 \
        | xargs -0 "${toolchain}/llvm-ar" rcs "${install}/lib/libopenmsx.a"
    echo "    archived ${objs} openMSX objects -> libopenmsx.a"

    find "${derived}/3rdparty/install/lib" -name '*.a' -exec cp -a {} "${install}/lib/" \; 2>/dev/null || true
    cp -a "${STAGE_DIR}/src" "${install}/include/openmsx" 2>/dev/null || true
    cp -a "${derived}/3rdparty/install/include/." "${install}/include/" 2>/dev/null || true
    cp -a "${derived}/config" "${install}/include/openmsx-config" 2>/dev/null || true
}

copy_sdl_java() {
    # openMSX is an SDL app; SDL2-on-Android requires its org.libsdl.app.* Java
    # companion classes to be present (JNI_OnLoad does FindClass + RegisterNatives
    # on them, and the app calls SDL.setupJNI() so SDL's Android audio/input
    # drivers have valid JNI references). Vendor the exact set matching the linked
    # SDL2 from the staged 3rd-party source.
    local sdl_java
    sdl_java=$(find "${STAGE_DIR}/derived" -path '*SDL2-*/android-project/app/src/main/java/org/libsdl/app' -type d 2>/dev/null | head -1)
    if [[ -n "${sdl_java}" ]]; then
        local dest="${PROJECT_ROOT}/app/src/main/java/org/libsdl"
        mkdir -p "${dest}"
        rm -rf "${dest}/app"
        cp -a "${sdl_java}" "${dest}/"
        # Patch: let SDLActivity.getNativeSurface() return a host-provided Surface
        # (our Compose SurfaceView) so SDL's Android video driver renders openMSX
        # straight to it, with no SDLActivity. See EmulatorSurface / SessionController.
        local act="${dest}/app/SDLActivity.java"
        if [[ -f "${act}" ]] && ! grep -q 'mExternalSurface' "${act}"; then
            python3 - "${act}" <<'PY'
import sys, re
p = sys.argv[1]
s = open(p).read()
old = ("    public static Surface getNativeSurface() {\n"
       "        if (SDLActivity.mSurface == null) {\n")
new = ("    // FujiNet Go MSX: render openMSX onto a host-provided Surface.\n"
       "    public static Surface mExternalSurface;\n\n"
       "    public static Surface getNativeSurface() {\n"
       "        if (mExternalSurface != null) {\n"
       "            return mExternalSurface;\n"
       "        }\n"
       "        if (SDLActivity.mSurface == null) {\n")
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
            echo "Patched SDLActivity.getNativeSurface() for mExternalSurface"
        fi
        echo "Vendored SDL2 Java companion classes -> ${dest}/app"
    else
        echo "WARNING: SDL2 Java sources not found; SDL JNI setup will fail at runtime." >&2
    fi
}

# --- run --------------------------------------------------------------------
stage_source
patch_staged_source
bundle_share
while IFS= read -r abi; do build_abi "${abi}"; done < <(resolve_abis)
copy_sdl_java

echo "openMSX core staging complete:"
echo "  staged source : ${STAGE_DIR}"
echo "  share tree    : ${SHARE_OUT}"
echo "  install root  : ${INSTALL_ROOT} (per-ABI archives when OPENMSX_DO_NATIVE_BUILD=1)"
