cmake_minimum_required(VERSION 3.22)
project(retroai_shim C)

# Deliberately NOT wired into app/src/main/cpp/CMakeLists.txt. This artefact
# belongs in RetroArch's core directory, not in our APK, and a separate project
# is the cheapest way to make that impossible to get wrong by accident.

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# The name RetroArch will load. The "_shim" infix is not decoration: shim.c
# strips it to find the real core beside it, so a rename is the entire
# mechanism for supporting another core. PREFIX "" because libretro cores on
# Android carry no "lib".
add_library(vbam_shim_libretro_android SHARED shim.c frame_link.c)
set_target_properties(vbam_shim_libretro_android PROPERTIES PREFIX "")

target_compile_options(vbam_shim_libretro_android PRIVATE
    -O2
    -Wall -Wextra -Werror
    # Hidden by default, with the 25 entry points opted back in one at a time
    # via RETRO_API. That makes the exported set a property of the source
    # rather than of whatever happened to be non-static, which is what the
    # llvm-nm check below is able to assert on.
    -fvisibility=hidden)

target_link_libraries(vbam_shim_libretro_android log)

target_link_options(vbam_shim_libretro_android PRIVATE
    # A core with an unresolved symbol loads fine and dies at the call site,
    # inside RetroArch's process, with no useful stack. Refuse at link time.
    -Wl,--no-undefined

    # This library starts a thread, so it must never be unmapped.
    #
    # RetroArch dlcloses a core when it is done with it - including when you
    # merely select one in the menu to look at it and then back out. Our sender
    # thread survives that, and the moment its code is unmapped it faults
    # somewhere with no stack worth reading. Measured: selecting the shim in
    # Load Core crashed RetroArch immediately after logging a clean startup.
    #
    # nodelete pins the library for the life of the process, which is what we
    # want anyway - we also hold a dlopen handle on the real core and never
    # release it.
    -Wl,-z,nodelete)
