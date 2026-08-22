#!/bin/bash
#
# Build the shim and refuse to hand over a binary that RetroArch would reject.
#
# The checks matter more than the build. A core missing one of the 25 entry
# points is refused with a message that names none of them; a core that pulled
# in libc++_shared.so loads and then dies at the first call, because RetroArch's
# process has no such library. Both present as "it just does not work" on a
# handheld, an hour away from the machine that could have caught them here.
# This is the same reasoning as tools/check_shaders.py, and the same trap it
# avoids: a check that prints a warning and exits 0 is worse than no check.
set -euo pipefail

cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK:-$SDK/ndk/25.2.9519653}"
CMAKE_BIN="$SDK/cmake/3.22.1/bin/cmake"
[ -x "$CMAKE_BIN" ] || CMAKE_BIN=$(command -v cmake)

[ -d "$NDK" ] || { echo "NDK not found at $NDK - run ./setup_toolchain.sh" >&2; exit 1; }
[ -x "$CMAKE_BIN" ] || { echo "no cmake found" >&2; exit 1; }

NM=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-nm | head -1)
READELF=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -1)
OUT=build-shim/vbam_shim_libretro_android.so

# The SDK's cmake ships its own ninja beside it and does NOT put it on PATH, so
# -G Ninja alone fails with "unable to find a build program". Name it.
NINJA="$(dirname "$CMAKE_BIN")/ninja"
[ -x "$NINJA" ] || NINJA=$(command -v ninja || true)
[ -n "$NINJA" ] || { echo "no ninja found beside cmake or on PATH" >&2; exit 1; }

"$CMAKE_BIN" -S shim -B build-shim -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-21 \
    -DCMAKE_BUILD_TYPE=Release >/dev/null
"$CMAKE_BIN" --build build-shim

fail=0

# Exactly 25, no more and no fewer. "No more" is worth asserting too: an extra
# export means -fvisibility=hidden stopped applying to something, and the next
# symbol to leak could be one that collides inside RetroArch's process.
count=$("$NM" -D --defined-only "$OUT" | grep -c ' T retro_' || true)
if [ "$count" -ne 25 ]; then
    echo "FAIL: exports $count retro_* symbols, libretro requires exactly 25" >&2
    "$NM" -D --defined-only "$OUT" | grep ' T retro_' >&2
    fail=1
else
    echo "ok    25 retro_* entry points exported"
fi

if "$NM" -D --defined-only "$OUT" | grep -q ' T retroai_shim_magic'; then
    echo "ok    retroai_shim_magic present"
else
    echo "FAIL: retroai_shim_magic is not exported" >&2
    fail=1
fi

# RetroArch does not ship our libc++_shared.so, and the NDK links it by default
# for anything that smells like C++.
if "$READELF" -d "$OUT" | grep -q 'libc++_shared'; then
    echo "FAIL: links libc++_shared.so, which does not exist in RetroArch's process" >&2
    fail=1
else
    echo "ok    no libc++_shared dependency ($("$READELF" -d "$OUT" | grep -c NEEDED) shared libs needed)"
fi

[ "$fail" -eq 0 ] || exit 1
echo
echo "built $OUT"
echo "push with: adb push $OUT /storage/emulated/0/RetroAIScaler/cores/"
