package com.retroai.scaler.capture

/**
 * Where frames come from. Two implementations, and the service must not care
 * which it has.
 *
 * [CaptureBridge] mirrors the screen through MediaProjection. That is the only
 * route for cores that render on the GPU, because such a core hands libretro a
 * sentinel instead of pixels and there is nothing for the shim to take
 * (NewSolution.md §9).
 *
 * [com.retroai.scaler.shim.ShimFrameSource] takes the emulator core's own
 * frame buffer from inside RetroArch, before the screen compositor sees it. It
 * removes the constraint the whole capture architecture was bent around - that
 * the sampled region and the painted region cannot overlap (AGENT.md §1) - and
 * with it the corner viewport, the viewport detection, the integer snapping and
 * the capture-mode probe.
 *
 * The watchdog is why this is an interface rather than two nullable fields.
 * It has to distinguish "no frames because the pipeline died" from "no frames
 * because nothing is being produced right now", and getting that wrong kills
 * the service four seconds after start. One shape for both sources means the
 * rule is written once.
 */
interface FrameSource {

    /** Elapsed-realtime ms of the last frame that reached the renderer. */
    val lastFrameAtMs: Long

    /** Elapsed-realtime ms this source last started or resumed. */
    val startedAtMs: Long

    /** Frames that reached the renderer since that start. */
    val renderedFrames: Long

    /** A paused source produces nothing on purpose; the watchdog must skip it. */
    val isPaused: Boolean

    /**
     * How long the frame stream may be silent before the overlay is wiped
     * transparent.
     *
     * Per source, because silence means different things. A mirror that stops
     * delivering is a fault, and ten seconds is a generous way to say so.
     * The shim goes quiet whenever RetroArch's menu opens - the menu pauses the
     * core, retro_run stops, and no frames exist to send - which is a NORMAL
     * state, and the one that lets a full-screen opaque overlay get out of the
     * way so the player can reach save, load and exit (§4.7). Ten seconds of
     * enhanced picture frozen over a menu the player is trying to use is not an
     * option, so the shim asks for a quarter of a second.
     *
     * This is not the same timer as the first-frame timeout, which is a fault
     * and does stop the service.
     */
    val frameStallTimeoutMs: Long

    /**
     * How long to wait for the very FIRST frame before giving up, or 0 for
     * never.
     *
     * Per source, because "no frames yet" means opposite things. A screen
     * mirror that has produced nothing in four seconds is broken: the screen
     * always has something on it, so frames arrive the instant it starts.
     *
     * The direct source has nothing to give until RetroArch has started, loaded
     * a core and run it, which takes longer than four seconds on a launch and
     * longer still when changing console - and it has nothing to give at all
     * while the player is in the frontend rather than in a game, which is a
     * perfectly ordinary state to sit in. Treating that as a dead pipeline
     * killed the service mid-launch every time a game was opened.
     */
    val firstFrameTimeoutMs: Long

    fun pauseCapture()

    fun resumeCapture(): Boolean

    /**
     * Hands the EGL context back from the thread that holds it. Blocking:
     * every caller's next step is to touch the renderer.
     */
    fun detachEglContext()

    /**
     * Runs [block] on the thread that owns the EGL context, and waits.
     * Returns false when there is no such thread, so callers can fall back.
     */
    fun runOnCaptureThread(block: () -> Unit): Boolean

    fun stopCapture()
}
