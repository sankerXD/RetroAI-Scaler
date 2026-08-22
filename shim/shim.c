/*
 * RetroAI shim core - gate 2 (NewSolution.md §7).
 *
 * A libretro core that is not a core. RetroArch loads this .so, and it dlopens
 * the real core beside it and forwards all 25 entry points. Because RetroArch
 * loads it of its own accord there is no injection here: no root, no ptrace, no
 * platform signature, and it works the same on every Android version.
 *
 * At this gate it is a pure pass-through. Not one line of IPC - the only
 * questions it exists to answer are: does RetroArch accept the .so, does the
 * real core dlopen, and does the game play exactly as before. Frames go to the
 * app in gate 3, over the loopback socket gate 1 measured.
 *
 * The one rule this file lives under: A CRASH HERE IS A CRASH IN RETROARCH.
 * That is why it is plain C with no STL, no exceptions, no allocation on the
 * frame path, no blocking syscall inside retro_run, and why every failure
 * degrades to "RetroArch reports it could not load the content" rather than to
 * a signal.
 *
 * Two things ride along at this gate because they are pure logging and they
 * answer gate 3's questions for free, from inside the real process:
 *   - the first video_refresh is logged with its geometry and pitch, and
 *     SET_PIXEL_FORMAT is logged as it goes past, so we learn what VBA-M
 *     actually emits without a second device trip;
 *   - the control file under /sdcard is opened once, which is the only
 *     untested part of E5 (RetroArch has legacy storage, so it should read
 *     /sdcard freely - "should" being the word this removes).
 * Neither changes behaviour. Both forward regardless of what they observe.
 */
#include "libretro_abi.h"

#include <android/log.h>
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#define TAG "RetroAI_Shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Bumped when the shim/app protocol changes. Exported so that a future
 * take-over scheme can dlopen a file and ask "are you already me?" before
 * overwriting it - see NewSolution.md §10 ①. */
#define RETROAI_SHIM_MAGIC 0x52414931u /* "RAI1" */

#define SHIM_MARKER "_shim"
#define CONTROL_FILE "/storage/emulated/0/RetroAIScaler/shim/probe.txt"

/* ---------------------------------------------------------------- real core */

struct core_fns {
    void     (*set_environment)(retro_environment_t);
    void     (*set_video_refresh)(retro_video_refresh_t);
    void     (*set_audio_sample)(retro_audio_sample_t);
    void     (*set_audio_sample_batch)(retro_audio_sample_batch_t);
    void     (*set_input_poll)(retro_input_poll_t);
    void     (*set_input_state)(retro_input_state_t);

    void     (*init)(void);
    void     (*deinit)(void);
    unsigned (*api_version)(void);
    void     (*get_system_info)(struct retro_system_info *);
    void     (*get_system_av_info)(struct retro_system_av_info *);
    void     (*set_controller_port_device)(unsigned, unsigned);
    void     (*reset)(void);
    void     (*run)(void);
    unsigned (*get_region)(void);
    void *   (*get_memory_data)(unsigned);
    size_t   (*get_memory_size)(unsigned);

    size_t   (*serialize_size)(void);
    bool     (*serialize)(void *, size_t);
    bool     (*unserialize)(const void *, size_t);
    void     (*cheat_reset)(void);
    void     (*cheat_set)(unsigned, bool, const char *);
    bool     (*load_game)(const struct retro_game_info *);
    bool     (*load_game_special)(unsigned, const struct retro_game_info *, size_t);
    void     (*unload_game)(void);
};

static struct core_fns  g_core;
static void            *g_handle;
static pthread_once_t   g_once = PTHREAD_ONCE_INIT;
static bool             g_ready;
static char             g_core_path[PATH_MAX];

/* RetroArch's callbacks, kept so the wrappers can forward to them. */
static retro_environment_t   g_env_cb;
static retro_video_refresh_t g_video_cb;

static unsigned long g_frames;

/* Instrumentation for the "core does not support save states" symptom.
 *
 * RetroArch refusing save states has two causes that look identical from the
 * outside: it asked us and we answered zero (a forwarding bug here), or it
 * never asked at all - since 1.16 the savestate menu is gated on
 * savestate_support_level, which comes from the core's .info file, and the shim
 * has none. A log line at the call site tells the two apart in one launch:
 * no line means nobody asked, and the fault is upstream of this file.
 * Deliberately bounded, so it cannot become per-frame noise. */
static unsigned g_serialize_queries;
static bool     g_logged_sysinfo;
static bool     g_logged_memsize[2];

/* ------------------------------------------------------------------ helpers */

/*
 * Derive the real core's path from our own: strip the first "_shim" out of our
 * filename and look in the same directory.
 *
 *   vbam_shim_libretro_android.so   ->  vbam_libretro_android.so
 *   snes9x_shim_libretro_android.so ->  snes9x_libretro_android.so
 *
 * A config file was the obvious alternative and is worse. It would have to live
 * on /sdcard, which is mounted noexec, so any core path it named would have to
 * point back into RetroArch's private directory anyway - and then one shim
 * could still only serve one core. Deriving from the name means copying this
 * binary under a different name is the whole of multi-core support, and there
 * is no extra file to distribute, maintain, or get wrong.
 */
static bool derive_core_path(void)
{
    Dl_info info;
    if (!dladdr((void *)derive_core_path, &info) || !info.dli_fname) {
        LOGE("dladdr failed - cannot locate myself, so cannot locate the core");
        return false;
    }

    const char *self = info.dli_fname;
    const char *slash = strrchr(self, '/');
    if (!slash) {
        LOGE("my own path has no directory component: '%s'", self);
        return false;
    }

    const char *base = slash + 1;
    const char *mark = strstr(base, SHIM_MARKER);
    if (!mark) {
        LOGE("my filename '%s' has no '%s' in it, so there is no core name to "
             "derive - rename me to <core>%s_libretro_android.so",
             base, SHIM_MARKER, SHIM_MARKER);
        return false;
    }

    size_t dir_len    = (size_t)(base - self);      /* includes the slash */
    size_t prefix_len = (size_t)(mark - base);      /* before "_shim"     */
    const char *suffix = mark + strlen(SHIM_MARKER);

    int n = snprintf(g_core_path, sizeof(g_core_path), "%.*s%.*s%s",
                     (int)dir_len, self, (int)prefix_len, base, suffix);
    if (n <= 0 || (size_t)n >= sizeof(g_core_path)) {
        LOGE("derived core path does not fit in PATH_MAX");
        return false;
    }
    return true;
}

#define SYM(field, name)                                                       \
    do {                                                                       \
        *(void **)(&g_core.field) = dlsym(g_handle, name);                     \
        if (!g_core.field) {                                                   \
            LOGE("core is missing %s", name);                                  \
            missing++;                                                         \
        }                                                                      \
    } while (0)

static void load_once(void)
{
    /* E5 check, logging only: RetroArch is untrusted_app_27 and holds legacy
     * storage, so it should read /sdcard freely. This is the only part of that
     * claim nothing has actually executed. */
    FILE *f = fopen(CONTROL_FILE, "r");
    if (f) {
        char line[128] = {0};
        if (!fgets(line, sizeof(line), f)) line[0] = '\0';
        line[strcspn(line, "\r\n")] = '\0';
        LOGI("control file readable from inside RetroArch: '%s'", line);
        fclose(f);
    } else {
        LOGI("control file not readable (%s) - fine if the app never wrote it",
             CONTROL_FILE);
    }

    if (!derive_core_path()) return;

    LOGI("dlopen real core: %s", g_core_path);
    /* RTLD_LOCAL so the core's symbols do not join the global namespace and
     * collide with RetroArch's or another core's. RTLD_NOW so a missing
     * dependency is an error here, with a message, rather than a jump into
     * nothing on some later call. */
    g_handle = dlopen(g_core_path, RTLD_NOW | RTLD_LOCAL);
    if (!g_handle) {
        LOGE("dlopen failed: %s", dlerror());
        return;
    }

    int missing = 0;
    SYM(set_environment,            "retro_set_environment");
    SYM(set_video_refresh,          "retro_set_video_refresh");
    SYM(set_audio_sample,           "retro_set_audio_sample");
    SYM(set_audio_sample_batch,     "retro_set_audio_sample_batch");
    SYM(set_input_poll,             "retro_set_input_poll");
    SYM(set_input_state,            "retro_set_input_state");
    SYM(init,                       "retro_init");
    SYM(deinit,                     "retro_deinit");
    SYM(api_version,                "retro_api_version");
    SYM(get_system_info,            "retro_get_system_info");
    SYM(get_system_av_info,         "retro_get_system_av_info");
    SYM(set_controller_port_device, "retro_set_controller_port_device");
    SYM(reset,                      "retro_reset");
    SYM(run,                        "retro_run");
    SYM(get_region,                 "retro_get_region");
    SYM(get_memory_data,            "retro_get_memory_data");
    SYM(get_memory_size,            "retro_get_memory_size");
    SYM(serialize_size,             "retro_serialize_size");
    SYM(serialize,                  "retro_serialize");
    SYM(unserialize,                "retro_unserialize");
    SYM(cheat_reset,                "retro_cheat_reset");
    SYM(cheat_set,                  "retro_cheat_set");
    SYM(load_game,                  "retro_load_game");
    SYM(load_game_special,          "retro_load_game_special");
    SYM(unload_game,                "retro_unload_game");

    if (missing) {
        LOGE("%d symbol(s) missing - refusing to run this core", missing);
        return;
    }

    g_ready = true;
    LOGI("real core loaded and all 25 entry points resolved");
}

/*
 * RetroArch's first call into a freshly dlopened core is retro_get_system_info,
 * before retro_init and before anything hands us a chance to set up. So loading
 * the real core cannot wait for retro_load_game: it has to happen on the first
 * entry into ANY exported function, whichever that turns out to be.
 */
static inline bool ensure(void)
{
    pthread_once(&g_once, load_once);
    return g_ready;
}

/* Never dlclose the real core. RetroArch calls retro_deinit and then retro_init
 * again on the same loaded library when content is restarted, so closing it
 * there would unload a core that is about to be used. Leaving it mapped until
 * RetroArch unloads us costs one file mapping and removes a whole class of
 * use-after-unload. */

/* -------------------------------------------------------------- environment */

static bool shim_env_cb(unsigned cmd, void *data)
{
    /* Gate 2 forwards everything untouched. The two cases below only log; the
     * moment one of them returns something of its own, this stops being a
     * pass-through and gate 2 stops being a clean measurement. */
    if (cmd == RETRO_ENVIRONMENT_SET_PIXEL_FORMAT && data) {
        unsigned fmt = *(const unsigned *)data;
        const char *name = fmt == 0 ? "0RGB1555" : fmt == 1 ? "XRGB8888"
                         : fmt == 2 ? "RGB565" : "unknown";
        LOGI("core requests pixel format %u (%s)", fmt, name);
    } else if (cmd == RETRO_ENVIRONMENT_SET_HW_RENDER) {
        LOGI("core requests HW rendering - gate 3 must refuse this, there are "
             "no CPU pixels to take from such a core");
    }

    return g_env_cb ? g_env_cb(cmd, data) : false;
}

/* ------------------------------------------------------------------- frames */

static void shim_video_cb(const void *data, unsigned width, unsigned height,
                          size_t pitch)
{
    /* The three cases, in an order that must not change:
     *   NULL                        - "reuse the previous frame", no pixels
     *   RETRO_HW_FRAME_BUFFER_VALID - the frame is a GPU texture; (void*)-1 is
     *                                 a sentinel, and reading through it is an
     *                                 immediate segfault in RetroArch
     *   anything else               - real pixels at `data`
     * Gate 3 hangs the publish on that third branch. Gate 2 only counts. */
    if (data && data != RETRO_HW_FRAME_BUFFER_VALID) {
        if (g_frames == 0)
            LOGI("first frame: %ux%u pitch=%zu (%zu bytes/pixel implied)",
                 width, height, pitch, width ? pitch / width : 0);
        else if ((g_frames % 3600) == 0)
            LOGI("frame %lu: %ux%u pitch=%zu", g_frames, width, height, pitch);
        g_frames++;
    }

    /* Forward unconditionally, including the NULL and sentinel cases. Dropping
     * one of those would freeze or corrupt RetroArch's own output, and at this
     * gate RetroArch's output is the reference we are being compared against. */
    if (g_video_cb) g_video_cb(data, width, height, pitch);
}

/* ------------------------------------------------------- the 25 entry points */

RETRO_API void retro_set_environment(retro_environment_t cb)
{
    g_env_cb = cb;
    if (ensure()) g_core.set_environment(shim_env_cb);
}

RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb)
{
    g_video_cb = cb;
    if (ensure()) g_core.set_video_refresh(shim_video_cb);
}

RETRO_API void retro_set_audio_sample(retro_audio_sample_t cb)
{
    if (ensure()) g_core.set_audio_sample(cb);
}

RETRO_API void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb)
{
    if (ensure()) g_core.set_audio_sample_batch(cb);
}

RETRO_API void retro_set_input_poll(retro_input_poll_t cb)
{
    if (ensure()) g_core.set_input_poll(cb);
}

RETRO_API void retro_set_input_state(retro_input_state_t cb)
{
    if (ensure()) g_core.set_input_state(cb);
}

RETRO_API void retro_init(void)
{
    if (ensure()) g_core.init();
}

RETRO_API void retro_deinit(void)
{
    if (ensure()) g_core.deinit();
    g_frames = 0;
}

RETRO_API unsigned retro_api_version(void)
{
    /* The real core's answer, not a hardcoded 1: if it ever speaks a different
     * API version than RetroArch expects, that mismatch is a diagnosis and
     * hardcoding would hide it. When the core did not load, RETRO_API_VERSION
     * keeps RetroArch on the "could not load content" path, which names the
     * real problem, instead of an API-mismatch message that does not. */
    return ensure() ? g_core.api_version() : RETRO_API_VERSION;
}

RETRO_API void retro_get_system_info(struct retro_system_info *info)
{
    if (!info) return;

    if (ensure()) {
        /* Straight through. RetroArch keys per-core config, save sorting,
         * cheats and playlist association off what comes back here, so the
         * shim being invisible in this one function is what keeps every path
         * on the device pointing where it did before. */
        g_core.get_system_info(info);
        if (!g_logged_sysinfo) {
            g_logged_sysinfo = true;
            LOGI("system_info: name='%s' version='%s' ext='%s' "
                 "need_fullpath=%d block_extract=%d",
                 info->library_name     ? info->library_name     : "(null)",
                 info->library_version  ? info->library_version  : "(null)",
                 info->valid_extensions ? info->valid_extensions : "(null)",
                 (int)info->need_fullpath, (int)info->block_extract);
        }
        return;
    }

    memset(info, 0, sizeof(*info));
    info->library_name     = "RetroAI Shim (real core missing)";
    info->library_version  = "0";
    info->valid_extensions = "";
    info->need_fullpath    = false;
    info->block_extract    = false;
}

RETRO_API void retro_get_system_av_info(struct retro_system_av_info *info)
{
    if (!ensure() || !info) return;
    g_core.get_system_av_info(info);
    LOGI("av_info: base=%ux%u max=%ux%u aspect=%.4f fps=%.4f rate=%.1f",
         info->geometry.base_width, info->geometry.base_height,
         info->geometry.max_width, info->geometry.max_height,
         (double)info->geometry.aspect_ratio,
         info->timing.fps, info->timing.sample_rate);
}

RETRO_API void retro_set_controller_port_device(unsigned port, unsigned device)
{
    if (ensure()) g_core.set_controller_port_device(port, device);
}

RETRO_API void retro_reset(void)
{
    if (ensure()) g_core.reset();
}

RETRO_API void retro_run(void)
{
    if (ensure()) g_core.run();
}

RETRO_API unsigned retro_get_region(void)
{
    return ensure() ? g_core.get_region() : 0;
}

RETRO_API void *retro_get_memory_data(unsigned id)
{
    return ensure() ? g_core.get_memory_data(id) : NULL;
}

RETRO_API size_t retro_get_memory_size(unsigned id)
{
    size_t n = ensure() ? g_core.get_memory_size(id) : 0;
    /* id 0 is RETRO_MEMORY_SAVE_RAM - a non-zero answer here is what makes
     * battery saves (.srm) work, and it is a different mechanism from save
     * states, so the two symptoms must not be read as one. */
    if (id < 2 && !g_logged_memsize[id]) {
        g_logged_memsize[id] = true;
        LOGI("memory_size(%u) -> %zu", id, n);
    }
    return n;
}

RETRO_API size_t retro_serialize_size(void)
{
    size_t n = ensure() ? g_core.serialize_size() : 0;
    if (g_serialize_queries < 4) {
        g_serialize_queries++;
        LOGI("serialize_size -> %zu (query #%u)", n, g_serialize_queries);
    }
    return n;
}

RETRO_API bool retro_serialize(void *data, size_t size)
{
    bool ok = ensure() ? g_core.serialize(data, size) : false;
    LOGI("serialize(size=%zu) -> %d", size, (int)ok);
    return ok;
}

RETRO_API bool retro_unserialize(const void *data, size_t size)
{
    bool ok = ensure() ? g_core.unserialize(data, size) : false;
    LOGI("unserialize(size=%zu) -> %d", size, (int)ok);
    return ok;
}

RETRO_API void retro_cheat_reset(void)
{
    if (ensure()) g_core.cheat_reset();
}

RETRO_API void retro_cheat_set(unsigned index, bool enabled, const char *code)
{
    if (ensure()) g_core.cheat_set(index, enabled, code);
}

RETRO_API bool retro_load_game(const struct retro_game_info *game)
{
    if (!ensure()) {
        LOGE("refusing to load content: the real core (%s) is not available",
             g_core_path[0] ? g_core_path : "path not derived");
        return false;
    }
    LOGI("load_game: %s", (game && game->path) ? game->path : "(no path)");
    return g_core.load_game(game);
}

RETRO_API bool retro_load_game_special(unsigned game_type,
                                       const struct retro_game_info *info,
                                       size_t num_info)
{
    if (!ensure()) return false;
    return g_core.load_game_special(game_type, info, num_info);
}

RETRO_API void retro_unload_game(void)
{
    if (ensure()) g_core.unload_game();
}

/* ------------------------------------------------------------------ marker */

RETRO_API unsigned retroai_shim_magic(void)
{
    return RETROAI_SHIM_MAGIC;
}
