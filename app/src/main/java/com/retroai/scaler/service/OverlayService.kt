package com.retroai.scaler.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.retroai.scaler.MainActivity
import com.retroai.scaler.R
import com.retroai.scaler.capture.CaptureBridge
import com.retroai.scaler.detector.ForegroundAppMonitor
import com.retroai.scaler.detector.RetroArchConfigManager
import com.retroai.scaler.detector.TargetAppPreference
import com.retroai.scaler.ui.ProfilePreference
import com.retroai.scaler.jni.NativeBridge
import com.retroai.scaler.ui.FloatingBallManager

/**
 * Foreground Overlay Service hosting the full-screen transparent SurfaceView,
 * Floating Ball UI, and MediaProjection Capture Pipeline.
 *
 * Safety rules this service must never break:
 *  1. The overlay is NOT marked secure. A fullscreen FLAG_SECURE layer makes
 *     SurfaceFlinger replace it with opaque black inside the MediaProjection
 *     mirror, so the capture is all black and the overlay paints the whole
 *     device black.
 *  2. The SurfaceView is NOT z-ordered on top, otherwise it covers the
 *     floating ball / menu and the user is left with no way out.
 *  3. If frames stop arriving, the watchdog wipes the overlay transparent.
 */
class OverlayService : Service(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "retro_ai_channel"

        private const val WATCHDOG_INTERVAL_MS = 500L

        /** How often the foreground app is polled. */
        private const val FOREGROUND_POLL_MS = 400L

        /** Capture that never produced a single frame is a broken pipeline. */
        private const val FIRST_FRAME_TIMEOUT_MS = 4000L

        /** A long stall means something died; a paused game is fine below this. */
        private const val FRAME_STALL_TIMEOUT_MS = 10_000L

        /**
         * How long the overlay surface has to hold a size before it counts as a
         * real display change. Long enough to swallow the inset flicker that
         * Recents produces, short enough that a rotation still feels immediate.
         */
        private const val SURFACE_SETTLE_MS = 350L

        /**
         * Window alpha used while the overlay is not painting, chosen to sit
         * under the obscuring limit that makes Android withhold touches from
         * other apps (default 0.8) without ever reaching zero.
         *
         * Zero is what it used to be, and zero is the problem: a fully
         * transparent window stops being composited, and bringing the alpha
         * back afterwards did not reconnect the SurfaceView's layer. The
         * renderer then drew and swapped perfectly happily - success=1,
         * paused=0, valid geometry - into a surface that no longer reached the
         * screen. Nothing is lost by staying at 0.5: the paused overlay has
         * already drawn a fully transparent frame, so its contents are
         * invisible at any alpha.
         */
        private const val PASSTHROUGH_ALPHA = 0.5f

        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        const val ACTION_OPEN_MENU = "com.retroai.scaler.ACTION_OPEN_MENU"
        const val ACTION_STOP = "com.retroai.scaler.ACTION_STOP"

        /** Read by MainActivity so the toggle button reflects reality. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val nativeBridge = NativeBridge()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var surfaceView: SurfaceView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    /** Latest size reported by surfaceChanged, acted on once it stops moving. */
    private var pendingSurfaceWidth = 0
    private var pendingSurfaceHeight = 0

    /**
     * True while the overlay window is being torn down and put straight back on
     * purpose, so surfaceDestroyed knows to keep the capture pipeline alive.
     */
    private var isRecreatingOverlay = false
    private var floatingBallManager: FloatingBallManager? = null
    private var captureBridge: CaptureBridge? = null
    private var mediaProjection: MediaProjection? = null

    private var foregroundMonitor: ForegroundAppMonitor? = null
    private var targetPackage: String? = null
    private var hasUsageAccess = false

    /** False while the target app is off screen. */
    private var isRenderingActive = true

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensityDpi = 0
    private var isSurfaceReady = false
    private var isOverlayCleared = false

    /**
     * The overlay must only paint while the target emulator is on screen.
     * Otherwise the enhanced image covers the launcher, Recents and every other
     * app, and the user cannot see their way back to the game.
     */
    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            updateTargetForegroundState()
            mainHandler.postDelayed(this, FOREGROUND_POLL_MS)
        }
    }

    /**
     * MediaProjection only pushes a frame when the screen content changes, so a
     * paused game legitimately stops the stream - that must not kill the
     * picture. What must never happen is a pipeline that is broken (or dead)
     * while the overlay keeps a stale image on screen.
     */
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val bridge = captureBridge
            // A paused pipeline produces no frames by design - do not let the
            // stall detector interpret that as a broken pipeline.
            if (bridge != null && !bridge.isPaused) {
                val now = SystemClock.elapsedRealtime()
                if (bridge.renderedFrames == 0L && now - bridge.startedAtMs > FIRST_FRAME_TIMEOUT_MS) {
                    Log.e(TAG, "No frame ever reached the renderer - shutting down.")
                    nativeBridge.nativeClearOverlay()
                    Toast.makeText(
                        this@OverlayService,
                        "没有捕获到任何画面，已停止（请检查录屏权限）",
                        Toast.LENGTH_LONG
                    ).show()
                    stopSelf()
                    return
                }

                val idleMs = now - bridge.lastFrameAtMs
                if (idleMs > FRAME_STALL_TIMEOUT_MS && !isOverlayCleared) {
                    Log.w(TAG, "No frame for ${idleMs}ms - wiping overlay transparent.")
                    nativeBridge.nativeClearOverlay()
                    isOverlayCleared = true
                } else if (idleMs <= FRAME_STALL_TIMEOUT_MS) {
                    isOverlayCleared = false
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensityDpi = metrics.densityDpi

        foregroundMonitor = ForegroundAppMonitor(this)
        targetPackage = TargetAppPreference.get(this) ?: foregroundMonitor?.detectRetroArch()
        hasUsageAccess = ForegroundAppMonitor.hasUsageAccess(this)
        // Start paused whenever we CAN tell: the user is looking at our own
        // Activity right now, not at the game.
        isRenderingActive = !(hasUsageAccess && targetPackage != null)
        if (!hasUsageAccess) {
            Log.w(TAG, "No usage access - painting unconditionally (overlay will cover other apps).")
        }

        ensureConfigBackup()
        setupFullScreenOverlay()
        setupFloatingBall()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        mainHandler.post(foregroundPollRunnable)
    }

    /**
     * Takes a dated snapshot of RetroArch's config tree if there is none, or if
     * the newest is older than 15 days. Runs off the main thread: it walks a
     * few hundred files on external storage.
     */
    private fun ensureConfigBackup() {
        Thread {
            try {
                val manager = RetroArchConfigManager(this)

                // A previous session that was killed (crash, force stop, low
                // memory) leaves RetroArch crippled in its corner window. Heal
                // that BEFORE snapshotting, or the snapshot would capture our
                // own edits as if they were the user's originals.
                if (manager.hasModifiedFiles()) {
                    val healed = manager.restoreFromLatestBackup()
                    Log.i(TAG, "healed leftover config from a previous session: ${healed.message}")
                }

                val result = manager.ensureFreshBackup()
                Log.i(TAG, "config backup: ${result.message}")
                if (result.ok && result.message.startsWith("已备份")) {
                    mainHandler.post {
                        Toast.makeText(this, "RetroArch 配置已备份\n${result.message}", Toast.LENGTH_LONG).show()
                    }
                }

                applyConfigOnStart(manager)
            } catch (e: Exception) {
                Log.e(TAG, "config backup failed", e)
            }
        }.apply { name = "ConfigBackup"; isDaemon = true }.start()
    }

    /**
     * Configures RetroArch the moment the enhancer starts, so the very first
     * run works: without this the user has to start the app, open the menu,
     * press 自动写入, then restart RetroArch before anything lines up.
     *
     * Uses the persisted platform choice - the floating menu may never have
     * been opened in this session.
     */
    private fun applyConfigOnStart(manager: RetroArchConfigManager) {
        val profile = ProfilePreference.load(this)
        val folders = manager.coreFoldersFor(profile.console)
        if (folders.isEmpty()) {
            Log.w(TAG, "no core config folder for ${profile.console.displayName}, skipping auto-write")
            mainHandler.post {
                Toast.makeText(
                    this,
                    "没找到 ${profile.console.displayName} 的核心配置目录，请先在 RA 里跑一次该平台游戏",
                    Toast.LENGTH_LONG
                ).show()
            }
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
        Log.i(TAG, "auto-write on start: ${result.message}")
        mainHandler.post {
            Toast.makeText(
                this,
                if (result.ok) "已配置 ${profile.console.displayName}，请重启 RetroArch" else result.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Renders only while [targetPackage] is the foreground app. Without usage
     * access there is no way to know, so painting stays on - degraded, but the
     * user still gets the feature they asked for.
     */
    private fun updateTargetForegroundState() {
        val monitor = foregroundMonitor ?: return
        val target = targetPackage

        val shouldRender = when {
            !hasUsageAccess -> true
            target == null -> true
            else -> monitor.currentForegroundPackage() == target
        }
        if (shouldRender == isRenderingActive) return

        isRenderingActive = shouldRender
        Log.i(TAG, if (shouldRender) "Target app in foreground - resuming." else "Target app left - pausing.")
        applyRenderingState()
        updateNotification()

        // The capture window has to be measured while the game is actually on
        // screen. Doing it automatically here is what removes the "every time I
        // start it the picture is misaligned until I press detect" step.
        //
        // Note there is deliberately no surface recreate here. Returning from
        // Recents can be quicker than the 400 ms foreground poll, so this
        // transition is not even observed in the case that needs it - the
        // window resize flicker is, and that is where the recreate hangs off.
        if (shouldRender) scheduleAutoDetect()
    }

    /**
     * Measures the capture window shortly after the game appears - long enough
     * for the emulator to have drawn a real frame, not a black one.
     */
    private fun scheduleAutoDetect() {
        val manager = floatingBallManager ?: return
        if (manager.profile.detectedSourceRect != null) return
        mainHandler.removeCallbacks(autoDetectRunnable)
        mainHandler.postDelayed(autoDetectRunnable, 1500L)
    }

    private val autoDetectRunnable = Runnable {
        val manager = floatingBallManager ?: return@Runnable
        if (!isRenderingActive || manager.profile.detectedSourceRect != null) return@Runnable
        Log.i(TAG, "auto-detecting capture window")
        manager.detectSourceWindow(silent = true)
    }

    /** Pushes [isRenderingActive] down to the renderer and the capture pipeline. */
    /**
     * Order matters, and getting it wrong leaves the overlay frozen on screen.
     *
     * clearOverlay() has to make the EGL context current on THIS thread to draw
     * its transparent frame, but the capture thread holds that context - it
     * acquires it per frame and never hands it back. Wiping before the frames
     * stop therefore means ensureEglContextCurrent() fails, the wipe silently
     * does nothing, and pausing immediately afterwards freezes the last
     * enhanced frame over everything. That is what made opening Recents look
     * like the device had hung: touches passed through fine, but a stale
     * picture of the game covered the screen.
     *
     * It survived on a 60 Hz handheld because the gaps between frames were
     * wide enough for the main thread to win the context often enough. At
     * 120 Hz it essentially never wins.
     *
     * So: stop the frames, take the context back, and only then wipe.
     */
    private fun applyRenderingState() {
        if (isRenderingActive) {
            setOverlayObscuring(true)
            nativeBridge.nativeSetRenderPaused(false)
            captureBridge?.resumeCapture()
        } else {
            captureBridge?.pauseCapture()
            // The wipe runs ON the capture thread, where the EGL context
            // already is. Doing it from here instead means handing the context
            // across threads and back on every app switch, and after that
            // round trip the renderer kept reporting healthy frames that never
            // reached the screen.
            val bridge = captureBridge
            val wiped = bridge?.runOnCaptureThread {
                nativeBridge.nativeSetRenderPaused(true)
            } ?: false
            if (!wiped) {
                // No capture thread to borrow (not started yet, or gone) - the
                // context cannot be current anywhere else, so this is safe.
                nativeBridge.nativeSetRenderPaused(true)
            }
            // After the wipe, so the window is already blank before it stops
            // obscuring and hands touches back to whatever is behind.
            setOverlayObscuring(false)
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildForegroundNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notification update failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested from notification.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_MENU -> {
                floatingBallManager?.showMenu()
                return START_NOT_STICKY
            }
        }

        if (intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            if (resultCode != 0 && data != null) {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                startCapturePipeline()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupFullScreenOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Starts non-obscuring. The service usually comes up while the user
            // is still looking at our own Activity rather than the game, and an
            // overlay that blocks other apps' touches before it has painted
            // anything is never the right default. applyRenderingState() raises
            // it once the target app is actually on screen.
            alpha = PASSTHROUGH_ALPHA
        }

        // NOTE: no setZOrderOnTop(true) and no setSecure(true) here - see the
        // class comment. The surface still composites above every window below
        // this overlay, and stays below the floating ball window added later.
        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(this@OverlayService)
        }

        overlayParams = params
        windowManager?.addView(surfaceView, params)
    }

    /**
     * Drops the overlay's opacity to zero while it is not painting, which is
     * what lets other apps receive touches again.
     *
     * Android blocks a touch that passes through a window belonging to another
     * UID once that window's opacity goes over the obscuring limit, and the
     * system already pins this one at 0.8 for that reason. On this handheld
     * touches to anything outside our own UID were being swallowed anyway - the
     * launcher and Recents were visible but completely dead, while our own
     * floating menu kept working, which is the exact signature of a cross-UID
     * obscuring block.
     *
     * Zero opacity means the window obscures nothing, so it stops counting.
     * The window is deliberately left attached rather than removed: taking the
     * SurfaceView out destroys the surface, which tears down the renderer and
     * reloads the ESPCN weights on every single app switch.
     */
    private fun setOverlayObscuring(obscuring: Boolean) {
        val params = overlayParams ?: return
        val view = surfaceView ?: return
        val wanted = if (obscuring) 1.0f else PASSTHROUGH_ALPHA
        // Always re-applied, never short-circuited on the cached value: this
        // controls whether the enhanced picture is visible at all, and a local
        // field that has drifted out of step with the real window would make
        // the overlay silently invisible while the whole pipeline reports
        // healthy.
        params.alpha = wanted
        if (view.isAttachedToWindow) {
            try {
                windowManager?.updateViewLayout(view, params)
                Log.i(TAG, "Overlay alpha -> $wanted (obscuring=$obscuring)")
            } catch (e: Exception) {
                Log.w(TAG, "overlay alpha update failed", e)
            }
        } else {
            Log.w(TAG, "Overlay alpha -> $wanted skipped: view not attached")
        }
    }

    private fun setupFloatingBall() {
        floatingBallManager = FloatingBallManager(
            this,
            windowManager!!,
            nativeBridge,
            onProfileChanged = { profile ->
                pushGeometry()
                Log.d(TAG, "Render profile updated: ${profile.console.displayName} ${profile.getOutputScale(screenWidth, screenHeight)}x")
            },
            onServiceStopRequested = {
                stopSelf()
            }
        )
        floatingBallManager?.init()
    }

    /** Pushes the current source/output geometry down to the renderer. */
    private fun pushGeometry() {
        val profile = floatingBallManager?.profile ?: return
        val src = profile.getSourceRect(screenWidth, screenHeight)
        val out = profile.getOutputRect(screenWidth, screenHeight)
        nativeBridge.nativeSetGeometry(
            src.left, src.top, src.width(), src.height(),
            out.left, out.top, out.width(), out.height(),
            profile.showSourceGuide
        )
    }

    private fun startCapturePipeline() {
        val mp = mediaProjection ?: return
        if (captureBridge != null || !isSurfaceReady) return

        val bridge = CaptureBridge(
            mp,
            screenWidth,
            screenHeight,
            screenDensityDpi,
            nativeBridge,
            onProjectionStopped = { mainHandler.post { stopSelf() } }
        )
        if (!bridge.startCapture()) {
            Toast.makeText(this, "录屏捕获启动失败，已停止 AI 增强", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        captureBridge = bridge
        pushGeometry()
        // Honour the current foreground state right away, otherwise the overlay
        // would paint over this very Activity until the next poll.
        applyRenderingState()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "Surface created: ${screenWidth}x${screenHeight}")
        isRecreatingOverlay = false
        val success = nativeBridge.nativeInit(holder.surface, screenWidth, screenHeight)
        if (!success) {
            Log.e(TAG, "nativeInit failed - tearing down so nothing covers the screen.")
            Toast.makeText(this, "渲染引擎初始化失败，已停止 AI 增强", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isSurfaceReady = true
        // Model loading and render config only stick once the native renderer
        // exists, so push everything here rather than at ball setup time.
        floatingBallManager?.pushAllSettings()
        pushGeometry()
        applyRenderingState()
        startCapturePipeline()
    }

    /**
     * Rebuilds the whole pipeline when the display rotates.
     *
     * Everything downstream is sized once, in screen pixels: the renderer's
     * viewport, the ImageReader, the VirtualDisplay, and the source/output
     * rects. On a handheld whose panel is portrait-native (1080x1920 driven at
     * 1920x1080) a rotation swaps every one of those, and the previous code
     * latched them in onCreate() and never looked again - the enhanced picture
     * kept being drawn at the old landscape coordinates over a portrait
     * surface, and never recovered without restarting the service.
     *
     * The surface's own size is the trigger rather than onConfigurationChanged:
     * it is the number the renderer actually draws against, so it cannot
     * disagree with what we hand to nativeInit.
     */
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!isSurfaceReady) {
            Log.i(TAG, "surfaceChanged ${width}x${height} ignored: surface not ready")
            return
        }
        // Debounced, because not every resize is a rotation. Opening Recents
        // momentarily reshapes the overlay to the inset-reduced height and
        // straight back (1920x1080 -> 1920x1024 -> 1920x1080), which the
        // previous immediate handling turned into two full teardowns of EGL,
        // the renderer and the ncnn model inside 300 ms - on every single app
        // switch. Waiting for the size to settle collapses that transient to
        // no net change, while a real rotation settles on the new size and
        // rebuilds exactly once.
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height
        mainHandler.removeCallbacks(surfaceSettleRunnable)
        mainHandler.postDelayed(surfaceSettleRunnable, SURFACE_SETTLE_MS)
    }

    private val surfaceSettleRunnable = Runnable { applySettledSurfaceSize() }

    private fun applySettledSurfaceSize() {
        val width = pendingSurfaceWidth
        val height = pendingSurfaceHeight
        if (width <= 0 || height <= 0) return
        if (!isSurfaceReady || (width == screenWidth && height == screenHeight)) {
            Log.i(TAG, "surfaceChanged settled at ${width}x${height} (no rebuild)")
            return
        }
        val holder = surfaceView?.holder ?: return
        if (!holder.surface.isValid) {
            Log.w(TAG, "surfaceChanged settled but surface is not valid - skipping rebuild")
            return
        }
        Log.i(TAG, "Display changed: ${screenWidth}x${screenHeight} -> ${width}x${height}, rebuilding pipeline.")

        // Suspend delivery first: frames sized for the old screen must not
        // reach a renderer that is being rebuilt for the new one.
        val bridge = captureBridge
        bridge?.pauseCapture()

        // nativeRelease() while the EGL context is still current on the capture
        // thread is the EGL_BAD_ACCESS trap from the class notes, so hand the
        // renderer back from that thread before touching it.
        bridge?.detachEglContext()
        nativeBridge.nativeRelease()
        isSurfaceReady = false

        screenWidth = width
        screenHeight = height
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenDensityDpi = metrics.densityDpi

        // Drops the measured capture window - it is in the old orientation's
        // pixels - and pulls the ball back inside the new bounds.
        floatingBallManager?.onScreenSizeChanged(width, height)

        if (!nativeBridge.nativeInit(holder.surface, width, height)) {
            Log.e(TAG, "nativeInit failed after rotation - tearing down so nothing covers the screen.")
            Toast.makeText(this, "旋转后渲染引擎重建失败，已停止 AI 增强", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isSurfaceReady = true

        // The renderer is a new object: the ESPCN weights and every render
        // setting live in it and have to be pushed again.
        floatingBallManager?.pushAllSettings()
        pushGeometry()

        if (bridge != null && !bridge.resizeTo(width, height, screenDensityDpi)) {
            Log.e(TAG, "capture resize failed after rotation - stopping.")
            Toast.makeText(this, "旋转后录屏重建失败，已停止 AI 增强", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        applyRenderingState()

        // The capture window has to be re-measured against the new screen, and
        // RetroArch needs a moment to finish its own re-layout first.
        if (isRenderingActive) scheduleAutoDetect()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed. (intentional recreate=$isRecreatingOverlay)")
        // A rebuild queued against a surface that no longer exists must not run.
        mainHandler.removeCallbacks(surfaceSettleRunnable)
        isSurfaceReady = false

        if (isRecreatingOverlay) {
            // Only the GL side goes. Stopping the capture here would release
            // the VirtualDisplay, and rebuilding it means a second
            // createVirtualDisplay() on the same MediaProjection - which is
            // exactly what the resize path was written to avoid.
            captureBridge?.pauseCapture()
            captureBridge?.detachEglContext()
            nativeBridge.nativeRelease()
            return
        }

        captureBridge?.stopCapture()
        captureBridge = null
        nativeBridge.nativeRelease()
    }

    /**
     * Throws the overlay window away and immediately adds it back, to get a
     * fresh surface.
     *
     * KNOWN LIMITATION, kept because it is the only lever we have: returning to
     * the emulator by tapping its own card in Recents orphans the SurfaceView's
     * buffer queue. SurfaceFlinger's frame counter freezes while every call on
     * this side keeps succeeding - success=1, paused=0, valid geometry, no
     * picture - and surfaceDestroyed is never delivered, so nothing notices.
     *
     * Nothing currently calls this. Two triggers were tried and both failed:
     * the foreground transition is often not observed at all (returning from
     * Recents beats the 400 ms poll), and the window-resize flicker that the
     * transition produces only happens some of the time. Without a dependable
     * signal an unconditional rebuild on every app switch was the only option
     * left, and that costs a renderer re-init plus a model reload each time.
     *
     * Leaving via any other app rebuilds the window naturally, which is why
     * that path always works and is the current workaround.
     */
    @Suppress("unused")
    private fun recreateOverlaySurface() {
        val view = surfaceView ?: return
        val params = overlayParams ?: return
        if (isRecreatingOverlay) return

        isRecreatingOverlay = true
        try {
            if (view.isAttachedToWindow) windowManager?.removeView(view)
            windowManager?.addView(view, params)
            Log.i(TAG, "Overlay window recreated to get a fresh surface.")
        } catch (e: Exception) {
            Log.e(TAG, "overlay recreate failed", e)
            isRecreatingOverlay = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Retro-AI-Scaler Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "RetroArch AI Enhancement Background Service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val openMenuPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_OPEN_MENU },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // The escape hatch. Must always be reachable, whatever the overlay is doing.
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val targetLabel = targetPackage?.let { foregroundMonitor?.labelFor(it) }
        val statusText = when {
            !hasUsageAccess -> "未授予「使用情况访问」，将始终渲染"
            targetPackage == null -> "未找到 RetroArch，将始终渲染"
            isRenderingActive -> "增强中 · $targetLabel"
            else -> "待机中 · 等待 $targetLabel 切到前台"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Retro-AI-Scaler 运行中")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, "控制菜单", openMenuPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed from Recents, stopping OverlayService...")
        stopSelf()
    }

    /**
     * RetroArch must not be left rendering into a corner once the enhancer is
     * gone. Runs on its own thread so a manual stop cannot interrupt it midway,
     * and each file is replaced atomically (temp + rename).
     */
    private fun restoreConfigOnStop() {
        val appContext = applicationContext
        Thread {
            try {
                val manager = RetroArchConfigManager(appContext)
                if (!manager.hasModifiedFiles()) {
                    Log.i(TAG, "no modified RetroArch config to restore.")
                    return@Thread
                }
                val result = manager.restoreFromLatestBackup()
                Log.i(TAG, "auto-restore on stop: ${result.message}")
            } catch (e: Exception) {
                Log.e(TAG, "auto-restore on stop failed", e)
            }
        }.apply { name = "ConfigRestore"; isDaemon = false }.start()
    }

    override fun onDestroy() {
        restoreConfigOnStop()
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.removeCallbacks(foregroundPollRunnable)
        mainHandler.removeCallbacks(autoDetectRunnable)
        mainHandler.removeCallbacks(surfaceSettleRunnable)

        // Order matters: stop producing frames, then wipe the overlay, then
        // tear down GL, then finally detach the window.
        captureBridge?.stopCapture()
        captureBridge = null
        mediaProjection?.stop()
        mediaProjection = null

        nativeBridge.nativeRelease()

        floatingBallManager?.release()
        floatingBallManager = null

        surfaceView?.let {
            if (it.isAttachedToWindow) {
                windowManager?.removeView(it)
            }
        }
        surfaceView = null

        isRunning = false
        super.onDestroy()
        Log.i(TAG, "OverlayService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
