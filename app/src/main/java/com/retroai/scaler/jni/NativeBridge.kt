package com.retroai.scaler.jni

import android.hardware.HardwareBuffer
import android.view.Surface

/**
 * JNI Bridge to C++ Zero-Copy NCNN AI & OpenGL ES Rendering Engine
 */
class NativeBridge {

    companion object {
        init {
            System.loadLibrary("retro_ai_scaler")
        }
    }

    external fun nativeInit(surface: Surface, screenWidth: Int, screenHeight: Int): Boolean

    external fun nativeProcessHardwareBuffer(buffer: HardwareBuffer): Boolean

    /**
     * Source = the small 1x RetroArch window we sample from.
     * Output = where the enhanced image is painted.
     * They must not overlap: the source rect is punched out of the output so
     * our own output never re-enters the screen capture.
     */
    external fun nativeSetGeometry(
        srcX: Int, srcY: Int, srcW: Int, srcH: Int,
        outX: Int, outY: Int, outW: Int, outH: Int,
        showSourceGuide: Boolean,
        /** False under single-app capture: the output may cover the source. */
        protectSource: Boolean
    )

    external fun nativeSetRenderConfig(
        isAiEnabled: Boolean,
        consoleNativeWidth: Int,
        consoleNativeHeight: Int,
        scanlineIntensity: Float,
        lcdGridIntensity: Float
    )

    /**
     * Loads ncnn ESPCN weights read from assets.
     * @param paramText contents of the .param file
     * @param binData contents of the matching .bin file
     */
    external fun nativeLoadEspcnModel(
        paramText: String,
        binData: ByteArray,
        scaleFactor: Int,
        preferGpu: Boolean,
        /** 1 for the luminance ESPCN models, 3 for RGB input. */
        inChannels: Int,
        /** 1 for luminance or depth output, 3 for RetroAI's RGB. */
        outChannels: Int
    ): Boolean

    /** Drops the network; rendering falls back to the GPU shader upscaler. */
    external fun nativeUnloadEspcnModel()

    /** Measures where the emulator actually draws (async, a few frames). */
    external fun nativeRequestSourceDetection(expectedWidth: Int, expectedHeight: Int)

    /** rectOut = [x, y, w, h]. False until a measurement has landed. */
    external fun nativeGetDetectedRect(rectOut: IntArray): Boolean

    /** Stops all painting while the target app is off screen. */
    external fun nativeSetRenderPaused(paused: Boolean)

    /** Wipes the overlay to fully transparent. Safety valve for the watchdog. */
    external fun nativeClearOverlay(): Boolean

    /** Must be called ON the capture thread before that thread exits. */
    external fun nativeDetachEglContext()

    /** 0 none, 1 aperture grille, 2 shadow mask, 3 slot mask. */
    external fun nativeSetMaskType(maskType: Int)

    /** Scale2x-style edge reconstruction (no network). */
    external fun nativeSetPixelEdge(enabled: Boolean)

    external fun nativeSetAutoCrop(enabled: Boolean)
    external fun nativeRecalibrateCrop()

    /**
     * Asks the renderer to time the loaded network back to back for ~3 s.
     *
     * Non-blocking - it raises a flag and returns. Watch `aiCalState` in
     * [nativeGetPerformanceStats] for progress (0 idle, 1 running, 2 done).
     * A no-op when no network is loaded or a run is already in flight.
     */
    external fun nativeCalibrateAi()

    /**
     * statsOut must hold 16 floats:
     * `[fps, captureMs, aiMs, renderMs, swapMs, aiBackend, aiP95Ms, aiGpuMs,
     *   aiCpuMs, aiFloorMs, aiCalState, aiCalMinMs, aiCalRunMinMs, aiCalMedMs,
     *   aiCalMaxMs, aiCalRuns]`
     *
     * `aiCalMinMs` accumulates across presses; `aiCalRunMinMs` is the last
     * burst alone.
     *
     * The AI costs are MEDIANS over a rolling window of finished inferences,
     * not the last result - see PerformanceStats in gl_renderer.h for why a
     * spot reading of that quantity is meaningless. `aiGpuMs` is ncnn's
     * extract, `aiCpuMs` the pixel-format work either side of it.
     *
     * Only the `aiCal*` numbers may be compared across consoles - everything
     * else is at the mercy of whichever clock state the GPU happened to be in.
     *
     * Returns false if the array is shorter than 16.
     */
    external fun nativeGetPerformanceStats(statsOut: FloatArray): Boolean

    /**
     * Starts the capture-mode probe. Needs frames to be flowing; the result
     * shows up a handful of frames later via [nativeGetCaptureMode].
     */
    /**
     * Grabs one frame of the capture window at exactly native resolution, for
     * building the training corpus. Lossless: the source rect is an integer
     * multiple of the native size, so sampling block centres returns the
     * emulator's own pixels.
     */
    /** Depth-driven HD-2D lighting on top of the selected upscaler. */
    external fun nativeSetHd2d(enabled: Boolean, strength: Float)

    /**
     * Tilt-shift focus band, 0..1. Deliberately separate from HD-2D: it needs
     * no depth, so it works with the network off.
     */
    external fun nativeSetDof(strength: Float)

    /** Highlight bleed, 0..1. A lens effect like the focus band - no depth. */
    external fun nativeSetBloom(strength: Float)

    external fun nativeRequestFrameCapture()

    /** RGBA bytes, top row first, or null if nothing is ready. Fills sizeOut with {w, h}. */
    external fun nativeFetchCapturedFrame(sizeOut: IntArray): ByteArray?

    external fun nativeRequestCaptureModeProbe()

    /** -1 still unknown, 0 single-app capture, 1 whole-screen capture. */
    external fun nativeGetCaptureMode(): Int

    external fun nativeRelease()
}
