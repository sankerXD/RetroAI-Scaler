#include "frame_link.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define TAG "RetroAI_Shim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* The app publishes where to connect and who it will accept. On /sdcard, which
 * RetroArch can read freely because it holds legacy storage (E5, confirmed on
 * device) - and which our app can write, unlike anything in RetroArch's own
 * directories. */
#define LINK_FILE "/storage/emulated/0/RetroAIScaler/shim/link.txt"

#define WIRE_VERSION 1
#define FRAME_MAGIC  0x31494152u   /* "RAI1" */
#define HELLO_MAGIC  0x48494152u   /* "RAIH" */
/* A short ASCII key=value line rather than a frame. Self-describing, readable
 * in a log, and an app that does not know a key can ignore it and carry on. */
#define NOTICE_MAGIC 0x43494152u   /* "RAIC" */
#define TOKEN_MAX    64

/* Exactly 64 bytes, little-endian, which is what both ends are. Asserted at
 * compile time because a header that silently grew would desynchronise the
 * stream and present as garbled pixels rather than as an error. */
struct wire_header {
    uint32_t magic;            /* offset 0, so a reader can resync on it   */
    uint32_t version;
    uint64_t timestamp_ns;     /* 8-aligned here on purpose: putting it at
                                * the end made the compiler insert four
                                * padding bytes and the struct came out 72 */
    uint32_t seq;
    uint32_t payload_bytes;
    uint32_t pitch;            /* bytes per row IN THE PAYLOAD, tightly packed */
    uint16_t width;
    uint16_t height;
    uint16_t pixel_format;
    uint16_t rotation;
    uint8_t  reserved[28];
};
_Static_assert(sizeof(struct wire_header) == 64, "wire header must be 64 bytes");

#define SLOT_COUNT 3
enum { SLOT_FREE = 0, SLOT_FILLING, SLOT_READY, SLOT_SENDING };

struct slot {
    atomic_int         state;
    uint64_t           seq;      /* written while FILLING, read after READY */
    struct wire_header hdr;
    uint8_t           *pixels;
};

static struct slot   g_slots[SLOT_COUNT];
static size_t        g_slot_capacity;
static sem_t         g_wake;
static pthread_t     g_thread;
static atomic_bool   g_running;
static atomic_bool   g_connected;
static atomic_ullong g_seq;
static atomic_ulong  g_published;
static atomic_ulong  g_dropped;
static atomic_uint   g_pixel_format = 2;   /* RGB565 until told otherwise */
static atomic_uint   g_rotation;
static int           g_sock = -1;
static bool          g_warned_oversize;
static atomic_bool   g_hw_render;
static atomic_bool   g_notice_sent;

/* ------------------------------------------------------------------ helpers */

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static unsigned bytes_per_pixel(unsigned pixel_format)
{
    return pixel_format == 1 ? 4 : 2;   /* XRGB8888 is the only 32-bit one */
}

static bool read_link_file(int *port_out, char *token_out, size_t token_sz)
{
    FILE *f = fopen(LINK_FILE, "r");
    if (!f) return false;

    int  port = 0;
    char token[TOKEN_MAX] = {0};
    char line[256];

    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = '\0';
        if (strncmp(line, "port=", 5) == 0)
            port = atoi(line + 5);
        else if (strncmp(line, "token=", 6) == 0)
            snprintf(token, sizeof(token), "%s", line + 6);
    }
    fclose(f);

    if (port <= 0 || port > 65535 || token[0] == '\0') return false;
    *port_out = port;
    snprintf(token_out, token_sz, "%s", token);
    return true;
}

/* Write exactly n bytes or fail. Only ever called from the sender thread. */
static bool send_all(int fd, const void *buf, size_t n)
{
    const uint8_t *p = buf;
    while (n > 0) {
        ssize_t w = send(fd, p, n, MSG_NOSIGNAL);
        if (w > 0) {
            p += w;
            n -= (size_t)w;
            continue;
        }
        if (w < 0 && (errno == EINTR)) continue;
        return false;
    }
    return true;
}

static int connect_to_app(void)
{
    int  port;
    char token[TOKEN_MAX];
    if (!read_link_file(&port, token, sizeof(token))) return -1;

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }

    /* Nagle would hold a frame back waiting for more to coalesce with, which
     * is the opposite of what a latency-bound single-frame-per-tick stream
     * wants. */
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    /* The token stops any other app on the device - loopback is reachable by
     * anything holding INTERNET - from reading someone's game frames just by
     * guessing the port. */
    struct wire_header hello;
    memset(&hello, 0, sizeof(hello));
    hello.magic         = HELLO_MAGIC;
    hello.version       = WIRE_VERSION;
    hello.payload_bytes = (uint32_t)strlen(token);
    hello.timestamp_ns  = now_ns();

    if (!send_all(fd, &hello, sizeof(hello)) ||
        !send_all(fd, token, strlen(token))) {
        close(fd);
        return -1;
    }

    LOGI("frame link connected to 127.0.0.1:%d", port);
    atomic_store(&g_notice_sent, false);
    return fd;
}

/* ------------------------------------------------------------ sender thread */

static void *sender_main(void *unused)
{
    (void)unused;

    while (atomic_load(&g_running)) {
        if (g_sock < 0) {
            g_sock = connect_to_app();
            if (g_sock < 0) {
                /* The app simply is not running most of the time. Back off and
                 * stay silent about it - a log line per second for the normal
                 * case is noise that hides the abnormal one. */
                sleep(1);
                continue;
            }
            atomic_store(&g_connected, true);
        }

        if (!atomic_load(&g_notice_sent)) {
            const char *line = atomic_load(&g_hw_render) ? "hw_render=1"
                                                         : "hw_render=0";
            struct wire_header n;
            memset(&n, 0, sizeof(n));
            n.magic         = NOTICE_MAGIC;
            n.version       = WIRE_VERSION;
            n.payload_bytes = (uint32_t)strlen(line);
            n.timestamp_ns  = now_ns();
            if (send_all(g_sock, &n, sizeof(n)) && send_all(g_sock, line, strlen(line))) {
                atomic_store(&g_notice_sent, true);
                LOGI("told the app: %s", line);
            }
        }

        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += 1;
        if (sem_timedwait(&g_wake, &deadline) != 0) continue;

        /* Send the newest READY slot and free the rest: when we have fallen
         * behind, the freshest frame is the only one worth the wire, and the
         * game's own pacing is never allowed to wait for ours. */
        for (;;) {
            int      best = -1;
            uint64_t best_seq = 0;
            for (int i = 0; i < SLOT_COUNT; i++) {
                if (atomic_load(&g_slots[i].state) != SLOT_READY) continue;
                if (best < 0 || g_slots[i].seq > best_seq) {
                    best = i;
                    best_seq = g_slots[i].seq;
                }
            }
            if (best < 0) break;

            int expected = SLOT_READY;
            if (!atomic_compare_exchange_strong(&g_slots[best].state, &expected,
                                                SLOT_SENDING))
                continue;   /* producer reclaimed it; look again */

            struct slot *s = &g_slots[best];
            bool ok = send_all(g_sock, &s->hdr, sizeof(s->hdr)) &&
                      send_all(g_sock, s->pixels, s->hdr.payload_bytes);
            atomic_store(&s->state, SLOT_FREE);

            if (!ok) {
                LOGI("frame link dropped (%s), will retry", strerror(errno));
                close(g_sock);
                g_sock = -1;
                atomic_store(&g_connected, false);
                /* Back off here too, not only after a FAILED connect. A peer
                 * that accepts and then dies on the first frame - which is
                 * exactly what an exception in the app's read loop looks like -
                 * otherwise puts this into a reconnect loop running dozens of
                 * times a second, burning CPU inside RetroArch's process and
                 * burying the real error in its own log spam. */
                sleep(1);
                break;
            }
            atomic_fetch_add(&g_published, 1);
        }
    }

    if (g_sock >= 0) {
        close(g_sock);
        g_sock = -1;
    }
    atomic_store(&g_connected, false);
    return NULL;
}

/* ------------------------------------------------------------------- public */

bool frame_link_alloc(unsigned max_width, unsigned max_height)
{
    if (g_slot_capacity > 0) return true;

    /* Four bytes per pixel regardless of the current format, so a core that
     * switches to XRGB8888 mid-run cannot outgrow a buffer sized for RGB565. */
    size_t need = (size_t)max_width * max_height * 4;
    if (need == 0) return false;

    for (int i = 0; i < SLOT_COUNT; i++) {
        g_slots[i].pixels = malloc(need);
        if (!g_slots[i].pixels) {
            LOGE("could not allocate %zu bytes for frame slot %d", need, i);
            for (int j = 0; j < i; j++) {
                free(g_slots[j].pixels);
                g_slots[j].pixels = NULL;
            }
            return false;
        }
        atomic_store(&g_slots[i].state, SLOT_FREE);
    }
    g_slot_capacity = need;
    LOGI("frame slots ready: %d x %zu bytes (max %ux%u)",
         SLOT_COUNT, need, max_width, max_height);
    return true;
}

void frame_link_set_format(unsigned pixel_format)
{
    atomic_store(&g_pixel_format, pixel_format);
}

void frame_link_set_rotation(unsigned rotation)
{
    atomic_store(&g_rotation, rotation);
}

void frame_link_set_hw_render(bool hardware_rendered)
{
    atomic_store(&g_hw_render, hardware_rendered);
    /* Force a resend: a core can ask for hardware rendering after the link is
     * already up, and a notice the app never hears is the same as no notice. */
    atomic_store(&g_notice_sent, false);
    sem_post(&g_wake);
}

void frame_link_start(void)
{
    if (atomic_exchange(&g_running, true)) return;
    sem_init(&g_wake, 0, 0);
    if (pthread_create(&g_thread, NULL, sender_main, NULL) != 0) {
        LOGE("could not start the frame link thread - staying a pure passthrough");
        atomic_store(&g_running, false);
    }
}

void frame_link_stop(void)
{
    if (!atomic_exchange(&g_running, false)) return;
    sem_post(&g_wake);
    pthread_join(g_thread, NULL);
    sem_destroy(&g_wake);
    for (int i = 0; i < SLOT_COUNT; i++) {
        free(g_slots[i].pixels);
        g_slots[i].pixels = NULL;
    }
    g_slot_capacity = 0;
}

void frame_link_publish(const void *data, unsigned width, unsigned height,
                        size_t pitch)
{
    /* Nobody listening: this is the common case, and it costs one atomic load.
     * §4.4's first safety line - the shim is a pure passthrough until the app
     * is actually there. */
    if (!atomic_load(&g_connected) || g_slot_capacity == 0) return;

    unsigned bpp        = bytes_per_pixel(atomic_load(&g_pixel_format));
    size_t   row_bytes  = (size_t)width * bpp;
    size_t   need       = row_bytes * height;

    /* pitch < width*bpp means the format we recorded is not the format the
     * core is actually emitting - RetroArch is free to refuse SET_PIXEL_FORMAT
     * and leave the core on the 0RGB1555 default, and we only ever saw the
     * request. Reading row_bytes out of a shorter row would run off the end of
     * the core's buffer, so refuse rather than trust our own bookkeeping. */
    if (pitch < row_bytes) {
        if (!g_warned_oversize) {
            g_warned_oversize = true;
            LOGE("pitch %zu is shorter than %ux%u bytes/pixel implies (%zu) - "
                 "the recorded pixel format %u is wrong, not publishing",
                 pitch, width, bpp, row_bytes, atomic_load(&g_pixel_format));
        }
        return;
    }

    if (need > g_slot_capacity) {
        if (!g_warned_oversize) {
            g_warned_oversize = true;
            LOGE("frame %ux%u needs %zu bytes but slots hold %zu - not "
                 "publishing; av_info reported a smaller maximum than the core "
                 "actually emits", width, height, need, g_slot_capacity);
        }
        return;
    }

    /* Claim a slot. Prefer a free one; otherwise take the oldest READY frame,
     * because dropping the stalest frame is always better than making
     * retro_run wait. SENDING slots are never touched. */
    int slot = -1;
    for (int i = 0; i < SLOT_COUNT && slot < 0; i++) {
        int expected = SLOT_FREE;
        if (atomic_compare_exchange_strong(&g_slots[i].state, &expected,
                                           SLOT_FILLING))
            slot = i;
    }
    if (slot < 0) {
        int      oldest = -1;
        uint64_t oldest_seq = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (atomic_load(&g_slots[i].state) != SLOT_READY) continue;
            if (oldest < 0 || g_slots[i].seq < oldest_seq) {
                oldest = i;
                oldest_seq = g_slots[i].seq;
            }
        }
        if (oldest >= 0) {
            int expected = SLOT_READY;
            if (atomic_compare_exchange_strong(&g_slots[oldest].state, &expected,
                                               SLOT_FILLING))
                slot = oldest;
        }
    }
    if (slot < 0) {
        atomic_fetch_add(&g_dropped, 1);
        return;
    }

    struct slot *s = &g_slots[slot];

    /* Repack to a tight pitch. Cores commonly pad rows to a power of two, and
     * sending the padding would put a format quirk of one core into the wire
     * protocol and into every consumer of it. VBA-M happens to have
     * pitch == width*bpp, so this is one memcpy there. */
    if (pitch == row_bytes) {
        memcpy(s->pixels, data, need);
    } else {
        const uint8_t *src = data;
        uint8_t       *dst = s->pixels;
        for (unsigned y = 0; y < height; y++) {
            memcpy(dst, src, row_bytes);
            src += pitch;
            dst += row_bytes;
        }
    }

    s->seq = atomic_fetch_add(&g_seq, 1) + 1;
    memset(&s->hdr, 0, sizeof(s->hdr));
    s->hdr.magic         = FRAME_MAGIC;
    s->hdr.version       = WIRE_VERSION;
    s->hdr.seq           = (uint32_t)s->seq;
    s->hdr.payload_bytes = (uint32_t)need;
    s->hdr.width         = (uint16_t)width;
    s->hdr.height        = (uint16_t)height;
    s->hdr.pitch         = (uint32_t)row_bytes;
    s->hdr.pixel_format  = (uint16_t)atomic_load(&g_pixel_format);
    s->hdr.rotation      = (uint16_t)atomic_load(&g_rotation);
    s->hdr.timestamp_ns  = now_ns();

    atomic_store(&s->state, SLOT_READY);
    sem_post(&g_wake);
}

void frame_link_stats(unsigned long *published, unsigned long *dropped,
                      bool *connected)
{
    if (published) *published = atomic_load(&g_published);
    if (dropped)   *dropped   = atomic_load(&g_dropped);
    if (connected) *connected = atomic_load(&g_connected);
}
