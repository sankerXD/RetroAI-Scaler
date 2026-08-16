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
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensityDpi: Int,
    private val nativeBridge: NativeBridge,
    private val onProjectionStopped: () -> Unit
) {
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

        // Use basic ImageReader WITHOUT custom HardwareBuffer usage flags.
        // On MediaTek SoCs, custom GPU usage flags cause format mismatch:
        //   Producer outputs 0x22 (IMPLEMENTATION_DEFINED)
        //   but ImageReader expects 0x1 (RGBA_8888)
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2  // double-buffering for low latency
        )

        var frameCounter = 0
        var errorCounter = 0
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isCapturing) return@setOnImageAvailableListener
            var image: android.media.Image? = null
            try {
                image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
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

        return if (createVirtualDisplay()) {
            isCapturing = true
            Log.i(TAG, "Capture pipeline started: ${screenWidth}x${screenHeight} @ ${screenDensityDpi}dpi")
            true
        } else {
            stopCapture()
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

        // The EGL context is current on the capture thread. It has to be
        // unbound FROM that thread, otherwise teardown on the main thread
        // hits EGL_BAD_ACCESS and the last rendered frame stays on screen.
        val handler = captureHandler
        if (handler != null) {
            val latch = CountDownLatch(1)
            val posted = handler.post {
                try {
                    nativeBridge.nativeDetachEglContext()
                } finally {
                    latch.countDown()
                }
            }
            if (posted) {
                latch.await(500, TimeUnit.MILLISECONDS)
            }
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.i(TAG, "Capture pipeline stopped.")
    }
}
