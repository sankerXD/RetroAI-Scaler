package com.retroai.scaler.ui

import android.content.Context
import android.graphics.Rect
import android.os.Build
import androidx.annotation.StringRes
import com.retroai.scaler.R

/**
 * Console presets. Native resolutions are what the emulator core actually
 * outputs - everything downstream (capture window size, integer output factor,
 * the AI network's input) is derived from these numbers.
 */
/**
 * Which console a core emulates.
 *
 * Resolution alone cannot answer this, and the failure is not exotic: fceumm
 * crops NES overscan by default and emits 248x224, which is nearer to SFC's
 * 256x224 than to the NES's own 256x240 - so matching on size picked the wrong
 * console for the commonest NES core there is. A core's name is not ambiguous,
 * and the shim knows it, so it says so.
 *
 * Resolution still goes first, because it settles what a core name cannot:
 * mGBA plays both Game Boy and Game Boy Advance, and only the frame size tells
 * those apart.
 */
fun consoleForCore(coreFile: String): ConsoleType? =
    when (coreFile.substringBefore("_libretro").removeSuffix("_shim")) {
        "vbam", "gpsp", "mgba", "vba_next" -> ConsoleType.GBA
        "gambatte", "sameboy", "gearboy" -> ConsoleType.GBC
        "fceumm", "nestopia", "quicknes", "mesen", "fceux" -> ConsoleType.FC
        "snes9x", "snes9x2010", "bsnes", "bsnes_mercury_balanced" -> ConsoleType.SFC
        "genesis_plus_gx", "picodrive" -> ConsoleType.MD
        "swanstation", "pcsx_rearmed", "mednafen_psx", "duckstation" -> ConsoleType.PS1
        // Dreamcast. Every one of these renders on the GPU, so they can only
        // ever arrive here through the capture route - which is exactly why
        // they have to be recognised: an unknown core means the viewport is
        // never written and RetroArch draws full screen.
        "flycast", "flycast_gl", "redream" -> ConsoleType.DC
        else -> null
    }

enum class ConsoleType(
    @StringRes val labelRes: Int,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val aspectRatioName: String
) {
    GBA(R.string.console_gba, 240, 160, "3:2"),
    GBC(R.string.console_gbc, 160, 144, "10:9"),
    SFC(R.string.console_sfc, 256, 224, "8:7"),
    FC(R.string.console_fc, 256, 240, "4:3"),
    MD(R.string.console_md, 320, 224, "4:3"),
    PS1(R.string.console_ps1, 320, 240, "4:3"),
    DC(R.string.console_dc, 640, 480, "4:3");

    fun label(context: Context): String = context.getString(labelRes)

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
enum class SourceCorner(@StringRes val labelRes: Int) {
    TOP_LEFT(R.string.corner_top_left),
    TOP_RIGHT(R.string.corner_top_right),
    BOTTOM_LEFT(R.string.corner_bottom_left),
    BOTTOM_RIGHT(R.string.corner_bottom_right);

    fun label(context: Context): String = context.getString(labelRes)

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
enum class UpscaleEngine(@StringRes val labelRes: Int, val assetVariant: String?) {
    /**
     * Scale2x-style edge reconstruction. For 2D sprite art this beats the
     * network outright: pixel art has no lost detail to recover, it has
     * staircases that should have been diagonals, and that is a comparison
     * problem rather than a regression problem.
     */
    PIXEL_EDGE(R.string.engine_pixel_edge, null),

    /**
     * A LOOK, not a network: pixel-edge reconstruction with the depth-driven
     * lighting, the tilt-shift focus band and the bloom all switched on at the
     * levels that were settled on the device (75 / 50 / 25).
     *
     * It sits in the engine list because that is what the player is choosing
     * between - one picture or another - and because every one of its parts was
     * a separate switch with its own slider for as long as they were being
     * tuned, which is not a decision anyone should have to make twice.
     */
    HD2D(R.string.engine_hd2d, null),
    SHADER(R.string.engine_shader, null),
    ESPCN_FAST(R.string.engine_espcn_fast, "fast"),
    ESPCN_HQ(R.string.engine_espcn_hq, "hq"),

    /**
     * ~30x the arithmetic of HQ. Only viable with ncnn on Vulkan, i.e. a recent
     * mobile GPU; on an older handheld it falls back to CPU and will not keep
     * up, which the UI says out loud rather than silently stuttering.
     */
    ESPCN_ULTRA(R.string.engine_espcn_ultra, "ultra"),

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
    /**
     * HIDDEN from the menu. Section 12.2 measured the repaint route out on
     * pixel art: the teacher only changes anything where the artist drew a hard
     * edge, and that is exactly where its changes are damage. Kept in the enum
     * rather than deleted because that verdict was specific to sprite art -
     * PS1 and arcade material, with real gradients and dithering, is where it
     * would earn its place, and the weights ship already.
     */
    RETROAI(R.string.engine_retroai, "retroai"),

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
    DEPTH(R.string.engine_depth, "depth");

    /**
     * Not offered in the menu. Both are reachable by setting the engine
     * directly, which is what they are for - one is a diagnostic, the other is
     * a route that was measured out for this content.
     */
    val isHidden: Boolean get() = this == RETROAI || this == DEPTH

    fun label(context: Context): String = context.getString(labelRes)

    val usesNetwork: Boolean get() = assetVariant != null
    /** Ultra and RetroAI are both unusable without GPU inference. */
    val needsGpu: Boolean get() = this == ESPCN_ULTRA || this == RETROAI
    /** HD-2D draws with the pixel-edge reconstruction and lights the result. */
    val isPixelEdge: Boolean get() = this == PIXEL_EDGE || this == HD2D

    /**
     * The lighting, focus band and bloom that come with this engine. Anything
     * else clears them, so switching engines cannot leave a stray effect on.
     */
    val hd2dStrength: Float get() = if (this == HD2D) 0.75f else 0.0f
    val dofStrength: Float get() = if (this == HD2D) 0.50f else 0.0f
    val bloomStrength: Float get() = if (this == HD2D) 0.25f else 0.0f

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
enum class MaskType(val id: Int, @StringRes val labelRes: Int) {
    NONE(0, R.string.mask_none),
    APERTURE(1, R.string.mask_aperture),
    SHADOW(2, R.string.mask_shadow),
    SLOT(3, R.string.mask_slot);

    fun label(context: Context): String = context.getString(labelRes)
}

enum class AiScale(val factor: Int, val label: String) {
    X1(1, "1x"),
    X2(2, "2x"),
    X3(3, "3x"),
    X4(4, "4x"),

    /**
     * 1280x960 handhelds drawing a Game Boy Advance: 240x160 fits five times
     * across and six down, so the picture is drawn at 5x - and there was no
     * model for it, so the nearest one reconstructed onto a grid the picture
     * was not drawn on. Now that the scale is picked from the measured
     * geometry, every multiple that geometry can produce needs a model.
     */
    X5(5, "5x"),

    /**
     * Matches the output geometry on a 1080p handheld running GBA: the picture
     * is drawn at the largest integer multiple of 240x160 that fits, which is
     * 6x. Below that the network reconstructs less than the screen shows and
     * the result is upscaled again; above it, the extra detail is computed and
     * then thrown away.
     *
     * Needs espcn_y_6x_{fast,hq,ultra}; the depth net is unaffected, since it
     * maps native resolution to itself and has no factor at all.
     */
    X6(6, "6x")
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
     *
     * 0.75 is where it was judged on the handheld once the lighting was reading
     * a genuinely blurred depth. The earlier 0.5 was picked while the pass was
     * still outlining every glyph, so half strength was as much of that as was
     * bearable - it was the artefact being turned down, not the light.
     */
    var hd2dStrength: Float = 0.75f,
    /**
     * Tilt-shift depth of field, 0..1. The most recognisable HD-2D cue, and the
     * only one that needs no depth at all - a fixed focus band stands in for it,
     * because in a scene viewed from above the top of the frame IS the distance.
     *
     * It softens the artist's pixels away from the band on purpose. Everywhere
     * else in this project that would be a bug; here it is the effect.
     */
    var dofStrength: Float = 0.0f,
    /**
     * Highlight bleed, 0..1. The other half of the lens pair with the focus
     * band, and like it, needs no depth.
     *
     * Colour grading, the third item on section 12.3's list, is deliberately
     * absent. Bloom and defocus SIMULATE A LENS and sit on top of any art
     * direction; grading re-decides the palette, and these games already have
     * one. It is the only effect on that list that fights the artist.
     */
    var bloomStrength: Float = 0.0f,
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

        /*
         * Nothing fits beside the capture window, at any whole multiple.
         *
         * This used to fall back to the whole screen "so something is still
         * shown", and that is the one rect it must never be: the output would
         * cover the very region we sample, the next captured frame would
         * contain the previous one, and the picture washes out in a few frames
         * (§1). Whole-screen capture has no way around that.
         *
         * It is reachable for real. A Dreamcast is 640x480 native, so on a
         * 1280x960 panel the capture window takes a quarter of the screen and
         * the largest free band is 1280x464 - eight pixels short of a 1x
         * output. An empty rect says "there is nowhere to draw this", which is
         * the truth, and the callers refuse rather than paint.
         */
        return best ?: Rect()
    }

    /**
     * Integer factor currently in use, or 0 when the output does not fit beside
     * the capture window at all. Zero is a real answer, not a failure to
     * compute one - see [getOutputRect].
     */
    fun getOutputScale(screenWidth: Int, screenHeight: Int): Int {
        val out = getOutputRect(screenWidth, screenHeight)
        if (out.isEmpty) return 0
        return (out.width() / console.nativeWidth).coerceAtLeast(1)
    }

    fun getSummaryText(context: Context, screenWidth: Int, screenHeight: Int): String {
        val out = getOutputRect(screenWidth, screenHeight)
        val k = getOutputScale(screenWidth, screenHeight)
        val src = getSourceRect(screenWidth, screenHeight)
        val origin = if (detectedSourceRect != null) {
            context.getString(R.string.corner_detected)
        } else {
            sourceCorner.label(context)
        }
        return context.getString(
            R.string.console_plan,
            src.width(), src.height(), origin, out.width(), out.height(), k
        )
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
            // A stored engine that is no longer offered falls back rather than
            // leaving the menu showing one thing and the renderer doing another.
            engine = runCatching {
                UpscaleEngine.valueOf(p.getString(key(console, "engine"), "PIXEL_EDGE")!!)
                    .takeUnless { it.isHidden } ?: UpscaleEngine.PIXEL_EDGE
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
            hd2dStrength = p.getFloat(key(console, "hd2dStrength"), 0.75f),
            dofStrength = p.getFloat(key(console, "dof"), 0.0f),
            bloomStrength = p.getFloat(key(console, "bloom"), 0.0f),
            disableRaShader = p.getBoolean(key(console, "disableShader"), true),
            avoidSourceOverlap = p.getBoolean(key(console, "avoidOverlap"), true),
            captureMode = lastCaptureMode(context)
        )
    }

    /**
     * Writes only the settings of the console this profile belongs to.
     *
     * It deliberately does NOT touch KEY_CONSOLE. It used to, and that single
     * line silently undid platform switches: the floating menu holds a profile
     * snapshot taken when the service started, so after the user picked a new
     * console in the main activity, the first control they touched in the menu
     * wrote the OLD console back as the current one - and re-stamped that
     * console's engine and factor on top. The visible symptom was "every
     * platform switch lands back on HD-2D", which was not a default at all
     * (the default is PIXEL_EDGE) but the previous session being restored.
     *
     * Whoever owns a key has to be the only writer of it. KEY_CONSOLE belongs
     * to [setConsole] and to the picker that calls it.
     */
    fun save(context: Context, profile: RenderProfile) {
        val c = profile.console
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
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
            .putFloat(key(c, "dof"), profile.dofStrength)
            .putFloat(key(c, "bloom"), profile.bloomStrength)
            .putBoolean(key(c, "disableShader"), profile.disableRaShader)
            .putBoolean(key(c, "avoidOverlap"), profile.avoidSourceOverlap)
            .apply()
    }
}
