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
        preferGpu: Boolean
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

    /** statsOut must hold 5 floats: [fps, captureMs, aiMs, renderMs, swapMs] */
    external fun nativeGetPerformanceStats(statsOut: FloatArray): Boolean

    /**
     * Starts the capture-mode probe. Needs frames to be flowing; the result
     * shows up a handful of frames later via [nativeGetCaptureMode].
     */
    external fun nativeRequestCaptureModeProbe()

    /** -1 still unknown, 0 single-app capture, 1 whole-screen capture. */
    external fun nativeGetCaptureMode(): Int

    external fun nativeRelease()
}
