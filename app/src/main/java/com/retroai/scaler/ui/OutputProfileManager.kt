package com.retroai.scaler.ui

import android.content.Context
import android.graphics.Rect

/**
 * Console presets. Native resolutions are what the emulator core actually
 * outputs - everything downstream (capture window size, integer output factor,
 * the AI network's input) is derived from these numbers.
 */
enum class ConsoleType(
    val displayName: String,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val aspectRatioName: String
) {
    GBA("GBA", 240, 160, "3:2"),
    GBC("GBC/GB", 160, 144, "10:9"),
    SFC("SFC/SNES", 256, 224, "8:7"),
    FC("FC/NES", 256, 240, "4:3"),
    MD("MD/Genesis", 320, 224, "4:3"),
    PS1("PS1/街机", 320, 240, "4:3");

    val aspectRatio: Float
        get() = nativeWidth.toFloat() / nativeHeight.toFloat()
}

/**
 * Which screen corner hosts RetroArch's small native capture window.
 *
 * The capture source and the enhanced output MUST NOT overlap: MediaProjection
 * mirrors the whole display including our own overlay, so painting on top of
 * the area we sample from would feed our output straight back into the capture.
 */
enum class SourceCorner(val displayName: String) {
    TOP_LEFT("左上"),
    TOP_RIGHT("右上"),
    BOTTOM_LEFT("左下"),
    BOTTOM_RIGHT("右下");

    /**
     * RetroArch's video_viewport_bias_* are normalised 0..1, which is why they
     * are used instead of custom_viewport_x/y: no coordinate-origin ambiguity
     * between video drivers. Whether bias_y=0 means top or bottom still differs
     * per driver, but that no longer matters - the real position is measured
     * off a captured frame afterwards.
     */
    val biasX: Float
        get() = if (this == TOP_LEFT || this == BOTTOM_LEFT) 0.0f else 1.0f

    val biasY: Float
        get() = if (this == TOP_LEFT || this == TOP_RIGHT) 0.0f else 1.0f
}

/**
 * Which upscaler runs. SHADER needs no weights; the ESPCN variants load a
 * .param/.bin pair whose name also encodes the AI factor.
 */
enum class UpscaleEngine(val displayName: String, val assetVariant: String?) {
    /**
     * Scale2x-style edge reconstruction. For 2D sprite art this beats the
     * network outright: pixel art has no lost detail to recover, it has
     * staircases that should have been diagonals, and that is a comparison
     * problem rather than a regression problem.
     */
    PIXEL_EDGE("像素边缘重建", null),
    SHADER("GPU 锐化", null),
    ESPCN_FAST("ESPCN Fast", "fast"),
    ESPCN_HQ("ESPCN HQ", "hq"),

    /**
     * ~30x the arithmetic of HQ. Only viable with ncnn on Vulkan, i.e. a recent
     * mobile GPU; on an older handheld it falls back to CPU and will not keep
     * up, which the UI says out loud rather than silently stuttering.
     */
    ESPCN_ULTRA("ESPCN Ultra (需高端 GPU)", "ultra");

    val usesNetwork: Boolean get() = assetVariant != null
    /** Ultra is unusable without GPU inference. */
    val needsGpu: Boolean get() = this == ESPCN_ULTRA
    val isPixelEdge: Boolean get() = this == PIXEL_EDGE
}

/**
 * How far the network reconstructs, i.e. the resolution of the luminance it
 * rebuilds. Independent of how large the picture ends up on screen - that is
 * decided by the largest integer multiple that fits the free space.
 */
/** CRT mask geometry, indexed by screen pixel like the real thing. */
enum class MaskType(val id: Int, val displayName: String) {
    NONE(0, "关闭"),
    APERTURE(1, "光栅"),
    SHADOW(2, "荫罩"),
    SLOT(3, "狭缝")
}

enum class AiScale(val factor: Int, val label: String) {
    X1(1, "1x"),
    X2(2, "2x"),
    X3(3, "3x"),
    X4(4, "4x")
}

data class RenderProfile(
    var console: ConsoleType = ConsoleType.GBA,
    var isAiEnabled: Boolean = true,
    var engine: UpscaleEngine = UpscaleEngine.PIXEL_EDGE,
    /** AI reconstruction factor. 1x means no network, shader sharpening only. */
    var aiScale: AiScale = AiScale.X3,
    var scanlineIntensity: Float = 0.0f, // 0.0 ~ 1.0
    var lcdGridIntensity: Float = 0.0f,  // mask strength
    var maskType: MaskType = MaskType.NONE,

    // Capture source window (where RetroArch draws its raw native picture)
    var sourceCorner: SourceCorner = SourceCorner.BOTTOM_RIGHT,
    var sourceScale: Int = 1,
    var sourceMarginPx: Int = 8,
    var showSourceGuide: Boolean = false,
    /** Disable RetroArch's own shader when writing its config. */
    var disableRaShader: Boolean = true,
    /** Keep the output clear of the capture window. */
    var avoidSourceOverlap: Boolean = true,
    /**
     * Where the emulator was actually measured to be drawing. Takes priority
     * over the computed corner: RetroArch's placement depends on its video
     * driver and on the repack's own overrides, so measuring beats predicting.
     */
    var detectedSourceRect: Rect? = null
) {
    /** Screen-space rect the overlay samples from. */
    fun getSourceRect(screenWidth: Int, screenHeight: Int): Rect {
        detectedSourceRect?.let { return it }
        return getPlannedSourceRect(screenWidth, screenHeight)
    }

    /** Where we ASK RetroArch to draw (before measuring). */
    fun getPlannedSourceRect(screenWidth: Int, screenHeight: Int): Rect {
        val w = console.nativeWidth * sourceScale
        val h = console.nativeHeight * sourceScale
        val m = sourceMarginPx
        val left = when (sourceCorner) {
            SourceCorner.TOP_LEFT, SourceCorner.BOTTOM_LEFT -> m
            SourceCorner.TOP_RIGHT, SourceCorner.BOTTOM_RIGHT -> screenWidth - w - m
        }.coerceAtLeast(0)
        val top = when (sourceCorner) {
            SourceCorner.TOP_LEFT, SourceCorner.TOP_RIGHT -> m
            SourceCorner.BOTTOM_LEFT, SourceCorner.BOTTOM_RIGHT -> screenHeight - h - m
        }.coerceAtLeast(0)
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Where the enhanced picture is painted: the largest EXACT INTEGER multiple
     * of the native resolution that fits in the free space beside the capture
     * window, computed per device instead of hard coded.
     *
     * Integer only, and that is the whole point. A fractional factor (the old
     * "fit to screen" mode produced 3.975x on this handheld) means one game
     * pixel does not land on a whole number of output pixels, so every pixel
     * edge falls mid-pixel and gets interpolated - which is what made the
     * picture look soft and stopped it reading as pixel art.
     */
    fun getOutputRect(screenWidth: Int, screenHeight: Int): Rect {
        val src = getSourceRect(screenWidth, screenHeight)
        val gap = 8

        val bands = if (avoidSourceOverlap) {
            listOf(
                Rect(0, 0, screenWidth, (src.top - gap).coerceAtLeast(0)),
                Rect(0, (src.bottom + gap).coerceAtMost(screenHeight), screenWidth, screenHeight),
                Rect(0, 0, (src.left - gap).coerceAtLeast(0), screenHeight),
                Rect((src.right + gap).coerceAtMost(screenWidth), 0, screenWidth, screenHeight)
            )
        } else {
            listOf(Rect(0, 0, screenWidth, screenHeight))
        }

        var best: Rect? = null
        var bestScale = 0

        for (band in bands) {
            if (band.width() <= 0 || band.height() <= 0) continue
            val k = minOf(
                band.width() / console.nativeWidth,
                band.height() / console.nativeHeight
            )
            if (k < 1 || k <= bestScale) continue

            bestScale = k
            val w = console.nativeWidth * k
            val h = console.nativeHeight * k
            val left = band.left + (band.width() - w) / 2
            val top = band.top + (band.height() - h) / 2
            best = Rect(left, top, left + w, top + h)
        }

        // Nothing fits even at 1x (tiny screen, oversized capture window):
        // fall back to the whole screen so something is still shown.
        return best ?: Rect(0, 0, screenWidth, screenHeight)
    }

    /** Integer factor currently in use, for the UI. */
    fun getOutputScale(screenWidth: Int, screenHeight: Int): Int =
        (getOutputRect(screenWidth, screenHeight).width() / console.nativeWidth).coerceAtLeast(1)

    fun getSummaryText(screenWidth: Int, screenHeight: Int): String {
        val out = getOutputRect(screenWidth, screenHeight)
        val k = getOutputScale(screenWidth, screenHeight)
        val src = getSourceRect(screenWidth, screenHeight)
        val origin = if (detectedSourceRect != null) "已探测" else sourceCorner.displayName
        return "${console.displayName} ${console.nativeWidth}×${console.nativeHeight} " +
                "→ ${out.width()}×${out.height()}（整数 ${k}x）\n" +
                "取景窗 ${src.width()}×${src.height()} @ (${src.left}, ${src.top}) $origin"
    }

    /**
     * assets/models/espcn_y_<factor>x_<variant>.{param,bin}, or null when the
     * network is not used (shader engine, or 1x where there is nothing to
     * reconstruct).
     */
    fun modelAssetBaseName(): String? {
        val variant = engine.assetVariant ?: return null
        if (aiScale == AiScale.X1) return null
        return "espcn_y_${aiScale.factor}x_$variant"
    }

    /** The lines RetroArch needs, shown in the menu and copyable. */
    fun getRetroArchViewportConfig(screenWidth: Int, screenHeight: Int): String {
        return buildString {
            appendLine("aspect_ratio_index = \"23\"")
            appendLine("video_scale_integer = \"true\"")
            appendLine("custom_viewport_width = \"${console.nativeWidth * sourceScale}\"")
            appendLine("custom_viewport_height = \"${console.nativeHeight * sourceScale}\"")
            appendLine("video_viewport_bias_x = \"%.1f\"".format(sourceCorner.biasX))
            appendLine("video_viewport_bias_y = \"%.1f\"".format(sourceCorner.biasY))
            append("input_overlay_enable = \"false\"")
        }
    }
}

/**
 * Survives across sessions so the service can configure RetroArch on start,
 * before the user has opened the floating menu at all.
 */
object ProfilePreference {
    private const val PREFS = "retro_ai_profile"
    private const val KEY_CONSOLE = "console"

    /**
     * Everything except the console is stored PER CONSOLE: a GBA and a PS1 want
     * different engines, different AI factors and different retro effects, and
     * re-tuning them on every switch is busywork. The console itself is global -
     * it is what the user picks on the main screen before starting.
     */
    private fun key(console: ConsoleType, name: String) = "${console.name}_$name"

    fun currentConsole(context: Context): ConsoleType {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching { ConsoleType.valueOf(p.getString(KEY_CONSOLE, "GBA")!!) }
            .getOrDefault(ConsoleType.GBA)
    }

    fun setConsole(context: Context, console: ConsoleType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONSOLE, console.name)
            .apply()
    }

    fun load(context: Context): RenderProfile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val console = currentConsole(context)
        return RenderProfile(
            console = console,
            isAiEnabled = p.getBoolean(key(console, "aiEnabled"), true),
            engine = runCatching {
                UpscaleEngine.valueOf(p.getString(key(console, "engine"), "PIXEL_EDGE")!!)
            }.getOrDefault(UpscaleEngine.PIXEL_EDGE),
            aiScale = runCatching {
                AiScale.valueOf(p.getString(key(console, "aiScale"), "X3")!!)
            }.getOrDefault(AiScale.X3),
            scanlineIntensity = p.getFloat(key(console, "scanline"), 0f),
            lcdGridIntensity = p.getFloat(key(console, "lcd"), 0f),
            maskType = runCatching {
                MaskType.valueOf(p.getString(key(console, "maskType"), "NONE")!!)
            }.getOrDefault(MaskType.NONE),
            sourceCorner = runCatching {
                SourceCorner.valueOf(p.getString(key(console, "corner"), "BOTTOM_RIGHT")!!)
            }.getOrDefault(SourceCorner.BOTTOM_RIGHT),
            sourceScale = p.getInt(key(console, "sourceScale"), 1),
            showSourceGuide = p.getBoolean(key(console, "guide"), false),
            disableRaShader = p.getBoolean(key(console, "disableShader"), true),
            avoidSourceOverlap = p.getBoolean(key(console, "avoidOverlap"), true)
        )
    }

    fun save(context: Context, profile: RenderProfile) {
        val c = profile.console
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONSOLE, c.name)
            .putBoolean(key(c, "aiEnabled"), profile.isAiEnabled)
            .putString(key(c, "engine"), profile.engine.name)
            .putString(key(c, "aiScale"), profile.aiScale.name)
            .putFloat(key(c, "scanline"), profile.scanlineIntensity)
            .putFloat(key(c, "lcd"), profile.lcdGridIntensity)
            .putString(key(c, "maskType"), profile.maskType.name)
            .putString(key(c, "corner"), profile.sourceCorner.name)
            .putInt(key(c, "sourceScale"), profile.sourceScale)
            .putBoolean(key(c, "guide"), profile.showSourceGuide)
            .putBoolean(key(c, "disableShader"), profile.disableRaShader)
            .putBoolean(key(c, "avoidOverlap"), profile.avoidSourceOverlap)
            .apply()
    }
}
