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
#include "frame_link.h"

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#define TAG "RetroAI_Shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Bumped when the shim/app protocol changes. Exported so that a future
 * take-over scheme can dlopen a file and ask "are you already me?" before
 * overwriting it - see NewSolution.md §10 ①. */
#define RETROAI_SHIM_MAGIC 0x52414931u /* "RAI1" */

#define SHIM_MARKER "_shim"
#define CONTROL_FILE "/storage/emulated/0/RetroAIScaler/shim/probe.txt"

/* One core filename per line, written by the app from what it found in the
 * frontend's launch lines. See replicate_for_listed_cores. */
#define CORES_FILE "/storage/emulated/0/RetroAIScaler/shim/cores.txt"
#define CORE_SUFFIX "_libretro_android.so"
#define SHIM_SUFFIX "_shim_libretro_android.so"

/* Where RetroArch keeps retroarch.cfg on Android, relative to external
 * storage. Only used to find libretro_info_path; see install_info_file. */
#define RA_CFG_FMT "/storage/emulated/0/Android/data/%s/files/retroarch.cfg"
#define RA_INFO_FALLBACK_FMT "/storage/emulated/0/Android/data/%s/files/info"

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
static char             g_self_dir[PATH_MAX];   /* ".../<pkg>/cores/"          */
static char             g_self_base[256];       /* "vbam_shim_libretro_..."    */
static char             g_core_base[256];       /* "vbam_libretro_android.so"  */

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
static unsigned g_serialize_calls;
static unsigned g_unserialize_calls;
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

    if (dir_len >= sizeof(g_self_dir) || strlen(base) >= sizeof(g_self_base))
        return false;
    memcpy(g_self_dir, self, dir_len);
    g_self_dir[dir_len] = '\0';
    snprintf(g_self_base, sizeof(g_self_base), "%s", base);
    snprintf(g_core_base, sizeof(g_core_base), "%.*s%s",
             (int)prefix_len, base, suffix);
    return true;
}

/*
 * Give RetroArch a .info file for ourselves, by copying the real core's.
 *
 * §5.3 concluded ".info is not needed at all" because RetroArch loads a core
 * from an absolute path without consulting it. That is true for LOADING and
 * false for FEATURES: since 1.16 the save-state menu is gated on
 * savestate_support_level, which comes from the .info file, so a core with no
 * info entry is treated as not supporting save states and retro_serialize_size
 * is never even called. Measured on device 2026-08-22 - everything else about
 * the shim was byte-identical to the real core, and only save states were
 * refused.
 *
 * We cannot write RetroArch's info directory from our own app: it sits under
 * a path Android 11 puts out of reach, and on this device it is not even on a
 * volume our app's mount namespace contains. The shim can, because the shim IS
 * RetroArch as far as the kernel is concerned - same uid, same namespace.
 *
 * Two properties this deliberately has:
 *  - it NEVER overwrites an existing file, so a user who has a real
 *    vbam_shim_libretro.info keeps it, and a re-entrant call cannot corrupt
 *    anything;
 *  - it copies rather than synthesises, so the shim reports exactly what the
 *    real core reports. Anything RetroArch keys off that file - save states,
 *    playlist association, firmware lists - then behaves as if the shim were
 *    the core, which is the whole design goal.
 *
 * It takes effect on the NEXT launch: RetroArch builds its info list at
 * startup, before any core is loaded, so the file we write here is read the
 * time after. Acceptable - activation already involves installing a core and
 * rescanning the frontend.
 */
static bool read_cfg_string(const char *cfg_path, const char *key,
                            char *out, size_t out_sz)
{
    FILE *f = fopen(cfg_path, "r");
    if (!f) return false;

    char line[1024];
    size_t key_len = strlen(key);
    bool found = false;

    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, key, key_len) != 0) continue;
        /* key must be followed by space or '=', not by more identifier - so
         * that "libretro_info_path" does not match a longer key. */
        const char *p = line + key_len;
        while (*p == ' ' || *p == '\t') p++;
        if (*p != '=') continue;
        p++;
        while (*p == ' ' || *p == '\t') p++;

        char        *dst = out;
        const char  *end = out + out_sz - 1;
        bool         quoted = (*p == '"');
        if (quoted) p++;
        while (*p && dst < end) {
            if (quoted && *p == '"') break;
            if (!quoted && (*p == '\r' || *p == '\n')) break;
            *dst++ = *p++;
        }
        *dst = '\0';
        /* Unquoted values can still carry trailing whitespace. */
        while (dst > out && (dst[-1] == ' ' || dst[-1] == '\t' ||
                             dst[-1] == '\r' || dst[-1] == '\n'))
            *--dst = '\0';
        found = (out[0] != '\0');
        break;
    }

    fclose(f);
    return found;
}

/* "vbam_libretro_android.so" -> "vbam_libretro". RetroArch drops the platform
 * suffix when it looks for the matching .info. */
static bool info_stem(const char *so_name, char *out, size_t out_sz)
{
    size_t n = strlen(so_name);
    if (n < 4 || strcmp(so_name + n - 3, ".so") != 0) return false;
    n -= 3;

    static const char suffix[] = "_android";
    size_t suffix_len = sizeof(suffix) - 1;
    if (n > suffix_len && strncmp(so_name + n - suffix_len, suffix, suffix_len) == 0)
        n -= suffix_len;

    if (n == 0 || n >= out_sz) return false;
    memcpy(out, so_name, n);
    out[n] = '\0';
    return true;
}

static char tolower_ascii(char c)
{
    return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
}

static void describe_savestate_keys(const char *info_path)
{
    FILE *f = fopen(info_path, "r");
    if (!f) return;

    char line[512];
    int  found = 0;
    while (fgets(line, sizeof(line), f)) {
        if (!strstr(line, "savestate")) continue;
        line[strcspn(line, "\r\n")] = '\0';
        LOGI("  %s declares: %s", info_path, line);
        found++;
    }
    if (found == 0)
        LOGI("  %s declares NO savestate key - RetroArch is deciding from its "
             "own default, so copying this file cannot be what is missing",
             info_path);
    fclose(f);
}

static void describe_info_dir(const char *info_dir)
{
    DIR *d = opendir(info_dir);
    if (!d) {
        LOGE("cannot open info dir %s", info_dir);
        return;
    }

    struct dirent *e;
    int infos = 0, others = 0;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.' &&
            (e->d_name[1] == '\0' || (e->d_name[1] == '.' && e->d_name[2] == '\0')))
            continue;
        size_t n = strlen(e->d_name);
        if (n > 5 && strcmp(e->d_name + n - 5, ".info") == 0) {
            infos++;
        } else {
            if (others < 8) LOGI("  info dir also holds: %s", e->d_name);
            others++;
        }
    }
    closedir(d);
    LOGI("  info dir %s: %d .info files, %d other entries", info_dir, infos, others);
}

static bool contains_ci(const char *haystack, const char *needle)
{
    size_t nl = strlen(needle);
    for (const char *p = haystack; *p; p++) {
        size_t i = 0;
        while (i < nl && p[i] && tolower_ascii(p[i]) == tolower_ascii(needle[i])) i++;
        if (i == nl) return true;
    }
    return false;
}

/*
 * Drop RetroArch's core-info cache when it predates the .info we installed.
 *
 * Writing the file is not enough on its own: RetroArch caches the parsed info
 * list in that directory, and a cache built before our core existed does not
 * mention it, so the file sits there and is never read. Measured symptom: the
 * .info present at startup, savestate = "true" inside it, and save states
 * still refused.
 *
 * The staleness test is what keeps this from being vandalism. We delete only a
 * cache OLDER than our own file, so once RetroArch has rebuilt it the
 * condition stops holding and we never touch it again - no delete-on-every-
 * launch, no fighting with RetroArch over a file it owns. And a cache is by
 * definition regenerable; if we are wrong about which file this is, the cost
 * is one slower startup.
 */
static void invalidate_stale_info_cache(const char *info_dir, const char *our_info)
{
    struct stat ours;
    if (stat(our_info, &ours) != 0) return;

    DIR *d = opendir(info_dir);
    if (!d) return;

    struct dirent *e;
    while ((e = readdir(d))) {
        size_t n = strlen(e->d_name);
        if (n > 5 && strcmp(e->d_name + n - 5, ".info") == 0) continue;
        if (!contains_ci(e->d_name, "cache")) continue;

        char path[PATH_MAX];
        if ((size_t)snprintf(path, sizeof(path), "%s/%s", info_dir, e->d_name)
                >= sizeof(path))
            continue;

        struct stat cache;
        if (stat(path, &cache) != 0 || !S_ISREG(cache.st_mode)) continue;

        if (cache.st_mtime >= ours.st_mtime) {
            LOGI("  %s is newer than our .info, leaving it alone", e->d_name);
            continue;
        }
        if (remove(path) == 0)
            LOGI("  removed stale core-info cache %s - RetroArch rebuilds it "
                 "on the next launch, and that is when save states appear",
                 path);
        else
            LOGE("  could not remove stale cache %s", path);
    }
    closedir(d);
}

/*
 * Copy ourselves once per core the device actually uses.
 *
 * RetroArch's core directory takes one file per trip through "Install or
 * Restore a Core", and the test device references 22 distinct cores across 59
 * launch lines. Twenty-two trips through a file browser is not a product, and
 * it is not a thing anyone would finish.
 *
 * The shim is the one piece of this that CAN do it: it runs as RetroArch, so
 * RetroArch's core directory is writable to it - the same capability that lets
 * it install its own .info. Install one by hand, and it covers the rest on the
 * next launch.
 *
 * This is not the "rename takeover" §10 rejected, and the difference is
 * structural rather than a matter of care: every name written here ends in
 * _shim_libretro_android.so, so it CANNOT collide with a real core's filename.
 * There is no path by which this overwrites a core, and it refuses to overwrite
 * any existing file anyway.
 *
 * A shim is only created where the real core is actually present. A launch line
 * pointing at a shim whose real core is missing is the one way this whole route
 * produces a hard error for the player, and it is cheaper to not create the
 * shim than to detect that later.
 */
static bool copy_file(const char *src, const char *dst)
{
    FILE *in = fopen(src, "rb");
    if (!in) return false;

    /* Write to a temporary name and rename into place. RetroArch could try to
     * load this the moment it appears, and a half-written .so is a crash with
     * no explanation attached. */
    char tmp[PATH_MAX];
    if ((size_t)snprintf(tmp, sizeof(tmp), "%s.tmp", dst) >= sizeof(tmp)) {
        fclose(in);
        return false;
    }
    FILE *out = fopen(tmp, "wb");
    if (!out) {
        fclose(in);
        return false;
    }

    char   buf[8192];
    size_t n;
    bool   ok = true;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) {
            ok = false;
            break;
        }
    }
    fclose(in);
    if (fclose(out) != 0) ok = false;

    if (!ok || rename(tmp, dst) != 0) {
        remove(tmp);
        return false;
    }
    chmod(dst, 0700);
    return true;
}

static void replicate_for_listed_cores(const char *self_dir, const char *self_base)
{
    FILE *f = fopen(CORES_FILE, "r");
    if (!f) return;   /* the app has not asked for anything; nothing to do */

    char self_path[PATH_MAX];
    if ((size_t)snprintf(self_path, sizeof(self_path), "%s%s", self_dir, self_base)
            >= sizeof(self_path)) {
        fclose(f);
        return;
    }

    char line[256];
    int  made = 0, skipped = 0;
    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = '\0';
        size_t len = strlen(line);
        if (len == 0 || line[0] == '#') continue;

        /* Only ever act on names shaped like a core, so a stray line in that
         * file cannot make us write something arbitrary. */
        size_t suffix_len = strlen(CORE_SUFFIX);
        if (len <= suffix_len ||
            strcmp(line + len - suffix_len, CORE_SUFFIX) != 0 ||
            strstr(line, SHIM_MARKER) != NULL ||
            strchr(line, '/') != NULL) {
            skipped++;
            continue;
        }

        char real[PATH_MAX], target[PATH_MAX];
        size_t stem = len - suffix_len;
        if ((size_t)snprintf(real, sizeof(real), "%s%s", self_dir, line) >= sizeof(real) ||
            (size_t)snprintf(target, sizeof(target), "%s%.*s%s",
                             self_dir, (int)stem, line, SHIM_SUFFIX) >= sizeof(target)) {
            skipped++;
            continue;
        }

        struct stat st;
        if (stat(real, &st) != 0) {
            LOGI("  no %s on this device, not making a shim for it", line);
            skipped++;
            continue;
        }
        if (stat(target, &st) == 0) continue;   /* already there */

        if (copy_file(self_path, target)) {
            LOGI("  installed %s", target);
            made++;
        } else {
            LOGE("  could not install %s", target);
        }
    }
    fclose(f);
    if (made) LOGI("replicated into %d core name(s), %d skipped", made, skipped);
}

static void install_info_file(const char *self_dir, const char *self_base,
                              const char *core_base)
{
    /* self_dir is ".../<pkg>/cores/". Two levels up is the package directory,
     * and its basename is the package name RetroArch runs under - derived
     * rather than hardcoded, so a differently-packaged RetroArch still works. */
    char pkg_dir[PATH_MAX];
    size_t dir_len = strlen(self_dir);
    if (dir_len == 0 || dir_len >= sizeof(pkg_dir)) return;
    memcpy(pkg_dir, self_dir, dir_len + 1);
    if (pkg_dir[dir_len - 1] == '/') pkg_dir[dir_len - 1] = '\0';   /* strip / */
    char *cut = strrchr(pkg_dir, '/');                              /* /cores  */
    if (!cut) return;
    *cut = '\0';
    const char *pkg = strrchr(pkg_dir, '/');
    if (!pkg) return;
    pkg++;

    char cfg[PATH_MAX], info_dir[PATH_MAX];
    snprintf(cfg, sizeof(cfg), RA_CFG_FMT, pkg);

    if (read_cfg_string(cfg, "libretro_info_path", info_dir, sizeof(info_dir))) {
        LOGI("info dir from %s: %s", cfg, info_dir);
    } else {
        snprintf(info_dir, sizeof(info_dir), RA_INFO_FALLBACK_FMT, pkg);
        LOGI("could not read libretro_info_path from %s, falling back to %s",
             cfg, info_dir);
    }

    char core_stem[256], self_stem[256];
    if (!info_stem(core_base, core_stem, sizeof(core_stem)) ||
        !info_stem(self_base, self_stem, sizeof(self_stem))) {
        LOGE("cannot derive .info names from '%s' / '%s'", core_base, self_base);
        return;
    }

    char src[PATH_MAX], dst[PATH_MAX];
    snprintf(src, sizeof(src), "%s/%s.info", info_dir, core_stem);
    snprintf(dst, sizeof(dst), "%s/%s.info", info_dir, self_stem);

    /* Whether RetroArch declares save-state support is decided by keys in this
     * file, so log them from the source. If the real core's info carries no
     * savestate key at all, copying it cannot grant what it never stated, and
     * the fix is to append the keys rather than to copy harder. */
    describe_savestate_keys(src);
    /* And list anything in the directory that is not a .info: RetroArch can
     * keep a core-info cache there, and a stale cache would explain a file
     * that is present being ignored. */
    describe_info_dir(info_dir);

    FILE *out = fopen(dst, "r");
    if (out) {
        fseek(out, 0, SEEK_END);
        long sz = ftell(out);
        fclose(out);
        LOGI("%s already exists (%ld bytes), leaving it alone", dst, sz);
        invalidate_stale_info_cache(info_dir, dst);
        return;
    }

    FILE *in = fopen(src, "r");
    if (!in) {
        LOGE("cannot read %s - RetroArch will keep treating the shim as a core "
             "with no info, which is what disables save states", src);
        return;
    }

    out = fopen(dst, "w");
    if (!out) {
        LOGE("cannot write %s", dst);
        fclose(in);
        return;
    }

    char   buf[4096];
    size_t n, total = 0;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) {
            LOGE("short write to %s", dst);
            break;
        }
        total += n;
    }
    fclose(in);
    fclose(out);
    LOGI("wrote %s (%zu bytes copied from %s) - save states need one more "
         "launch, RetroArch builds its info list before loading any core",
         dst, total, src);
    invalidate_stale_info_cache(info_dir, dst);
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

    /* After the core is known to be good, so a failure here can never be
     * confused with a failure to load. Purely additive and idempotent. */
    install_info_file(g_self_dir, g_self_base, g_core_base);

    /* Starts a thread that tries to reach the app once a second and otherwise
     * costs nothing. Until it connects, frame_link_publish returns on one
     * atomic load and the shim stays the pure passthrough gate 2 measured. */
    frame_link_start();

    /* Last, and only once the core is known good: a failure here must never be
     * confused with a failure to load, and there is nothing to replicate for if
     * this shim itself did not work. */
    replicate_for_listed_cores(g_self_dir, g_self_base);
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
        /* Record it either way; whether RetroArch accepts is its answer to
         * give, and if it refuses the core will ask again with another format
         * and this will be overwritten by that one. */
        frame_link_set_format(fmt);
    } else if (cmd == RETRO_ENVIRONMENT_SET_ROTATION && data) {
        frame_link_set_rotation(*(const unsigned *)data);
    } else if (cmd == RETRO_ENVIRONMENT_SET_HW_RENDER) {
        /*
         * Forward it. Do NOT refuse.
         *
         * §4.2 said to return false here, and that was wrong - it was written
         * assuming the shim is the one who has to answer. The shim can hand the
         * question to RetroArch, which really does provide hardware rendering,
         * and then the core behaves exactly as it would with no shim present.
         *
         * Refusing changes the core's behaviour, and that breaks the only
         * promise this thing makes: when it cannot help, it must be invisible.
         * A core told there is no hardware rendering might fall back to
         * software, or might fail outright - and a player who installed us to
         * get a nicer picture would find their Dreamcast games stopped working.
         *
         * What we lose is nothing we ever had: such a core hands video_refresh
         * RETRO_HW_FRAME_BUFFER_VALID instead of pixels, which the frame
         * callback already skips. We simply tell the app there will be no CPU
         * frames, and it says so and stands down.
         *
         * Note this is a per-CORE-SETTING fact, not a per-console one:
         * swanstation and yabause have software renderers in their core
         * options, and with one of those selected they never send this at all
         * and direct mode just works.
         */
        LOGI("core requests HW rendering - forwarding to RetroArch; there will "
             "be no CPU pixels to take, so the app must use screen capture");
        frame_link_set_hw_render(true);
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
        frame_link_publish(data, width, height, pitch);

        if (g_frames == 0) {
            LOGI("first frame: %ux%u pitch=%zu (%zu bytes/pixel implied)",
                 width, height, pitch, width ? pitch / width : 0);
        } else if ((g_frames % 3600) == 0) {
            unsigned long sent, dropped;
            bool connected;
            frame_link_stats(&sent, &dropped, &connected);
            LOGI("frame %lu: %ux%u pitch=%zu link=%s sent=%lu dropped=%lu",
                 g_frames, width, height, pitch,
                 connected ? "up" : "down", sent, dropped);
        }
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

    /* The one place with both the maximum geometry and no frame in flight.
     * Allocating here rather than on first frame keeps malloc out of
     * retro_run, where an unbounded pause is a dropped frame in the game. */
    frame_link_alloc(info->geometry.max_width, info->geometry.max_height);
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
    /* Bounded, because save states are not always user-initiated: with rewind
     * enabled RetroArch serializes EVERY frame, and an unbounded log line here
     * would be 60Hz of logcat traffic inside the frame path. */
    if (g_serialize_calls < 3) {
        g_serialize_calls++;
        LOGI("serialize(size=%zu) -> %d", size, (int)ok);
    }
    return ok;
}

RETRO_API bool retro_unserialize(const void *data, size_t size)
{
    bool ok = ensure() ? g_core.unserialize(data, size) : false;
    if (g_unserialize_calls < 3) {
        g_unserialize_calls++;
        LOGI("unserialize(size=%zu) -> %d", size, (int)ok);
    }
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
