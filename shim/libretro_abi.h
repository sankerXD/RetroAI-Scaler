/*
 * libretro_abi.h - the slice of the libretro API a pass-through shim needs.
 *
 * This is NOT libretro.h. Upstream's header is ~3000 lines describing an API
 * this file uses about 5% of, and vendoring it would mean carrying, and keeping
 * in sync, a large document whose bulk we never read. What a shim needs is the
 * ABI: the exact signatures of the 25 entry points RetroArch resolves, and the
 * layout of the three structs it actually dereferences.
 *
 * Every declaration below is transcribed from libretro.h (libretro/libretro-
 * common, include/libretro.h, public domain / "do what you want" licence as
 * stated in its own header). Getting one of them wrong is an ABI break that
 * presents as a crash inside the real core with no useful stack, so each is
 * annotated with what it must match.
 *
 * Deliberately absent: the RETRO_ENVIRONMENT_* command numbers, beyond the two
 * used for logging only. Gate 2 forwards every environment call verbatim, so it
 * needs none of them, and transcribing constants a pass-through never reads is
 * how a wrong number gets in. Gate 3 needs SET_PIXEL_FORMAT, SET_HW_RENDER,
 * SET_GEOMETRY, SET_SYSTEM_AV_INFO, GET_LIBRETRO_PATH and
 * GET_CURRENT_SOFTWARE_FRAMEBUFFER for real; vendor the upstream header then,
 * rather than trusting anyone's memory for the experimental-bit ones.
 */
#ifndef RETROAI_LIBRETRO_ABI_H
#define RETROAI_LIBRETRO_ABI_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Everything is compiled -fvisibility=hidden so that the dynamic symbol table
 * contains exactly the entry points marked here and nothing else. RetroArch
 * refuses a core that is missing one of the 25; a core that exports extras is
 * merely untidy, but "exactly these" is a property we can assert on with
 * llvm-nm, and an untidy one is not. */
#define RETRO_API __attribute__((visibility("default")))

#define RETRO_API_VERSION 1

/* video_refresh's data argument when the frame lives in a GPU texture rather
 * than in memory. Dereferencing it is a segfault inside RetroArch's process,
 * which is our process too. */
#define RETRO_HW_FRAME_BUFFER_VALID ((void *)-1)

/* Logging only - never branched on for behaviour in gate 2. */
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10
#define RETRO_ENVIRONMENT_SET_HW_RENDER    14

/* struct retro_game_geometry: 4 unsigned, then a float. */
struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float    aspect_ratio;
};

/* struct retro_system_timing: two doubles. */
struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

/* struct retro_system_info: three pointers then two bools. RetroArch reads
 * library_name for save sorting and per-core config, and valid_extensions plus
 * need_fullpath to decide whether to hand the core a path or a buffer - which
 * is why passing the real core's answer through unchanged is what keeps saves,
 * cheats and playlist association pointing at the same places as before. */
struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool        need_fullpath;
    bool        block_extract;
};

/* Never dereferenced here - retro_load_game's argument is forwarded as an
 * opaque pointer. Declared only so the signatures below can name it. */
struct retro_game_info {
    const char *path;
    const void *data;
    size_t      size;
    const char *meta;
};

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width,
                                      unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device,
                                       unsigned index, unsigned id);

#ifdef __cplusplus
}
#endif

#endif /* RETROAI_LIBRETRO_ABI_H */
