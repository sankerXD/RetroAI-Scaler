package com.retroai.scaler.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.retroai.scaler.R
import com.retroai.scaler.capture.DatasetRecorder
import com.retroai.scaler.detector.RetroArchConfigManager
import com.retroai.scaler.jni.NativeBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the Floating Ball, Auto-Hide / Touch-Wake Edge Tab, and Expanded Menu.
 */
class FloatingBallManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val nativeBridge: NativeBridge,
    private val onProfileChanged: (RenderProfile) -> Unit,
    private val onServiceStopRequested: () -> Unit
) {
    companion object {
        private const val AUTO_HIDE_DELAY_MS = 3500L
        private const val BALL_SIZE_PX = 56

        /** Burst length and spacing - about two seconds of an animation. */
        private const val BURST_FRAMES = 15
        private const val BURST_INTERVAL_MS = 130L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * ncnn model loading runs here, never on the main thread.
     *
     * nativeLoadEspcnModel() takes the pipeline mutex and, on the very first
     * call, brings up the whole Vulkan stack inside ncnn (instance, device,
     * pipeline cache). unloadEspcn() is no cheaper: it joins the inference
     * thread, which for Ultra means waiting out a 50 ms+ forward pass. Doing
     * either on the main thread while the capture thread holds that same mutex
     * every frame is what produced the "menu opens, app stops responding" ANR.
     *
     * Single-threaded on purpose: loads must apply in the order they were
     * requested, and two ncnn::Net constructions must not overlap.
     */
    private val engineExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EspcnLoader").apply { isDaemon = true }
    }

    /**
     * Bumped on every engine/scale change. A load that finishes after the user
     * has already picked something else must not report its result, or a slow
     * Ultra load would toast-and-fall-back over the newer selection.
     */
    private val engineGeneration = AtomicInteger(0)

    /**
     * Corpus capture. Runs on its own thread because the grab blocks waiting
     * for the render thread to service it, and writing a PNG touches storage.
     */
    private val recorder by lazy { DatasetRecorder(context, nativeBridge) }
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DatasetCapture").apply { isDaemon = true }
    }
    private var isAutoCapturing = false
    /**
     * The corpus capture controls are no longer on the panel - the dataset is
     * collected, and the buttons were developer tooling in a player-facing
     * menu. The machinery below stays reachable so a future collection run
     * needs a layout entry back, not a rewrite.
     */
    private var captureButton: Button? = null
    private var autoCaptureButton: Button? = null
    private var lastCaptureMessage: String? = null

    var profile: RenderProfile = ProfilePreference.load(context)
        private set

    // Views
    private var floatingBallView: View? = null
    private var edgeTabView: View? = null
    private var menuView: View? = null

    // Layout Params
    private lateinit var ballParams: WindowManager.LayoutParams
    private lateinit var edgeTabParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1.0f
    private var isDockedOnRight = false

    // State
    private var isMenuShowing = false
    private var isBallHidden = false

    private val autoHideRunnable = Runnable { hideBallToEdgeTab() }

    private val hudUpdateRunnable = object : Runnable {
        override fun run() {
            if (isMenuShowing) {
                updateHudStats()
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    fun init() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.density

        setupLayoutParams()
        inflateViews()
        showFloatingBall()
        resetAutoHideTimer()
    }

    /**
     * Switches the layout between "keep the output clear of the capture window"
     * and "lay the output straight over it", once the probe has established
     * which one applies. Drops the measured rect: the capture window is about
     * to move between the corner and the centre.
     */
    fun applyCaptureMode(mode: CaptureMode) {
        if (profile.captureMode == mode) return
        profile.captureMode = mode
        profile.detectedSourceRect = null
        applyRenderProfile()
    }

    /**
     * Re-seats everything that was measured against the old screen after the
     * display rotates.
     *
     * The measured capture window is dropped rather than transformed: it is a
     * rect in the previous orientation's pixels, and RetroArch re-lays out its
     * own viewport on rotation anyway, so the old numbers describe a window
     * that no longer exists. The service re-runs detection once the game is
     * back on screen.
     */
    fun onScreenSizeChanged(newWidth: Int, newHeight: Int) {
        if (newWidth == screenWidth && newHeight == screenHeight) return
        screenWidth = newWidth
        screenHeight = newHeight
        profile.detectedSourceRect = null

        // The ball is placed in absolute screen pixels, so after a portrait
        // <-> landscape swap it can sit past the new edge, out of reach.
        val ballSize = dp(BALL_SIZE_PX)
        ballParams.x = ballParams.x.coerceIn(0, (screenWidth - ballSize).coerceAtLeast(0))
        ballParams.y = ballParams.y.coerceIn(0, (screenHeight - ballSize).coerceAtLeast(0))
        if (floatingBallView?.isAttachedToWindow == true) {
            windowManager.updateViewLayout(floatingBallView, ballParams)
        }
        if (edgeTabView?.isAttachedToWindow == true) {
            edgeTabParams.x = if (isDockedOnRight) screenWidth - edgeTabParams.width else 0
            edgeTabParams.y = ballParams.y
            windowManager.updateViewLayout(edgeTabView, edgeTabParams)
        }
    }

    /**
     * Called by the service once the native renderer exists (model loading and
     * config pushes are no-ops before nativeInit).
     */
    fun pushAllSettings() {
        // The engine owns its effects, including across a restart: a profile
        // saved while the lighting was still a separate switch would otherwise
        // come back with an effect on that the current engine does not carry.
        applyEnginePreset()
        applyEngine()
        applyRenderProfile()
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun setupLayoutParams() {
        ballParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = screenHeight / 3
        }

        edgeTabParams = WindowManager.LayoutParams(
            dp(20),
            dp(72),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenHeight / 3
        }

        val menuWidth = (screenWidth * 0.82f).toInt().coerceIn(dp(300), dp(420))
        menuParams = WindowManager.LayoutParams(
            menuWidth,
            (screenHeight * 0.85f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun inflateViews() {
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_RetroAIScaler)
        val inflater = LayoutInflater.from(themedContext)

        floatingBallView = inflater.inflate(R.layout.layout_floating_ball, null)
        setupBallTouchListener()

        edgeTabView = inflater.inflate(R.layout.layout_floating_edge_tab, null)
        edgeTabView?.setOnClickListener { wakeBallFromEdge() }

        menuView = inflater.inflate(R.layout.layout_floating_menu, null)
        setupMenuInteractions()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBallTouchListener() {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false

        floatingBallView?.setOnTouchListener { _, event ->
            resetAutoHideTimer()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ballParams.x
                    initialY = ballParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        isDragging = true
                    }
                    ballParams.x = (initialX + dx).coerceIn(0, screenWidth - dp(BALL_SIZE_PX))
                    ballParams.y = (initialY + dy).coerceIn(0, screenHeight - dp(BALL_SIZE_PX))
                    if (floatingBallView?.isAttachedToWindow == true) {
                        windowManager.updateViewLayout(floatingBallView, ballParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        toggleMenu()
                    } else {
                        snapBallToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBallToEdge() {
        val midX = screenWidth / 2
        val ballWidth = floatingBallView?.width?.takeIf { it > 0 } ?: dp(BALL_SIZE_PX)
        val targetX = if (ballParams.x + ballWidth / 2 < midX) {
            isDockedOnRight = false
            dp(4)
        } else {
            isDockedOnRight = true
            screenWidth - ballWidth - dp(4)
        }

        ValueAnimator.ofInt(ballParams.x, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                ballParams.x = it.animatedValue as Int
                if (floatingBallView?.isAttachedToWindow == true) {
                    windowManager.updateViewLayout(floatingBallView, ballParams)
                }
            }
            start()
        }
    }

    /** Auto-Hide: the ball is swapped for a slim, near-transparent edge tab. */
    private fun hideBallToEdgeTab() {
        if (isMenuShowing || isBallHidden) return
        isBallHidden = true

        floatingBallView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }

        edgeTabParams.x = if (isDockedOnRight) screenWidth - edgeTabParams.width else 0
        edgeTabParams.y = ballParams.y
        edgeTabView?.let {
            if (!it.isAttachedToWindow) windowManager.addView(it, edgeTabParams)
        }
    }

    /** Touch-Wake: tapping the edge tab restores the full ball. */
    private fun wakeBallFromEdge() {
        if (!isBallHidden) return
        isBallHidden = false

        edgeTabView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        showFloatingBall()
        floatingBallView?.apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
        resetAutoHideTimer()
    }

    private fun showFloatingBall() {
        floatingBallView?.let {
            if (!it.isAttachedToWindow) {
                windowManager.addView(it, ballParams)
            }
        }
    }

    private fun resetAutoHideTimer() {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (!isMenuShowing) {
            mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMenuInteractions() {
        val root = menuView ?: return

        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                true
            } else {
                false
            }
        }

        root.findViewById<ImageView>(R.id.btnCloseMenu).setOnClickListener { hideMenu() }

        // 2. AI reconstruction factor
        root.findViewById<ChipGroup>(R.id.chipGroupScale)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                profile.aiScale = when (checkedIds.first()) {
                    R.id.chipScale1x -> AiScale.X1
                    R.id.chipScale2x -> AiScale.X2
                    R.id.chipScale3x -> AiScale.X3
                    R.id.chipScale4x -> AiScale.X4
                    else -> profile.aiScale
                }
                applyEngine()
                applyRenderProfile()
            }

        // 3. AI Switch
        root.findViewById<SwitchMaterial>(R.id.switchAiEnable)
            .setOnCheckedChangeListener { _, isChecked ->
                profile.isAiEnabled = isChecked
                applyRenderProfile()
            }

        // 2. AI reconstruction factor (NOT the on-screen size)
        root.findViewById<ChipGroup>(R.id.chipGroupScale)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                profile.aiScale = when (checkedIds.first()) {
                    R.id.chipScale1x -> AiScale.X1
                    R.id.chipScale2x -> AiScale.X2
                    R.id.chipScale3x -> AiScale.X3
                    R.id.chipScale4x -> AiScale.X4
                    else -> profile.aiScale
                }
                applyEngine()
                applyRenderProfile()
            }

        // 3b. Upscale engine
        root.findViewById<ChipGroup>(R.id.chipGroupEngine)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                profile.engine = when (checkedIds.first()) {
                    R.id.chipEnginePixelEdge -> UpscaleEngine.PIXEL_EDGE
                    R.id.chipEngineHd2d -> UpscaleEngine.HD2D
                    R.id.chipEngineShader -> UpscaleEngine.SHADER
                    R.id.chipEngineEspcnFast -> UpscaleEngine.ESPCN_FAST
                    R.id.chipEngineEspcnHq -> UpscaleEngine.ESPCN_HQ
                    R.id.chipEngineEspcnUltra -> UpscaleEngine.ESPCN_ULTRA
                    else -> profile.engine
                }
                applyEnginePreset()
                applyEngine()
                applyRenderProfile()
            }

                                                root.findViewById<Button>(R.id.btnToggleGuide).apply {
            updateGuideButtonText(this)
            setOnClickListener {
                profile.showSourceGuide = !profile.showSourceGuide
                updateGuideButtonText(this)
                applyRenderProfile()
                // The numbers behind the ring, so a misaligned capture window
                // can be read off directly instead of through logcat.
                Toast.makeText(
                    context,
                    profile.getSummaryText(screenWidth, screenHeight),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        root.findViewById<Button>(R.id.btnDetectSource).setOnClickListener { detectSourceWindow() }
        root.findViewById<Button>(R.id.btnDetectSource).setOnLongClickListener {
            clearDetectedWindow(); true
        }
        root.findViewById<Button>(R.id.btnRestoreRaConfig).setOnClickListener { restoreRetroArchConfig() }
        root.findViewById<Button>(R.id.btnRestoreRaConfig).setOnClickListener { restoreRetroArchConfig() }

        // 5. Retro Shaders
        val tvScanline = root.findViewById<TextView>(R.id.tvScanlineLabel)
        tvScanline.text = "CRT 扫描线强度  ${(profile.scanlineIntensity * 100).toInt()}%"
        root.findViewById<SeekBar>(R.id.seekbarScanline).apply {
            progress = (profile.scanlineIntensity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    profile.scanlineIntensity = progress / 100f
                    tvScanline.text = "CRT 扫描线强度  $progress%"
                    applyRenderProfile()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        root.findViewById<ChipGroup>(R.id.chipGroupMask)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                profile.maskType = when (checkedIds.first()) {
                    R.id.chipMaskAperture -> MaskType.APERTURE
                    R.id.chipMaskShadow -> MaskType.SHADOW
                    R.id.chipMaskSlot -> MaskType.SLOT
                    else -> MaskType.NONE
                }
                applyRenderProfile()
            }

        val tvLcd = root.findViewById<TextView>(R.id.tvLcdGridLabel)
        tvLcd.text = triadLabel(profile.lcdGridIntensity)
        root.findViewById<SeekBar>(R.id.seekbarLcdGrid).apply {
            progress = (profile.lcdGridIntensity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    profile.lcdGridIntensity = progress / 100f
                    tvLcd.text = triadLabel(profile.lcdGridIntensity)
                    applyRenderProfile()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        // 6. Stop service - always reachable escape hatch
        root.findViewById<Button>(R.id.btnStopService).setOnClickListener {
            onServiceStopRequested()
        }

        syncMenuToProfile(root)
    }

    /**
     * Loads (or drops) the ncnn weights for the selected engine, and switches
     * the shader's edge-reconstruction path on or off.
     */
    /**
     * The engine carries its own effects. HD-2D is a bundle - the lighting, the
     * focus band and the bloom at the levels settled on the device - and every
     * other engine clears all three, so choosing one can never leave a stray
     * effect running from a previous choice.
     */
    private fun applyEnginePreset() {
        profile.hd2dEnabled = profile.engine.hd2dStrength > 0f
        profile.hd2dStrength = profile.engine.hd2dStrength
        profile.dofStrength = profile.engine.dofStrength
        profile.bloomStrength = profile.engine.bloomStrength
    }

    private fun applyEngine() {
        // Cheap uniform writes stay here: they take the pipeline mutex for a
        // fraction of a frame, not for a Vulkan bring-up.
        nativeBridge.nativeSetPixelEdge(profile.engine.isPixelEdge)
        nativeBridge.nativeSetMaskType(profile.maskType.id)

        // Snapshot what the user picked. The worker must not read `profile`,
        // which keeps changing on the main thread.
        // HD-2D needs the depth net regardless of which upscaler is showing
        // the picture, so it wins the model slot while it is on.
        val baseName = if (profile.hd2dEnabled) "retrodepth_base"
                       else profile.modelAssetBaseName()
        val scaleFactor = profile.aiScale.factor
        val inChannels = if (profile.hd2dEnabled) 3 else profile.engine.modelInChannels
        val outChannels = if (profile.hd2dEnabled) 1 else profile.engine.modelOutChannels
        // The depth net maps native resolution to itself.
        val modelScale = if (profile.hd2dEnabled || profile.engine.ignoresAiScale) 1 else scaleFactor
        val engineName = profile.engine.displayName
        val generation = engineGeneration.incrementAndGet()

        engineExecutor.execute {
            if (baseName == null) {
                nativeBridge.nativeUnloadEspcnModel()
                return@execute
            }
            val failure: String? = try {
                val param = context.assets.open("models/$baseName.param").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val bin = context.assets.open("models/$baseName.bin").use { it.readBytes() }
                // Ask for the GPU for every network; ncnn falls back to CPU when
                // there is no Vulkan device.
                if (nativeBridge.nativeLoadEspcnModel(param, bin, modelScale, true, inChannels, outChannels)) {
                    null
                } else {
                    "$engineName 加载失败，已回退"
                }
            } catch (e: Exception) {
                "模型文件缺失，已回退到像素边缘重建"
            }

            // A newer selection already superseded this one - stay quiet and
            // leave whatever that newer load installed alone.
            if (failure == null || generation != engineGeneration.get()) return@execute

            nativeBridge.nativeUnloadEspcnModel()
            mainHandler.post {
                if (generation != engineGeneration.get()) return@post
                Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
                profile.engine = UpscaleEngine.PIXEL_EDGE
                nativeBridge.nativeSetPixelEdge(true)
                syncEngineChipToProfile()
            }
        }
    }

    /**
     * Moves the engine chip back onto whatever is actually loaded. Only needed
     * on the fallback path: the load now finishes long after the tap, so
     * without this the chip would keep advertising an ESPCN variant that was
     * dropped. Re-checking re-enters the listener once, which lands on
     * PIXEL_EDGE and unloads - idempotent, and it terminates immediately.
     */
    private val autoCaptureRunnable = object : Runnable {
        override fun run() {
            if (!isAutoCapturing) return
            captureCorpusFrame(skipSimilar = true)
            mainHandler.postDelayed(this, 2000L)
        }
    }

    /**
     * Burst: BURST_FRAMES grabs in quick succession, keeping every one.
     *
     * Battle effects are the hardest thing to collect and the most valuable to
     * have - flashes, particles and big colour washes are exactly where a
     * repainting model has the most to invent. They also last a fraction of a
     * second, so a 2 s poll walks straight past them and a single tap needs
     * reflexes nobody has.
     *
     * The similarity filter is off here on purpose. Consecutive frames of an
     * animation genuinely differ, and anything that does slip through is
     * caught by the offline pass, which compares against the whole corpus
     * rather than just the previous frame. Capture liberally, filter later.
     */
    private var burstRemaining = 0

    private val burstRunnable = object : Runnable {
        override fun run() {
            if (burstRemaining <= 0) return
            burstRemaining--
            captureCorpusFrame(skipSimilar = false)
            if (burstRemaining > 0) mainHandler.postDelayed(this, BURST_INTERVAL_MS)
        }
    }

    private fun startBurst() {
        burstRemaining = BURST_FRAMES
        mainHandler.removeCallbacks(burstRunnable)
        mainHandler.post(burstRunnable)
    }

    /**
     * Reports on the button rather than through a Toast.
     *
     * A Toast is never seen here: TYPE_TOAST sits at window layer 2005 and our
     * overlay at TYPE_APPLICATION_OVERLAY 2038, so the enhanced picture is
     * drawn straight over it. The button label is always visible while the
     * menu is open, which is exactly when this feedback is wanted, and a
     * running count says more than a per-capture confirmation anyway.
     */
    private fun captureCorpusFrame(skipSimilar: Boolean) {
        val console = profile.console
        captureExecutor.execute {
            val result = recorder.captureOnce(console, skipSimilar)
            lastCaptureMessage = result.message
            mainHandler.post {
                updateCaptureButtonText()
                updateAutoCaptureText()
            }
        }
    }

    private fun updateCaptureButtonText() {
        val button = captureButton ?: return
        val count = recorder.countFor(profile.console)
        button.text = when {
            burstRemaining > 0 -> "连拍中… 还剩 $burstRemaining"
            lastCaptureMessage == null -> "截取原生帧（长按连拍）"
            else -> "截取原生帧　已存 $count"
        }
    }

    private fun updateAutoCaptureText(button: Button? = autoCaptureButton) {
        val target = button ?: return
        target.text = if (isAutoCapturing) {
            // The last outcome is worth surfacing: most auto-capture calls are
            // deliberate skips, and without it a stalled counter looks broken.
            "停止自动采集　${recorder.countFor(profile.console)} 张\n${lastCaptureMessage ?: ""}"
        } else {
            "开始自动采集"
        }
    }

    private fun updateGuideButtonText(button: Button) {
        button.text = if (profile.showSourceGuide) "隐藏取景框" else "显示取景框"
    }

    private fun syncEngineChipToProfile() {
        val group = menuView?.findViewById<ChipGroup>(R.id.chipGroupEngine) ?: return
        group.check(
            when (profile.engine) {
                UpscaleEngine.PIXEL_EDGE -> R.id.chipEnginePixelEdge
                UpscaleEngine.SHADER -> R.id.chipEngineShader
                UpscaleEngine.ESPCN_FAST -> R.id.chipEngineEspcnFast
                UpscaleEngine.ESPCN_HQ -> R.id.chipEngineEspcnHq
                UpscaleEngine.ESPCN_ULTRA -> R.id.chipEngineEspcnUltra
                // Not offered in the menu; nothing to check.
                UpscaleEngine.HD2D -> R.id.chipEngineHd2d
                else -> R.id.chipEnginePixelEdge
            }
        )
    }

    /** Reflects the (per-console) saved settings back into the controls. */
    private fun syncMenuToProfile(root: View) {
        root.findViewById<ChipGroup>(R.id.chipGroupMask).check(
            when (profile.maskType) {
                MaskType.APERTURE -> R.id.chipMaskAperture
                MaskType.SHADOW -> R.id.chipMaskShadow
                MaskType.SLOT -> R.id.chipMaskSlot
                MaskType.NONE -> R.id.chipMaskNone
            }
        )
        root.findViewById<ChipGroup>(R.id.chipGroupScale).check(
            when (profile.aiScale) {
                AiScale.X1 -> R.id.chipScale1x
                AiScale.X2 -> R.id.chipScale2x
                AiScale.X3 -> R.id.chipScale3x
                AiScale.X4 -> R.id.chipScale4x
            }
        )
        root.findViewById<ChipGroup>(R.id.chipGroupMask).check(
            when (profile.maskType) {
                MaskType.APERTURE -> R.id.chipMaskAperture
                MaskType.SHADOW -> R.id.chipMaskShadow
                MaskType.SLOT -> R.id.chipMaskSlot
                MaskType.NONE -> R.id.chipMaskNone
            }
        )
        root.findViewById<ChipGroup>(R.id.chipGroupScale).check(
            when (profile.aiScale) {
                AiScale.X1 -> R.id.chipScale1x
                AiScale.X2 -> R.id.chipScale2x
                AiScale.X3 -> R.id.chipScale3x
                AiScale.X4 -> R.id.chipScale4x
            }
        )
        root.findViewById<ChipGroup>(R.id.chipGroupEngine).check(
            when (profile.engine) {
                UpscaleEngine.PIXEL_EDGE -> R.id.chipEnginePixelEdge
                UpscaleEngine.SHADER -> R.id.chipEngineShader
                UpscaleEngine.ESPCN_FAST -> R.id.chipEngineEspcnFast
                UpscaleEngine.ESPCN_HQ -> R.id.chipEngineEspcnHq
                UpscaleEngine.ESPCN_ULTRA -> R.id.chipEngineEspcnUltra
                // Not offered in the menu; nothing to check.
                UpscaleEngine.HD2D -> R.id.chipEngineHd2d
                else -> R.id.chipEnginePixelEdge
            }
        )
        root.findViewById<SwitchMaterial>(R.id.switchAiEnable).isChecked = profile.isAiEnabled
        root.findViewById<SeekBar>(R.id.seekbarScanline).progress =
            (profile.scanlineIntensity * 100).toInt()
        root.findViewById<SeekBar>(R.id.seekbarLcdGrid).progress =
            (profile.lcdGridIntensity * 100).toInt()

    }

    /**
     * Writes the current capture window into RetroArch's per-core overrides.
     * The main retroarch.cfg is unreachable on Android 11+ (it lives under
     * Android/data), so overrides are the only writable path.
     */
    private fun applyRetroArchConfig() {
        if (!RetroArchConfigManager.hasAllFilesAccess()) {
            Toast.makeText(context, "需要「所有文件访问」权限，请在主界面授权", Toast.LENGTH_LONG).show()
            context.startActivity(RetroArchConfigManager.allFilesAccessIntent(context))
            return
        }

        val manager = RetroArchConfigManager(context)
        if (manager.findConfigRoot() == null) {
            Toast.makeText(context, "没找到 RetroArch/config 目录", Toast.LENGTH_LONG).show()
            return
        }

        val folders = manager.coreFoldersFor(profile.console)
        if (folders.isEmpty()) {
            Toast.makeText(
                context,
                "没找到 ${profile.console.displayName} 对应的核心配置目录，请先在 RA 里跑一次该平台游戏",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Never write without a snapshot in place. The service takes one at
        // startup; this is the last-chance check.
        val backup = manager.ensureFreshBackup()
        if (!backup.ok) {
            Toast.makeText(context, "备份失败，已取消写入：${backup.message}", Toast.LENGTH_LONG).show()
            return
        }

        val result = manager.applyViewport(
            folders,
            profile.console.nativeWidth * profile.sourceScale,
            profile.console.nativeHeight * profile.sourceScale,
            profile.sourceCorner.biasX,
            profile.sourceCorner.biasY,
            profile.disableRaShader
        )

        if (result.ok) {
            // The emulator is about to draw at a different size/place, so any
            // previously measured capture window is now wrong. Keeping it would
            // silently sample the wrong area and look like the picture "moved".
            invalidateDetectedWindow()
        }
        Toast.makeText(
            context,
            if (result.ok) "${result.message}\n重启 RA 后请重新【自动探测】" else result.message,
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Measures where the emulator is really drawing and locks the capture
     * window onto it. Beats predicting the position: RetroArch's placement
     * depends on its video driver, its integer-scaling mode and whatever the
     * repack's own overrides do.
     */
    fun detectSourceWindow(silent: Boolean = false) {
        if (!silent) Toast.makeText(context, "正在探测游戏画面位置…", Toast.LENGTH_SHORT).show()

        // Our own floating windows are part of the captured screen too - the
        // ball is a bright round blob and the detector happily locked onto it.
        // Take them off screen for the measurement.
        val ballWasVisible = floatingBallView?.isAttachedToWindow == true
        val menuWasVisible = isMenuShowing
        hideOwnWindowsForDetection()

        // Native size, NOT native*sourceScale: the emulator may be drawing at
        // any integer multiple and the detector tries them all.
        nativeBridge.nativeRequestSourceDetection(
            profile.console.nativeWidth,
            profile.console.nativeHeight
        )

        val rect = IntArray(4)
        var attempts = 0
        val poll = object : Runnable {
            override fun run() {
                if (nativeBridge.nativeGetDetectedRect(rect)) {
                    val detected = android.graphics.Rect(
                        rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]
                    )
                    profile.detectedSourceRect = detected
                    applyRenderProfile()
                    restoreOwnWindowsAfterDetection(ballWasVisible, menuWasVisible)
                    if (!silent) {
                        Toast.makeText(
                            context,
                            "已锁定画面: ${detected.width()}×${detected.height()} @ (${detected.left}, ${detected.top})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return
                }
                if (++attempts < 20) {
                    mainHandler.postDelayed(this, 150)
                } else {
                    restoreOwnWindowsAfterDetection(ballWasVisible, menuWasVisible)
                    if (!silent) {
                        Toast.makeText(
                            context,
                            "没找到游戏画面。确认游戏正在显示、且不是全黑画面",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        mainHandler.postDelayed(poll, 300)
    }

    private fun hideOwnWindowsForDetection() {
        menuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        floatingBallView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        edgeTabView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
    }

    private fun restoreOwnWindowsAfterDetection(ballWasVisible: Boolean, menuWasVisible: Boolean) {
        if (ballWasVisible || isBallHidden) showFloatingBall()
        if (menuWasVisible) {
            menuView?.let { if (!it.isAttachedToWindow) windowManager.addView(it, menuParams) }
            isMenuShowing = true
            }
        isBallHidden = false
        resetAutoHideTimer()
    }

    /** Drops a measured capture window that no longer matches the config. */
    private fun invalidateDetectedWindow() {
        if (profile.detectedSourceRect == null) return
        profile.detectedSourceRect = null
        applyRenderProfile()
    }

    private fun clearDetectedWindow() {
        profile.detectedSourceRect = null
        applyRenderProfile()
        Toast.makeText(context, "已改回按角落计算的取景窗", Toast.LENGTH_SHORT).show()
    }

    private fun restoreRetroArchConfig() {
        if (!RetroArchConfigManager.hasAllFilesAccess()) {
            Toast.makeText(context, "需要「所有文件访问」权限", Toast.LENGTH_LONG).show()
            return
        }
        // Restores from the newest snapshot and KEEPS it, so repeated
        // apply/restore cycles never run out of a safety net.
        val result = RetroArchConfigManager(context).restoreFromLatestBackup()
        // Restoring changes the emulator's layout back, so the lock is stale too.
        if (result.ok) invalidateDetectedWindow()
        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
    }

    /** Output pixels covering one game pixel - both retro effects scale with it. */
    private fun outputPixelsPerGamePixel(): Float {
        val out = profile.getOutputRect(screenWidth, screenHeight)
        if (profile.console.nativeWidth <= 0) return 0f
        return minOf(
            out.width().toFloat() / profile.console.nativeWidth,
            out.height().toFloat() / profile.console.nativeHeight
        )
    }

    private fun triadLabel(value: Float): String =
        "遮罩强度  ${(value * 100).toInt()}%"

    private fun applyRenderProfile() {
        nativeBridge.nativeSetMaskType(profile.maskType.id)
        nativeBridge.nativeSetHd2d(profile.hd2dEnabled, profile.hd2dStrength)
        nativeBridge.nativeSetDof(profile.dofStrength)
        nativeBridge.nativeSetBloom(profile.bloomStrength)
        nativeBridge.nativeSetRenderConfig(
            profile.isAiEnabled,
            profile.console.nativeWidth,
            profile.console.nativeHeight,
            profile.scanlineIntensity,
            profile.lcdGridIntensity
        )
        ProfilePreference.save(context, profile)
        // The service owns the geometry push: it knows the real screen size
        // of the overlay surface.
        onProfileChanged(profile)
    }

    private fun toggleMenu() {
        if (isMenuShowing) hideMenu() else showMenu()
    }

    fun showMenu() {
        if (isMenuShowing) return
        mainHandler.removeCallbacks(autoHideRunnable)
        if (isBallHidden) wakeBallFromEdge()
        isMenuShowing = true

        positionMenuAwayFromSource()
        menuView?.let {
            if (!it.isAttachedToWindow) windowManager.addView(it, menuParams)
        }
        mainHandler.post(hudUpdateRunnable)
    }

    /**
     * The menu is a normal window, so MediaProjection captures it like anything
     * else. If it overlaps the capture window even by a few pixels, that sliver
     * gets sampled and blown up across the output - it looks like a ghostly
     * reflection of the menu inside the game picture. So park the menu in the
     * corner opposite the capture window.
     */
    private fun positionMenuAwayFromSource() {
        val src = profile.getSourceRect(screenWidth, screenHeight)
        val onRight = src.centerX() > screenWidth / 2
        val onBottom = src.centerY() > screenHeight / 2

        menuParams.gravity = (if (onRight) Gravity.START else Gravity.END) or
                (if (onBottom) Gravity.TOP else Gravity.BOTTOM)
        menuParams.x = dp(8)
        menuParams.y = dp(8)

        // Keep it clear of the capture window's band as well as beside it.
        val freeHeight = if (onBottom) src.top - dp(16) else screenHeight - src.bottom - dp(16)
        val freeWidth = if (onRight) src.left - dp(16) else screenWidth - src.right - dp(16)
        val wanted = (screenWidth * 0.82f).toInt().coerceIn(dp(300), dp(420))

        menuParams.width = if (freeWidth > dp(280)) minOf(wanted, freeWidth) else wanted
        menuParams.height = maxOf(freeHeight, (screenHeight * 0.6f).toInt())
            .coerceAtMost((screenHeight * 0.92f).toInt())

        if (menuView?.isAttachedToWindow == true) {
            windowManager.updateViewLayout(menuView, menuParams)
        }
    }

    private fun hideMenu() {
        if (!isMenuShowing) return
        isMenuShowing = false
        mainHandler.removeCallbacks(hudUpdateRunnable)

        menuView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        resetAutoHideTimer()
    }

    private fun updateHudStats() {
        val stats = FloatArray(6)
        if (!nativeBridge.nativeGetPerformanceStats(stats)) return

        val (fps, captureMs, aiMs, renderMs) = stats.toList()
        val swapMs = stats[4]
        floatingBallView?.findViewById<TextView>(R.id.tvBallFps)?.text = "%.0f".format(fps)
        // Which backend ncnn actually landed on. Asking for the GPU does not
        // guarantee getting it - with no Vulkan device it silently falls back
        // to CPU, and a cost that looks alarming on the GPU is simply expected
        // on two big cores. Showing it here beats grepping logcat at load time.
        val backend = when (stats[5].toInt()) {
            1 -> "GPU/Vulkan"
            0 -> "CPU/NEON"
            else -> null
        }
        // AI runs on its own thread and the swap blocks on vsync, so neither
        // belongs in the frame's work cost - reporting them together would
        // make a vsync-limited pipeline look compute-bound.
        val aiText = if (aiMs > 0.01f && backend != null) {
            "AI 推理 %.1f ms（异步 · %s）".format(aiMs, backend)
        } else if (aiMs > 0.01f) {
            "AI 推理 %.1f ms（异步）".format(aiMs)
        } else {
            "AI 推理 未启用"
        }
        menuView?.findViewById<TextView>(R.id.tvPerformanceHud)?.text =
            ("帧率 %.1f FPS\n" +
                    "GPU 耗时 %.1f ms（采样 %.1f + 渲染 %.1f）· 垂直同步 %.1f ms\n" +
                    "%s")
                .format(fps, captureMs + renderMs, captureMs, renderMs, swapMs, aiText)
    }

    fun release() {
        // Let an in-flight load finish rather than killing the thread mid
        // ncnn::Net construction; the service's own teardown already waits on
        // the pipeline before the renderer goes away.
        engineExecutor.shutdown()
        isAutoCapturing = false
        captureExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        floatingBallView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        edgeTabView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        menuView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        floatingBallView = null
        edgeTabView = null
        menuView = null
    }
}
