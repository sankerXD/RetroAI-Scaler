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
    private fun applyRenderingState() {
        nativeBridge.nativeSetRenderPaused(!isRenderingActive)
        if (isRenderingActive) {
            captureBridge?.resumeCapture()
        } else {
            captureBridge?.pauseCapture()
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
        }

        // NOTE: no setZOrderOnTop(true) and no setSecure(true) here - see the
        // class comment. The surface still composites above every window below
        // this overlay, and stays below the floating ball window added later.
        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.TRANSLUCENT)
            holder.addCallback(this@OverlayService)
        }

        windowManager?.addView(surfaceView, params)
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Overlay is fullscreen and orientation-locked to the display metrics
        // captured in onCreate; nothing to do here.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "Surface destroyed.")
        isSurfaceReady = false
        captureBridge?.stopCapture()
        captureBridge = null
        nativeBridge.nativeRelease()
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
