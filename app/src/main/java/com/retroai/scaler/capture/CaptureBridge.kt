package com.retroai.scaler.capture

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.retroai.scaler.jni.NativeBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages MediaProjection, VirtualDisplay, and ImageReader buffer acquisition pipeline.
 *
 * NOTE: On MediaTek Helio G99 (Android 11), specifying custom HardwareBuffer usage flags
 * causes the GPU driver to output IMPLEMENTATION_DEFINED (0x22) format instead of RGBA_8888,
 * which makes ImageReader.acquireLatestImage() throw UnsupportedOperationException.
 * We must use the basic ImageReader.newInstance() without custom usage flags.
 */
class CaptureBridge(
    private val mediaProjection: MediaProjection,
    screenWidth: Int,
    screenHeight: Int,
    screenDensityDpi: Int,
    private val nativeBridge: NativeBridge,
    private val onProjectionStopped: () -> Unit
) {
    // Mutable because the display can rotate under us: a portrait-native panel
    // swaps 1920x1080 <-> 1080x1920 and both the mirror and the reader have to
    // follow, or the capture keeps arriving letterboxed at the old aspect.
    private var screenWidth = screenWidth
    private var screenHeight = screenHeight
    private var screenDensityDpi = screenDensityDpi

    companion object {
        private const val TAG = "CaptureBridge"
        private const val VIRTUAL_DISPLAY_NAME = "RetroAI_Capture_Display"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var isCapturing = false

    @Volatile
    var isPaused = false
        private set

    /** Elapsed-realtime of the last frame that actually reached native. */
    @Volatile
    var lastFrameAtMs: Long = 0L
        private set

    /** Elapsed-realtime of startCapture(), used to detect a pipeline that never starts. */
    @Volatile
    var startedAtMs: Long = 0L
        private set

    /** Frames successfully rendered since start. */
    @Volatile
    var renderedFrames: Long = 0L
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by the system or the user.")
            onProjectionStopped()
        }

        // DIAGNOSTIC ONLY - log, no behaviour attached yet.
        //
        // These two arrive from API 34 and, as far as the docs describe them,
        // only for a single-app capture: they report the captured app's content
        // size and whether it is on screen. Whole-screen capture has no
        // "captured content" separate from the display, so it should stay
        // silent. If that holds, this is how the service can tell which mode
        // the user actually picked in the consent dialog - the dialog offers
        // both and there is no API to force the single-app one, while the two
        // modes need different geometry.
        //
        // Overriding is safe below API 34: the framework simply never calls
        // them.
        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.i(TAG, "CAPTURE-MODE-PROBE onCapturedContentResize ${width}x${height}")
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.i(TAG, "CAPTURE-MODE-PROBE onCapturedContentVisibilityChanged visible=$isVisible")
        }
    }

    @SuppressLint("WrongConstant")
    fun startCapture(): Boolean {
        if (isCapturing) return true

        captureThread = HandlerThread("CapturePipelineThread", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
        }
        captureHandler = Handler(captureThread!!.looper)

        // Required since Android 14, harmless before: without a registered
        // callback createVirtualDisplay() throws IllegalStateException.
        mediaProjection.registerCallback(projectionCallback, captureHandler)

        imageReader = createImageReader()

        return if (createVirtualDisplay()) {
            isCapturing = true
            Log.i(TAG, "Capture pipeline started: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")
            true
        } else {
            stopCapture()
            false
        }
    }

    /**
     * Use basic ImageReader WITHOUT custom HardwareBuffer usage flags.
     * On MediaTek SoCs, custom GPU usage flags cause format mismatch:
     *   Producer outputs 0x22 (IMPLEMENTATION_DEFINED)
     *   but ImageReader expects 0x1 (RGBA_8888)
     */
    private fun createImageReader(): ImageReader {
        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2  // double-buffering for low latency
        )
        var frameCounter = 0
        var errorCounter = 0
        reader.setOnImageAvailableListener({ r ->
            if (!isCapturing) return@setOnImageAvailableListener
            var image: android.media.Image? = null
            try {
                image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val hardwareBuffer = image.hardwareBuffer
                if (hardwareBuffer != null) {
                    try {
                        if (nativeBridge.nativeProcessHardwareBuffer(hardwareBuffer)) {
                            lastFrameAtMs = SystemClock.elapsedRealtime()
                            renderedFrames++
                        }
                    } finally {
                        hardwareBuffer.close()
                    }
                    if (++frameCounter % 300 == 1) {
                        Log.i(TAG, "Frame #$frameCounter processed")
                    }
                }
            } catch (e: Exception) {
                if (errorCounter++ % 60 == 0) {
                    Log.e(TAG, "Error processing frame", e)
                }
            } finally {
                image?.close()
            }
        }, captureHandler)
        return reader
    }

    /**
     * Follows a display rotation without touching the projection itself.
     *
     * The VirtualDisplay is resized in place rather than released and rebuilt:
     * a MediaProjection's consent is tied to the session, and re-running
     * createVirtualDisplay() on an existing projection is not something to rely
     * on across Android versions. resize() has been public since API 21 and
     * keeps the same mirror alive.
     *
     * The ImageReader cannot be resized, so a new one is built at the new size
     * and swapped in; the old one is closed only after the display points at
     * the replacement, so no frame is delivered into a closed reader.
     */
    fun resizeTo(newWidth: Int, newHeight: Int, newDensityDpi: Int): Boolean {
        if (!isCapturing) return false
        if (newWidth == screenWidth && newHeight == screenHeight) return true

        return try {
            screenWidth = newWidth
            screenHeight = newHeight
            screenDensityDpi = newDensityDpi

            val oldReader = imageReader
            val newReader = createImageReader()
            imageReader = newReader

            virtualDisplay?.resize(newWidth, newHeight, newDensityDpi)
            // While paused the display deliberately holds no surface; leave it
            // that way so resumeCapture() is still the one thing that starts
            // frames flowing again.
            if (!isPaused) {
                virtualDisplay?.surface = newReader.surface
            }

            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()

            // The watchdog judges liveness from these, and the pipeline just
            // restarted - it must not read the gap as a dead pipeline.
            startedAtMs = SystemClock.elapsedRealtime()
            lastFrameAtMs = startedAtMs
            renderedFrames = 0L

            Log.i(TAG, "Capture resized to ${newWidth}x${newHeight} @ ${newDensityDpi}dpi")
            true
        } catch (e: Exception) {
            Log.e(TAG, "resizeTo failed", e)
            false
        }
    }

    private fun createVirtualDisplay(): Boolean {
        return try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                captureHandler
            )
            startedAtMs = SystemClock.elapsedRealtime()
            lastFrameAtMs = startedAtMs
            renderedFrames = 0L
            true
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            false
        }
    }

    /**
     * Detaches the output surface while the target app is off screen, which
     * stops the mirror from being composed at all - SurfaceFlinger sat at ~31%
     * CPU producing frames nobody used.
     *
     * setSurface(null) is the supported way to idle a VirtualDisplay. Releasing
     * and recreating it does NOT work: the recreated display never delivers a
     * frame into the existing ImageReader surface, and the frame watchdog then
     * (correctly) concludes the pipeline is dead and shuts the service down.
     */
    fun pauseCapture() {
        if (!isCapturing || isPaused) return
        isPaused = true
        try {
            virtualDisplay?.surface = null
            Log.i(TAG, "Capture paused (VirtualDisplay surface detached).")
        } catch (e: Exception) {
            Log.w(TAG, "pauseCapture failed", e)
            isPaused = false
        }
    }

    fun resumeCapture(): Boolean {
        if (!isCapturing || !isPaused) return true
        return try {
            virtualDisplay?.surface = imageReader?.surface
            // Reset the stall counters: the watchdog must judge the pipeline
            // from the moment it actually restarted.
            startedAtMs = SystemClock.elapsedRealtime()
            lastFrameAtMs = startedAtMs
            renderedFrames = 0L
            isPaused = false
            Log.i(TAG, "Capture resumed (surface reattached).")
            true
        } catch (e: Exception) {
            Log.e(TAG, "resumeCapture failed", e)
            false
        }
    }

    /**
     * Hands the EGL context back from the capture thread.
     *
     * The context is current on that thread, and EGL only lets one thread hold
     * it. It therefore has to be unbound FROM that thread - doing the teardown
     * (or a rebuild) from the main thread while it is still bound here fails
     * with EGL_BAD_ACCESS, and the last rendered frame stays on screen.
     *
     * Blocking, because every caller's next step is to touch the renderer.
     */
    fun detachEglContext() {
        runOnCaptureThread { nativeBridge.nativeDetachEglContext() }
    }

    /**
     * Runs [block] on the capture thread and waits for it.
     *
     * Anything that touches GL belongs here rather than on the caller's thread.
     * The EGL context lives on the capture thread and stays current between
     * frames, so driving GL from elsewhere means migrating the context back and
     * forth - which is both racy and, in practice, a way to end up drawing
     * successfully into something that no longer reaches the screen.
     *
     * Returns false when there is no capture thread to run on, so callers can
     * fall back to doing it inline.
     */
    fun runOnCaptureThread(block: () -> Unit): Boolean {
        val handler = captureHandler ?: return false
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

    fun stopCapture() {
        isCapturing = false

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterCallback failed", e)
        }

        detachEglContext()

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.i(TAG, "Capture pipeline stopped.")
    }
}
