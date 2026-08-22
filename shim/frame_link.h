/*
 * frame_link - carries native-resolution frames out of RetroArch's process.
 *
 * Kept separate from shim.c so that file stays about one thing: forwarding the
 * libretro API. Everything about sockets, buffering and dropping lives here.
 *
 * The transport is a loopback TCP connection to the RetroAI-Scaler app, which
 * gate 1 measured working from RetroArch's own process: SELinux checks
 * name_connect against the port's type, not the peer's domain, and never
 * consults the per-app MLS categories that rule out unix sockets between two
 * apps. That leaves this side as plain C with no JNI, no reflection and no
 * Binder.
 *
 * The rule everything here is shaped by: A CRASH OR A STALL HERE IS A CRASH OR
 * A STALL IN RETROARCH. So retro_run's thread only ever memcpys into a
 * preallocated slot and posts a semaphore; it never allocates, never touches
 * the socket, and never holds a lock across anything that can block. When the
 * app is not connected it does not even do that - publish returns immediately.
 */
#ifndef RETROAI_FRAME_LINK_H
#define RETROAI_FRAME_LINK_H

#include <stdbool.h>
#include <stddef.h>

/* Spawn the connect-and-send thread. Safe to call once, at core load. */
void frame_link_start(void);

/* Join the thread and close the socket.
 *
 * Not currently called from anywhere, on purpose. retro_deinit would be the
 * obvious place and is the wrong one: RetroArch calls deinit and then init
 * again on the same loaded library when content restarts, while the one-time
 * setup that starts this thread does not run twice - so stopping there would
 * leave the link dead for the rest of the session. The thread costs one
 * blocked wait per second and dies with the process. */
void frame_link_stop(void);

/* Allocate the slot ring, from retro_get_system_av_info's max geometry.
 * Deliberately NOT lazy on the frame path - allocation there is exactly the
 * kind of unbounded pause retro_run must never take. */
bool frame_link_alloc(unsigned max_width, unsigned max_height);

/* libretro pixel format (0 = 0RGB1555, 1 = XRGB8888, 2 = RGB565) and rotation,
 * recorded as the environment callback sees them and reported per frame. */
void frame_link_set_format(unsigned pixel_format);
void frame_link_set_rotation(unsigned rotation);

/* Called from retro_run. `data` must be real pixels: the caller has already
 * rejected NULL and RETRO_HW_FRAME_BUFFER_VALID. */
void frame_link_publish(const void *data, unsigned width, unsigned height,
                        size_t pitch);

/* Counters for the periodic log line. */
void frame_link_stats(unsigned long *published, unsigned long *dropped,
                      bool *connected);

#endif /* RETROAI_FRAME_LINK_H */
