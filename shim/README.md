# libretro shim core

A libretro core that is not a core. RetroArch loads `vbam_shim_libretro_android.so`,
which `dlopen`s `vbam_libretro_android.so` beside it and forwards all 25 entry
points. Because RetroArch loads it voluntarily there is no injection involved:
no root, no ptrace, no platform signature, and it behaves the same on every
Android version.

Its reason to exist is in `NewSolution.md` §1: with MediaProjection the only
frame source, RetroArch's own picture and the enhanced picture must occupy
different parts of one screen, and on a 3.5" panel that cost is not payable.
Frames taken here never reach the screen compositor at all, so the overlay can
go fullscreen and the corner viewport disappears.

## Build

```bash
./shim/build.sh
```

Needs only the NDK and CMake that `setup_toolchain.sh` already installs — no
JDK, no Gradle, no ncnn. The script builds and then refuses to hand over a
binary RetroArch would reject: exactly 25 `retro_*` exports, the
`retroai_shim_magic` marker, and no `libc++_shared.so` dependency (RetroArch's
process does not contain ours).

`shim/` is deliberately not wired into `app/src/main/cpp/CMakeLists.txt`. The
artefact belongs in RetroArch's core directory; keeping it a separate project
makes shipping it inside the APK impossible by accident.

## Supporting another core

Copy the binary and rename it. The shim finds the real core by stripping the
first `_shim` out of its own filename and looking in the same directory:

```
vbam_shim_libretro_android.so    ->  vbam_libretro_android.so
snes9x_shim_libretro_android.so  ->  snes9x_libretro_android.so
```

That is the whole of multi-core support, and it is why there is no config file:
one would have to live on `/sdcard`, which is mounted `noexec`, so any core path
it named would have to point back into RetroArch's private directory anyway —
and one shim could still only serve one core.

## Installing it on a device

The shim has to end up in `/data/data/com.retroarch.aarch64/cores/`, which no
third-party app can write. RetroArch can, so RetroArch does it:

1. push the `.so` somewhere readable, e.g.
   `/storage/emulated/0/RetroAIScaler/cores/`;
2. in RetroArch: **Load Core → Install or Restore a Core** → pick the file;
3. point the launcher at it. Pegasus names the core explicitly in
   `<volume>/Roms/<system>/metadata.pegasus.txt`:
   `-e LIBRETRO /data/data/com.retroarch.aarch64/cores/vbam_libretro_android.so`
   → change that one token to `vbam_shim_...`. Pegasus reads the file at
   startup, so restart it.

Step 2 is the only step that cannot be automated, and it is once per core. The
launch line is one token, so undoing all of this is one token too.

## What is not here yet

Gate 2 is a pure pass-through: no IPC, no frame publishing. It answers three
questions and no others — does RetroArch accept the `.so`, does the real core
load, does the game play identically. Frames go to the app in gate 3 over a
loopback TCP socket (`NewSolution.md` §4.4, measured in gate 1).

## Provenance

`libretro_abi.h` is transcribed from `libretro.h` in
[libretro/libretro-common](https://github.com/libretro/libretro-common)
(`include/libretro.h`), which its own header places under a permissive
"do what you want" licence. Only the parts a pass-through shim needs are
reproduced; see the comment at the top of that file for why it is not a full
vendored copy.

The shim links the real core at runtime through `dlopen` and contains no
RetroArch code, so RetroArch's GPLv3 and this project's MIT stay separate.
Repackaging RetroArch is explicitly out of scope.
