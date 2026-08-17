package com.retroai.scaler.ui

import android.content.Context
import android.graphics.Rect
import android.os.Build

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
 * Whether our own overlay ends up inside the capture.
 *
 * This is the single fact the whole geometry hangs off. Whole-screen capture
 * mirrors the display including everything we draw, so the source and the
 * output have to be kept apart. Single-app capture only mirrors the emulator's
 * window, so our output can sit right on top of the capture window - which is
 * what finally gets the small native picture out of the user's sight.
 *
 * Measured at runtime by the marker probe rather than assumed: the consent
 * dialog offers both and there is no API to force either one.
 */
enum class CaptureMode { WHOLE_SCREEN, SINGLE_APP }

/**
 * Which screen corner hosts RetroArch's small native capture window.
 *
 * Only meaningful for [CaptureMode.WHOLE_SCREEN]. The capture source and the
 * enhanced output MUST NOT overlap there: MediaProjection mirrors the whole
 * display including our own overlay, so painting on top of the area we sample
 * from would feed our output straight back into the capture.
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
    ESPCN_ULTRA("ESPCN Ultra (需高端 GPU)", "ultra"),

    /**
     * A different aim from the ESPCN family, not just a bigger one. Those
     * reconstruct the luminance faithfully; this one is trained to REPAINT -
     * to redraw edges and invent texture - so it rebuilds full RGB and is
     * meant to look obviously generated rather than merely sharper.
     *
     * Residual and RGB, so it needs the three-channel inference path. The
     * ESPCN models stay: they are far cheaper and remain the right choice on
     * weaker hardware.
     */
    RETROAI("RetroAI（重绘 · 需旗舰 GPU）", "retroai"),

    /**
     * Diagnostic: shows the estimated depth map instead of a picture.
     *
     * Depth is the one piece of the HD-2D pass that needs a network - the
     * lighting, bloom, tilt-shift and grading built on it are all weightless
     * shader work. It is here as its own engine so the cost and the quality of
     * that piece can be judged on device before anything is built on top, the
     * same way the upscalers were.
     *
     * 3 channels in, 1 out, and no upscaling: depth is low-frequency enough to
     * compute at native resolution, which is most of why it is affordable.
     */
    DEPTH("深度图（调试）", "depth");

    val usesNetwork: Boolean get() = assetVariant != null
    /** Ultra and RetroAI are both unusable without GPU inference. */
    val needsGpu: Boolean get() = this == ESPCN_ULTRA || this == RETROAI
    val isPixelEdge: Boolean get() = this == PIXEL_EDGE

    /** Anything past the ESPCN family takes full RGB in. */
    val modelInChannels: Int get() = if (this == ESPCN_FAST || this == ESPCN_HQ ||
        this == ESPCN_ULTRA) 1 else 3

    /** RetroAI reconstructs colour; ESPCN is luminance; depth is one channel. */
    val modelOutChannels: Int get() = if (this == RETROAI) 3 else 1

    /** The depth net does not upscale - it maps native resolution to itself. */
    val ignoresAiScale: Boolean get() = this == DEPTH
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

    /**
     * Depth-driven lighting. NOT an upscaler - the picture still comes from
     * whichever engine is selected, and the network's only contribution is the
     * depth field that lights it. That separation is the whole reason this
     * works where repainting did not: nothing generative touches the pixels.
     */
    var hd2dEnabled: Boolean = false,

    /**
     * How far the lighting is pushed, 0..1. Exposed because the honest answer
     * to "how much is right" is a matter of taste and of the game, and the
     * first fixed value guessed it badly wrong.
     */
    var hd2dStrength: Float = 0.5f,
    /** Disable RetroArch's own shader when writing its config. */
    var disableRaShader: Boolean = true,
    /** Keep the output clear of the capture window. */
    var avoidSourceOverlap: Boolean = true,
    /**
     * Where the emulator was actually measured to be drawing. Takes priority
     * over the computed corner: RetroArch's placement depends on its video
     * driver and on the repack's own overrides, so measuring beats predicting.
     */
    var detectedSourceRect: Rect? = null,

    /**
     * Decides whether the capture window has to be kept clear of the output.
     * Measured by the probe; predicted from the last session until it lands.
     */
    var captureMode: CaptureMode = CaptureMode.WHOLE_SCREEN
) {
    /**
     * Where RetroArch is told to put its viewport, normalised 0..1.
     *
     * 0.5 under single-app capture: the picture ends up underneath our output
     * anyway, and the middle is unambiguous whichever origin the video driver
     * uses - which is the one thing the corner placements can never be sure of.
     */
    val effectiveBiasX: Float
        get() = if (captureMode == CaptureMode.SINGLE_APP) 0.5f else sourceCorner.biasX

    val effectiveBiasY: Float
        get() = if (captureMode == CaptureMode.SINGLE_APP) 0.5f else sourceCorner.biasY

    /** Screen-space rect the overlay samples from. */
    fun getSourceRect(screenWidth: Int, screenHeight: Int): Rect {
        detectedSourceRect?.let { return it }
        return getPlannedSourceRect(screenWidth, screenHeight)
    }

    /** Where we ASK RetroArch to draw (before measuring). */
    fun getPlannedSourceRect(screenWidth: Int, screenHeight: Int): Rect {
        val w = console.nativeWidth * sourceScale
        val h = console.nativeHeight * sourceScale

        // Single-app capture: centred, because the output is going to be laid
        // straight over it. Centring also removes the one ambiguity in
        // RetroArch's bias keys - whether bias_y=0 means top or bottom depends
        // on the video driver, but the middle is the middle either way.
        if (captureMode == CaptureMode.SINGLE_APP) {
            val left = ((screenWidth - w) / 2).coerceAtLeast(0)
            val top = ((screenHeight - h) / 2).coerceAtLeast(0)
            return Rect(left, top, left + w, top + h)
        }

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

        // Single-app capture: our overlay is not in the mirror, so the output
        // takes the whole screen and sits right on top of the capture window.
        //
        // Centred on the SCREEN, not on the capture window. RetroArch centres
        // its viewport inside its own window, which the system bars make
        // shorter than the display - following that centre put the picture
        // ~28px high and left visibly uneven top and bottom borders. The
        // capture window is tiny by comparison, so screen-centring still
        // swallows it whole; the nudge below only ever matters for a source
        // large enough to poke out, and keeps coverage guaranteed.
        if (captureMode == CaptureMode.SINGLE_APP) {
            val k = minOf(
                screenWidth / console.nativeWidth,
                screenHeight / console.nativeHeight
            ).coerceAtLeast(1)
            val w = console.nativeWidth * k
            val h = console.nativeHeight * k
            var left = (screenWidth - w) / 2
            var top = (screenHeight - h) / 2
            if (src.width() <= w) left = left.coerceIn(src.right - w, src.left)
            if (src.height() <= h) top = top.coerceIn(src.bottom - h, src.top)
            left = left.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
            top = top.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
            return Rect(left, top, left + w, top + h)
        }

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
        // The depth net has nothing to do with the AI factor - it is not an
        // upscaler, so 1x is a valid setting for it rather than "off".
        if (aiScale == AiScale.X1 && !engine.ignoresAiScale) return null
        // RetroAI is a separate family with its own naming, not an ESPCN
        // variant - different channel count, different blob names.
        if (engine == UpscaleEngine.DEPTH) return "retrodepth_base"
        if (engine == UpscaleEngine.RETROAI) return "retrosr_${aiScale.factor}x_base"
        return "espcn_y_${aiScale.factor}x_$variant"
    }

    /** The lines RetroArch needs, shown in the menu and copyable. */
    fun getRetroArchViewportConfig(screenWidth: Int, screenHeight: Int): String {
        return buildString {
            appendLine("aspect_ratio_index = \"23\"")
            appendLine("video_scale_integer = \"true\"")
            appendLine("custom_viewport_width = \"${console.nativeWidth * sourceScale}\"")
            appendLine("custom_viewport_height = \"${console.nativeHeight * sourceScale}\"")
            appendLine("video_viewport_bias_x = \"%.1f\"".format(effectiveBiasX))
            appendLine("video_viewport_bias_y = \"%.1f\"".format(effectiveBiasY))
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
     * Global, not per-console: it describes how the user grants capture, not
     * anything about the game.
     *
     * Persisted because RetroArch's config has to be written BEFORE RetroArch
     * starts, while the mode can only be measured once frames are flowing -
     * so the first write of a session is always a prediction. Last session's
     * answer is a far better one than a fixed default, and users do not
     * normally alternate. When the probe disagrees, the service rewrites the
     * config and says to restart RetroArch.
     */
    private const val KEY_CAPTURE_MODE = "captureMode"

    /**
     * Single-app capture only exists from API 34, so anything older can only
     * ever be whole-screen. On 34+ the dialog still lets the user pick either,
     * so this is a guess - just the best available one before the probe lands.
     */
    private val defaultCaptureMode: CaptureMode
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            CaptureMode.SINGLE_APP
        } else {
            CaptureMode.WHOLE_SCREEN
        }

    fun lastCaptureMode(context: Context): CaptureMode {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            CaptureMode.valueOf(p.getString(KEY_CAPTURE_MODE, defaultCaptureMode.name)!!)
        }.getOrDefault(defaultCaptureMode)
    }

    fun setCaptureMode(context: Context, mode: CaptureMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CAPTURE_MODE, mode.name)
            .apply()
    }

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
            hd2dEnabled = p.getBoolean(key(console, "hd2d"), false),
            hd2dStrength = p.getFloat(key(console, "hd2dStrength"), 0.5f),
            disableRaShader = p.getBoolean(key(console, "disableShader"), true),
            avoidSourceOverlap = p.getBoolean(key(console, "avoidOverlap"), true),
            captureMode = lastCaptureMode(context)
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
            .putBoolean(key(c, "hd2d"), profile.hd2dEnabled)
            .putFloat(key(c, "hd2dStrength"), profile.hd2dStrength)
            .putBoolean(key(c, "disableShader"), profile.disableRaShader)
            .putBoolean(key(c, "avoidOverlap"), profile.avoidSourceOverlap)
            .apply()
    }
}
