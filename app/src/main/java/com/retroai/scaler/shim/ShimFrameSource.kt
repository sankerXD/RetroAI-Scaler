package com.retroai.scaler.shim

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.retroai.scaler.capture.FrameSource
import com.retroai.scaler.jni.NativeBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Feeds the renderer from the libretro shim instead of the screen.
 *
 * Frames arrive on [ShimFrameService]'s socket thread and are rendered on this
 * class's own thread, which is the one that ends up owning the EGL context.
 * Two threads rather than one because the socket thread spends its life blocked
 * in a read: nothing can be posted to it, and [runOnCaptureThread] - which
 * every GL teardown in the service depends on - would have nowhere to run.
 *
 * The hand-off keeps only the newest frame. Frames can arrive far faster than
 * the display can show them: fast-forward was measured at 263fps against a
 * 60Hz panel, and a queue would spend the whole time rendering pictures that
 * were already stale. Two buffers swap under a lock, the render thread takes
 * whatever is latest when it gets there, and everything in between is dropped -
 * which is the same policy the shim's own ring uses at the other end, for the
 * same reason.
 */
class ShimFrameSource(
    private val nativeBridge: NativeBridge,
    /**
     * Reports the native resolution of the frames, first time and on every
     * change. A console changes video mode at runtime - SFC between 256x224
     * and 512x448, PS1 across several - and the resolution is what the
     * renderer samples on, so following it is not cosmetic.
     */
    private val onNativeSize: (width: Int, height: Int) -> Unit
) : FrameSource {

    companion object {
        private const val TAG = "RetroAI_ShimSource"
    }

    /**
     * A quarter of a second. RetroArch pauses the core whenever its menu is
     * open, so the frame stream stopping is the signal that the player is in a
     * menu and needs to see it - see FrameSource.frameStallTimeoutMs.
     */
    override val frameStallTimeoutMs: Long = 250L

    /**
     * Never. Waiting for the emulator is what this source does between games,
     * and there is no length of silence that means it has broken.
     */
    override val firstFrameTimeoutMs: Long = 0L

    @Volatile override var lastFrameAtMs: Long = 0L
        private set
    @Volatile override var startedAtMs: Long = 0L
        private set
    @Volatile override var renderedFrames: Long = 0L
        private set
    @Volatile override var isPaused: Boolean = false
        private set

    private val thread = HandlerThread("ShimFrameSource").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var running = true
    private var reportedWidth = 0
    private var reportedHeight = 0

    private val lock = Object()
    private var spare: ByteArray? = null
    private var ready: ByteArray? = null
    private var readyWidth = 0
    private var readyHeight = 0
    private var readyPitch = 0
    private var readyFormat = 0
    private var posted = false

    fun start() {
        startedAtMs = SystemClock.elapsedRealtime()
        lastFrameAtMs = startedAtMs
        renderedFrames = 0L
        ShimFrameService.frameListener = ShimFrameService.FrameListener(::submit)
        Log.i(TAG, "shim frame source started")
    }

    /**
     * Called on the socket thread. [data] is the receiver's own buffer and is
     * reused the moment this returns, so the frame is copied here rather than
     * referenced - 77KB at 60Hz, against a hand-off that would otherwise need
     * the reader to stall until the renderer was done with it.
     */
    private fun submit(data: ByteArray, width: Int, height: Int, pitch: Int, format: Int) {
        if (!running || isPaused) return
        val bytes = pitch * height
        if (bytes <= 0 || bytes > data.size) return

        synchronized(lock) {
            var buf = spare
            if (buf == null || buf.size < bytes) buf = ByteArray(bytes)
            System.arraycopy(data, 0, buf, 0, bytes)
            // Whatever was waiting is now stale; it becomes the spare and this
            // frame takes its place.
            spare = ready
            ready = buf
            readyWidth = width
            readyHeight = height
            readyPitch = pitch
            readyFormat = format
            if (!posted) {
                posted = true
                handler.post(::renderLatest)
            }
        }
    }

    private fun renderLatest() {
        val buf: ByteArray?
        val w: Int
        val h: Int
        val pitch: Int
        val format: Int
        synchronized(lock) {
            buf = ready
            w = readyWidth
            h = readyHeight
            pitch = readyPitch
            format = readyFormat
            ready = null
            posted = false
        }
        if (buf == null || !running || isPaused) return

        if (w != reportedWidth || h != reportedHeight) {
            reportedWidth = w
            reportedHeight = h
            onNativeSize(w, h)
        }

        if (nativeBridge.nativeProcessShimFrame(buf, w, h, pitch, format)) {
            lastFrameAtMs = SystemClock.elapsedRealtime()
            renderedFrames++
        }
        synchronized(lock) {
            // Hand the buffer back for reuse rather than letting it be garbage:
            // at 60Hz a fresh 77KB array per frame is 4.6MB/s of allocation for
            // no reason.
            if (spare == null) spare = buf
        }
    }

    override fun pauseCapture() {
        if (isPaused) return
        isPaused = true
        Log.i(TAG, "paused - frames dropped at the door, socket left connected")
    }

    override fun resumeCapture(): Boolean {
        if (!isPaused) return true
        // Reset the counters: the watchdog has to judge the pipeline from the
        // moment it actually restarted, not from when the service began.
        startedAtMs = SystemClock.elapsedRealtime()
        lastFrameAtMs = startedAtMs
        renderedFrames = 0L
        isPaused = false
        Log.i(TAG, "resumed")
        return true
    }

    override fun detachEglContext() {
        runOnCaptureThread { nativeBridge.nativeDetachEglContext() }
    }

    override fun runOnCaptureThread(block: () -> Unit): Boolean {
        if (!thread.isAlive) return false
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return latch.await(500, TimeUnit.MILLISECONDS)
    }

    override fun stopCapture() {
        running = false
        ShimFrameService.frameListener = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        try {
            thread.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            ready = null
            spare = null
        }
        Log.i(TAG, "shim frame source stopped after $renderedFrames frames")
    }
}
