package com.retroai.scaler.capture

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.retroai.scaler.jni.NativeBridge
import com.retroai.scaler.ui.ConsoleType
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes native-resolution frames to disk to build the model's training corpus.
 *
 * The frames come straight off the emulator at its own resolution, which is the
 * whole point: anything sampled from the screen has already been through
 * RetroArch's scaler and is useless as ground truth. Training on stretched
 * frames teaches a network to reproduce somebody else's interpolation.
 */
class DatasetRecorder(
    private val context: Context,
    private val nativeBridge: NativeBridge
) {
    companion object {
        private const val TAG = "DatasetRecorder"

        /**
         * How different a frame has to be from the last one kept, as a mean
         * absolute difference over a 16x16 grey thumbnail (0..255).
         *
         * Near-identical frames are worse than useless in a training set: they
         * cost disk and epochs while teaching nothing, and they quietly bias
         * the model towards whatever screen the player happened to idle on.
         */
        private const val MIN_DIFFERENCE = 6.0

        /**
         * A frame flatter than this carries nothing to learn from. Fades and
         * loading screens are solid colour, and several turned up in the very
         * first capture session - they cost disk and epochs while teaching
         * nothing, and they pull the corpus towards black.
         */
        private const val MIN_CONTRAST = 12

        private const val THUMB = 16

        private const val MAX_POLLS = 40
        private const val POLL_INTERVAL_MS = 25L
    }

    /** Last kept frame, reduced to a grey thumbnail for the novelty test. */
    private var lastThumb: IntArray? = null

    private val outputRoot: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            "RetroAIScaler/dataset"
        )

    data class Result(val saved: Boolean, val message: String)

    /**
     * Asks the renderer for a frame and writes it out.
     *
     * Blocking on the caller's thread - it polls for a few frames while the
     * render thread services the request, so it must not run on the main
     * thread.
     */
    fun captureOnce(console: ConsoleType, skipSimilar: Boolean): Result {
        nativeBridge.nativeRequestFrameCapture()

        val size = IntArray(2)
        var pixels: ByteArray? = null
        // The grab happens on the next rendered frame; at 60-144 Hz a handful
        // of short waits is far more than enough, and bailing out is better
        // than blocking a UI action indefinitely if the pipeline is paused.
        //
        // A plain loop, because repeat's `return@repeat` is continue rather
        // than break: it kept polling after a successful fetch, and since the
        // renderer hands each frame over exactly once, the very next call
        // returned null and overwrote the result. Every capture succeeded and
        // was then thrown away.
        for (attempt in 0 until MAX_POLLS) {
            val frame = nativeBridge.nativeFetchCapturedFrame(size)
            if (frame != null) {
                pixels = frame
                break
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        val data = pixels ?: run {
            Log.w(TAG, "no frame after ${MAX_POLLS * POLL_INTERVAL_MS} ms")
            return Result(false, "没有取到画面（游戏未在前台？）")
        }

        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0 || data.size < w * h * 4) {
            return Result(false, "画面尺寸异常 ${w}x${h}")
        }

        val thumb = greyThumbnail(data, w, h)
        if (thumb.max() - thumb.min() < MIN_CONTRAST) {
            return Result(false, "画面几乎是纯色（载入/淡出？），已跳过")
        }
        if (skipSimilar) {
            val previous = lastThumb
            if (previous != null && meanAbsDiff(previous, thumb) < MIN_DIFFERENCE) {
                return Result(false, "画面与上一张几乎相同，已跳过")
            }
        }

        return try {
            val file = write(data, w, h, console)
            lastThumb = thumb
            Log.i(TAG, "saved ${file.absolutePath}")
            Result(true, "已保存 ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "saving frame failed", e)
            Result(false, "保存失败：${e.message}")
        }
    }

    private fun write(data: ByteArray, w: Int, h: Int, console: ConsoleType): File {
        val dir = File(outputRoot, console.name)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${dir.absolutePath}")
        }

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))

        // The console and its native size go in the name so the corpus stays
        // self-describing once the files are off the device.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "${console.name}_${w}x${h}_$stamp.png")
        FileOutputStream(file).use { out ->
            // PNG, and never JPEG: compression artefacts in the ground truth
            // become artefacts the network is trained to reproduce.
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    /** Count of frames already collected, for the UI. */
    fun countFor(console: ConsoleType): Int =
        File(outputRoot, console.name).listFiles { f -> f.extension == "png" }?.size ?: 0

    fun outputPathFor(console: ConsoleType): String =
        File(outputRoot, console.name).absolutePath

    private fun greyThumbnail(data: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(THUMB * THUMB)
        for (ty in 0 until THUMB) {
            for (tx in 0 until THUMB) {
                val x = (tx * w / THUMB).coerceIn(0, w - 1)
                val y = (ty * h / THUMB).coerceIn(0, h - 1)
                val i = (y * w + x) * 4
                val r = data[i].toInt() and 0xFF
                val g = data[i + 1].toInt() and 0xFF
                val b = data[i + 2].toInt() and 0xFF
                out[ty * THUMB + tx] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }
        return out
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }
}
