cmake_minimum_required(VERSION 3.22.1)

project("msxcore")

# ---------------------------------------------------------------------------
# libmsxcore.so = the Android host (session runtime + offscreen openMSX driver +
# the FujiNet dlopen shim) linked against openMSX, which is embedded as a set of
# *prebuilt static archives*.
#
# Unlike AppleWin (a CMake project we add_subdirectory), openMSX has a bespoke
# Make build that also downloads + statically builds its own 3rd-party stack
# (SDL2, Tcl, freetype, libpng, ogg/vorbis/theora, zlib) for the target. So
# tools/openmsx/build-openmsx-core.sh runs that native chain per Android ABI and
# installs the results under:
#
#   ../cpp-generated/openmsx/install/<ABI>/lib/*.a      (openMSX + 3rd-party)
#   ../cpp-generated/openmsx/install/<ABI>/include/     (openMSX + SDL headers)
#
# This CMakeLists imports every archive there and links it into libmsxcore.so.
#
# PHASE 1: that tree does not exist yet, so we build a stub-only core (msx_host.
# cpp ships a self-contained placeholder). Once the staged archives are present,
# MSX_HAVE_OPENMSX is defined and the real driver links in -- no edits here.
# ---------------------------------------------------------------------------

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ARM tuning (no-ops on x86/x86_64).
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_ANDROID_ARM_MODE ON)
    set(CMAKE_ANDROID_ARM_NEON ON)
endif()

# 16K page-size support for newer NDKs on ARM (lld expects -z,max-page-size).
if((ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
        AND (NOT DEFINED CMAKE_ANDROID_NDK_VERSION OR CMAKE_ANDROID_NDK_VERSION VERSION_GREATER_EQUAL 26))
    add_link_options(-Wl,-z,max-page-size=16384)
endif()

# --- The app's native library -----------------------------------------------
add_library(
        msxcore
        SHARED
        msx_core.cpp
        session_runtime.cpp
        msx_host.cpp
        fujinet_android.cpp
)

target_include_directories(msxcore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(msxcore
        log
        android
        dl       # fujinet_android.cpp dlopen's libfujinet.so
)

# --- Staged openMSX (Phase 2+): link the prebuilt static archives -----------
set(OPENMSX_INSTALL "${CMAKE_CURRENT_SOURCE_DIR}/../cpp-generated/openmsx/install/${ANDROID_ABI}")

if(EXISTS "${OPENMSX_INSTALL}/lib")
    message(STATUS "msxcore: linking staged openMSX from ${OPENMSX_INSTALL}")
    target_compile_definitions(msxcore PRIVATE MSX_HAVE_OPENMSX=1)

    # openMSX uses flat includes ("#include \"Reactor.hh\"") that rely on every
    # src/ subdirectory being on the include path, plus its generated config
    # headers (build-info.hh / components.hh / Version.ii) and the GLES shim.
    # Derive the per-subdir include list from the staged header tree.
    file(GLOB_RECURSE _omsx_headers "${OPENMSX_INSTALL}/include/openmsx/*.hh")
    set(_omsx_incdirs "")
    foreach(_h ${_omsx_headers})
        get_filename_component(_d "${_h}" DIRECTORY)
        list(APPEND _omsx_incdirs "${_d}")
    endforeach()
    list(REMOVE_DUPLICATES _omsx_incdirs)
    target_include_directories(msxcore PRIVATE
            "${OPENMSX_INSTALL}/include"
            "${OPENMSX_INSTALL}/include/SDL2"
            "${OPENMSX_INSTALL}/include/openmsx-config"
            "${OPENMSX_INSTALL}/../../openmsx/android-gl-shim"
            ${_omsx_incdirs}
    )

    # msx_host.cpp drives openMSX directly, so it must build with the same C++23
    # dialect + the imgui-GLES define openMSX itself was compiled with. (Our other
    # glue files are C++17-compatible and build fine at C++23 too.)
    set_target_properties(msxcore PROPERTIES CXX_STANDARD 23 CXX_STANDARD_REQUIRED ON)
    target_compile_options(msxcore PRIVATE -fconstexpr-steps=2000000)
    target_compile_definitions(msxcore PRIVATE IMGUI_IMPL_OPENGL_ES3)

    # Import every archive the staging step installed (openMSX object archive
    # plus its statically built 3rd-party deps). Wrapped in --start-group so the
    # cyclic deps between openMSX and SDL2/Tcl resolve regardless of order.
    file(GLOB OPENMSX_ARCHIVES "${OPENMSX_INSTALL}/lib/*.a")
    if(NOT OPENMSX_ARCHIVES)
        message(FATAL_ERROR "openMSX install dir ${OPENMSX_INSTALL}/lib has no archives")
    endif()
    # Intercept openMSX's per-frame present so we read the offscreen GLES
    # framebuffer back to the frame sink (msx_host.cpp __wrap_SDL_GL_SwapWindow).
    target_link_options(msxcore PRIVATE "-Wl,--wrap,SDL_GL_SwapWindow")

    target_link_libraries(msxcore
            -Wl,--start-group ${OPENMSX_ARCHIVES} -Wl,--end-group
            GLESv3 GLESv2 EGL OpenSLES
    )
else()
    message(WARNING
        "msxcore: staged openMSX not found at ${OPENMSX_INSTALL}; building the "
        "Phase 1 stub core. Run tools/openmsx/build-openmsx-core.sh to embed openMSX.")
endif()
