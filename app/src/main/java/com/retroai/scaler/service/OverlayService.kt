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
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.retroai.scaler.MainActivity
import com.retroai.scaler.R
import com.retroai.scaler.capture.CaptureBridge
import com.retroai.scaler.capture.FrameSource
import com.retroai.scaler.shim.ShimFrameService
import com.retroai.scaler.shim.ShimFrameSource
import com.retroai.scaler.detector.HardwareCoreNotice
import com.retroai.scaler.detector.ForegroundAppMonitor
import com.retroai.scaler.detector.RetroArchConfigManager
import com.retroai.scaler.detector.TargetAppPreference
import com.retroai.scaler.ui.CaptureMode
import com.retroai.scaler.ui.LocaleHelper
import com.retroai.scaler.ui.ProfilePreference
import com.retroai.scaler.ui.consoleForCore
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

    /**
     * The language override has to be applied HERE as well as in MainActivity.
     *
     * Everything the player sees while a game is running is drawn from this
     * context, not an Activity's: the floating menu is inflated from it, and
     * every toast and notification is built with it. Wrapping only the Activity
     * left the main screen following the manual choice while the overlay stayed
     * in the system language - which looks like "the translation is missing"
     * rather than "the context was not wrapped".
     *
     * It also cannot be AppCompatDelegate.setApplicationLocales: below API 33
     * that has nothing to say to a Service, and minSdk here is 30.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "retro_ai_channel"

        private const val WATCHDOG_INTERVAL_MS = 500L

        /** How often the foreground app is polled. */
        private const val FOREGROUND_POLL_MS = 400L

        /**
         * Auto-detect retries. Five attempts 4 s apart covers ~20 s, which is
         * well past a GBA BIOS boot; the gap clears detectSourceWindow's own
         * 3 s poll so two runs cannot be in flight at once. Each attempt hides
         * the floating ball for the measurement, so the ball blinking is the
         * visible cost - and it only happens while detection is failing, which
         * is when the picture is in the wrong place anyway.
         */
        private const val AUTO_DETECT_ATTEMPTS = 5
        private const val AUTO_DETECT_RETRY_MS = 4000L

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

        /** Start with the libretro shim as the frame source, not the screen. */
        const val EXTRA_SHIM_MODE = "extra_shim_mode"
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
    private var shimSource: ShimFrameSource? = null

    /**
     * Whichever source is live. The watchdog, the pause/resume path and the GL
     * teardown all go through this and must not know which one it is.
     */
    private val frameSource: FrameSource?
        get() = shimSource ?: captureBridge

    /**
     * Shim mode: frames come from inside RetroArch, not from the screen.
     *
     * Everything the capture architecture needed in order to keep the sampled
     * region and the painted region apart stops applying (AGENT.md §1), so
     * this flag turns off the viewport rewrite, the viewport detection, the
     * integer snapping and the capture-mode probe in one place rather than
     * leaving each of them to work out that it has nothing to do.
     */
    private var shimMode = false
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
            /*
             * A core that draws on the GPU hands libretro a sentinel instead of
             * pixels, so there is nothing for the shim to send and no amount of
             * waiting will produce one. Say that plainly and stop, rather than
             * letting the stall detector arrive four seconds later and blame
             * screen capture - which is not running at all in this mode.
             *
             * This cannot fall back on its own: capture needs the projection
             * consent dialog, and a Service has nowhere to show it. So the job
             * here is to tell the player exactly which button to press instead.
             */
            if (shimMode && ShimFrameService.hardwareRenderedCore) {
                /*
                 * Ask for screen capture right here, rather than telling
                 * someone to go and find a button.
                 *
                 * The consent dialog needs an Activity and a Service has none -
                 * but it can start one, and this app holds SYSTEM_ALERT_WINDOW,
                 * which is one of the exemptions from the background
                 * activity-start restrictions. So the only part that genuinely
                 * cannot be automatic is the tap on the system dialog.
                 */
                /*
                 * Say so and stop. Do not try to switch modes here.
                 *
                 * Pulling this app to the front mid-game to ask for capture
                 * consent was tried and abandoned. Every failure lived in that
                 * transition: capture starting while this app is what is on
                 * screen, the console preference not reaching the running
                 * service, the last frame frozen because the thread that could
                 * clear it had already been torn down. And the convenience it
                 * bought was small - RetroArch has to be closed and the game
                 * restarted either way, because it reads the new viewport only
                 * when it loads content.
                 *
                 * So: the player closes RetroArch and starts capture mode from
                 * the app, which is the clean state that machinery was built
                 * for - app in front, no game running, nothing to race.
                 */
                Log.w(TAG, "core renders on the GPU - no frames; stopping")
                // With the core's name: the capture route configures RetroArch
                // for whatever console THIS core is, and by the time the player
                // presses the button the static holding it may be gone.
                HardwareCoreNotice.remember(ShimFrameService.coreFile)
                // On the source's own thread, while it still exists: after
                // stopSelf there is nowhere left to run it and the last frame
                // stays on the glass (AGENT.md §10.3b).
                frameSource?.runOnCaptureThread { nativeBridge.nativeClearOverlay() }
                Toast.makeText(
                    this@OverlayService,
                    getString(R.string.toast_shim_hw_core),
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }

            val bridge = frameSource
            // A paused pipeline produces no frames by design - do not let the
            // stall detector interpret that as a broken pipeline.
            if (bridge != null && !bridge.isPaused) {
                val now = SystemClock.elapsedRealtime()
                val firstFrameTimeout = bridge.firstFrameTimeoutMs
                if (firstFrameTimeout > 0L &&
                    bridge.renderedFrames == 0L &&
                    now - bridge.startedAtMs > firstFrameTimeout) {
                    Log.e(TAG, "No frame ever reached the renderer - shutting down.")
                    nativeBridge.nativeClearOverlay()
                    // Two very different causes, so two different messages.
                    // "check your screen recording permission" is actively
                    // misleading in a mode that never asked for one.
                    Toast.makeText(
                        this@OverlayService,
                        getString(
                            if (shimMode) R.string.toast_shim_no_frames
                            else R.string.toast_no_frames
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    stopSelf()
                    return
                }

                // The threshold belongs to the source, not to the watchdog:
                // for a screen mirror this long a silence is a fault, for the
                // shim it is RetroArch's menu being open and the overlay has to
                // get out of the way fast enough to be usable (§4.7).
                val stallMs = bridge.frameStallTimeoutMs
                val idleMs = now - bridge.lastFrameAtMs
                if (idleMs > stallMs && !isOverlayCleared) {
                    if (!shimMode) Log.w(TAG, "No frame for ${idleMs}ms - wiping overlay transparent.")
                    /*
                     * ON the thread that owns the EGL context, not from here.
                     *
                     * This ran inline on the main thread for a long time and
                     * looked fine, because under screen capture it took ten
                     * seconds of silence to reach and almost never did.
                     * ensureEglContextCurrent() fails silently when the context
                     * is current on another thread, the wipe becomes a no-op,
                     * and the last frame stays frozen on the glass -
                     * AGENT.md §10.3b, written down and then not followed here.
                     * The shim asks for this every time a menu opens, which is
                     * what finally made it visible.
                     */
                    if (!bridge.runOnCaptureThread { nativeBridge.nativeClearOverlay() }) {
                        Log.w(TAG, "no frame thread to wipe on - doing it inline")
                        nativeBridge.nativeClearOverlay()
                    }
                    // Hand back touches and opacity while the picture is not
                    // ours: with the shim this is RetroArch's own menu showing
                    // through, and an invisible full-screen overlay that still
                    // swallows taps would make it unusable for anyone not on a
                    // handheld with physical buttons.
                    if (shimMode) setOverlayObscuring(false)
                    isOverlayCleared = true
                } else if (idleMs <= stallMs) {
                    if (isOverlayCleared && shimMode && isRenderingActive) {
                        setOverlayObscuring(true)
                    }
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
    /**
     * Everything that touches RetroArch's config files, in order, on one
     * thread.
     *
     * Order is the point. The snapshot has to be taken before a patch is
     * written, or the snapshot records our own viewport as the user's original.
     * These used to be two threads started from two lifecycle callbacks
     * (onCreate and onStartCommand) with nothing sequencing them.
     *
     * Not the restore: that has to outlive the service, so it keeps its own
     * non-daemon thread.
     */
    private val configExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "RAConfig").apply { isDaemon = true }
    }

    private fun ensureConfigBackup() {
        configExecutor.execute {
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
                // createdSnapshot, not a prefix test on the message: that
                // message is user-facing text and testing it made the branch
                // depend on the display language.
                if (result.ok && result.createdSnapshot) {
                    mainHandler.post {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_backup_done, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // NOT applyConfigOnStart here. onCreate always runs before
                // onStartCommand, so the frame source is not known yet, and
                // this ran unconditionally - which is how direct mode still
                // announced "configured FC, restart RetroArch", from a code
                // path that had already been told not to run. onStartCommand
                // queues it behind this one once it knows it is capture that is
                // starting.
            } catch (e: Exception) {
                Log.e(TAG, "config backup failed", e)
            }
        }
    }

    /**
     * Shrinks RetroArch into its corner, the moment capture starts.
     *
     * This has to happen HERE and not one step later, because RetroArch reads
     * its overrides only when it loads content: the player is about to be told
     * to close it and launch the game again, and that launch is the one chance
     * this write has to take effect.
     *
     * It had no call site at all between 2605148 and this commit. The symptom
     * was the whole capture route quietly not working: RetroArch drew full
     * screen, and the enhancer magnified whatever happened to be in the
     * bottom-right 320x240 of it - on the test device, the copyright line of a
     * PlayStation title screen, blown up 2x in a box in the middle of the
     * picture. Nothing was wrong with the geometry or the capture mode; the
     * emulator had simply never been asked to move.
     */
    private fun applyConfigOnStart(manager: RetroArchConfigManager) {
        /*
         * Shim mode leaves RetroArch's config alone entirely.
         *
         * Every key this writes exists to serve screen capture: the custom
         * viewport shrinks RetroArch into a corner so our output has somewhere
         * to go that is not on top of what we are sampling, and
         * video_shader_enable=false keeps RetroArch's own shaders out of the
         * picture we sample. The shim takes the core's frame before either
         * happens, so RetroArch can go back to drawing normally at whatever
         * size and with whatever shader the player chose - and every key we do
         * not write is one less thing to restore afterwards.
         */
        if (shimMode) {
            Log.i(TAG, "shim mode - leaving RetroArch's config untouched")
            return
        }

        /*
         * The console comes from the core that is actually running. Nothing
         * else is allowed to decide it.
         *
         * The stored "current console" is a leftover from whatever was played
         * last, and using it is not a smaller mistake than not writing at all:
         * it writes a PlayStation viewport into the Game Boy Advance override,
         * where it does nothing for the session that wrote it and then squeezes
         * a GBA into a corner days later, on a platform the player never
         * connected to screen recording. That happened, and it took a restore
         * bug hunt to find.
         *
         * So: no core, or a core that maps to no console -> write NOTHING and
         * say so. A capture session that does not line up is a bad session; a
         * viewport written into the wrong console's config is damage that
         * outlives it.
         */
        val core = ShimFrameService.coreFile ?: HardwareCoreNotice.coreFile()
        val console = core?.let { consoleForCore(it) }
        if (console == null) {
            Log.w(TAG, "not writing RetroArch's viewport: core=$core maps to no console")
            mainHandler.post {
                Toast.makeText(
                    this,
                    getString(R.string.toast_unknown_core, core ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (console != ProfilePreference.currentConsole(this)) {
            Log.i(TAG, "capture: $core is ${console.name}; switching the profile to it")
            ProfilePreference.setConsole(this, console)
            mainHandler.post { floatingBallManager?.reloadProfile() }
        }

        val profile = ProfilePreference.load(this)
        val folders = manager.coreFoldersFor(console)
        if (folders.isEmpty()) {
            Log.w(TAG, "no core config folder for ${console.name}, skipping auto-write")
            mainHandler.post {
                Toast.makeText(
                    this,
                    getString(R.string.toast_no_core_dir, console.label(this)),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val result = manager.applyViewport(
            folders,
            console.nativeWidth * profile.sourceScale,
            console.nativeHeight * profile.sourceScale,
            profile.effectiveBiasX,
            profile.effectiveBiasY,
            profile.disableRaShader
        )
        Log.i(TAG, "capture: wrote the viewport for ${console.name}: ${result.message}")
        // Only when it went wrong. The dialog the player is looking at right
        // now already tells them the one thing they have to do next, and a
        // toast repeating it in other words is how a screen becomes noise.
        if (!result.ok) {
            mainHandler.post {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * True once the emulator has actually been on screen in this capture
     * session. Until then "not in the foreground" means the player is still on
     * their way there - our own settings screen, the consent dialog, the
     * frontend - and is not a session ending.
     */
    private var targetWasInForeground = false

    /**
     * Ends a capture session: wipe, drop the notice, stop.
     *
     * The wipe has to run on the frame source's own thread while that thread
     * still exists - it owns the EGL context, and ensureEglContextCurrent()
     * fails silently anywhere else, which leaves the last frame frozen on the
     * glass over whatever is really on screen (AGENT.md §10.3b, three times
     * now). Everything else - restoring RetroArch's config, tearing down the
     * projection - happens in onDestroy.
     */
    private fun endCaptureSession() {
        frameSource?.runOnCaptureThread { nativeBridge.nativeClearOverlay() }
        // The screen-recording card describes a live situation. This is the end
        // of that situation, so the card goes with it; meeting such a core
        // again is what brings it back.
        HardwareCoreNotice.forget()
        stopSelf()
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

        /*
         * A capture session belongs to one launch of one game, and ends with
         * it.
         *
         * Direct mode is the opposite: the emulator leaving is nothing, the
         * player is checking something and will be back, and tearing the
         * pipeline down would make switching apps cost a restart. So it pauses,
         * which is what this whole method is for.
         *
         * Capture cannot afford the same generosity. The emulator quitting
         * (select+B) leaves a session configured for the console that just
         * ended: RetroArch's viewport override written for it, our sampling
         * rect predicted for its resolution, a projection still mirroring the
         * screen. Launch anything else from the frontend and it resumes on the
         * spot - the reported symptom was the enhanced picture with a small
         * slab of raw RetroArch magnified into the middle of it, because we
         * were still sampling a PlayStation-sized rect out of a Game Boy
         * Advance's full-screen picture.
         *
         * Ending it here is also what puts RetroArch's config back and takes
         * the screen-recording card off the main screen, both of which are
         * only true once the session is over.
         */
        if (!shimMode) {
            if (shouldRender) {
                targetWasInForeground = true
            } else if (targetWasInForeground) {
                Log.i(TAG, "capture: the emulator is gone - ending the session")
                endCaptureSession()
                return
            }
        }

        if (shouldRender == isRenderingActive) return

        isRenderingActive = shouldRender
        Log.i(TAG, if (shouldRender) "Target app in foreground - resuming." else "Target app left - pausing.")

        // Capture that was granted while this app was in front waits here for
        // the emulator to come back. See startCapturePipeline.
        if (shouldRender && captureBridge == null && mediaProjection != null) {
            startCapturePipeline()
        }
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
        if (shouldRender) {
            // Coming back to the emulator is taken as the restart we asked for.
            // Worst case the user only switched away and back, and they see the
            // same scrambled frame they were already told how to fix.
            if (awaitingRaRestart) {
                Log.i(TAG, "target app re-entered - assuming RetroArch restarted")
                awaitingRaRestart = false
                floatingBallManager?.profile?.detectedSourceRect = null
                applyRenderingState()
            }
            scheduleAutoDetect()
            scheduleCaptureModeProbe()
        }
    }

    /**
     * Measures the capture window shortly after the game appears - long enough
     * for the emulator to have drawn a real frame, not a black one.
     */
    private fun scheduleAutoDetect() {
        // Nothing to measure in shim mode: the frame arrives at exactly its
        // native resolution, so the rect this pass exists to recover is
        // already known to the pixel.
        if (shimMode) return
        val manager = floatingBallManager ?: return
        if (manager.profile.detectedSourceRect != null) return
        mainHandler.removeCallbacks(autoDetectRunnable)
        autoDetectAttempts = 0
        mainHandler.postDelayed(autoDetectRunnable, 1500L)
    }

    /**
     * Retried rather than fired once, because 1.5 s is not long enough for
     * every console and the single shot failed silently.
     *
     * A GBA is still playing the BIOS boot logo at 1.5 s: a black field with a
     * small sliding wordmark. Thresholded, the largest non-black region IS that
     * wordmark, which the aspect gate correctly refuses (10.2) - and with no
     * retry the capture window was then never measured for the rest of the
     * session, so the enhanced picture sat wherever the PREDICTED source rect
     * put it. Every other console loads straight into a full-frame picture and
     * hit on the first try, which is why this only ever showed up on GBA.
     *
     * Refusing is right; giving up after one refusal is not.
     */
    private var autoDetectAttempts = 0

    // An object rather than a lambda: the body reposts itself, and a lambda
    // assigned to a val cannot refer to that val while initialising it.
    private val autoDetectRunnable = object : Runnable {
        override fun run() {
            val manager = floatingBallManager ?: return
            if (!isRenderingActive || manager.profile.detectedSourceRect != null) return
            autoDetectAttempts++
            Log.i(TAG, "auto-detecting capture window (attempt $autoDetectAttempts)")
            manager.detectSourceWindow(silent = true)
            // Spaced past detectSourceWindow's own 3 s poll so attempts cannot
            // overlap - two detections in flight would blank each other's frames.
            if (autoDetectAttempts < AUTO_DETECT_ATTEMPTS) {
                mainHandler.postDelayed(this, AUTO_DETECT_RETRY_MS)
            } else {
                Log.w(TAG, "auto-detect gave up after $autoDetectAttempts attempts")
            }
        }
    }

    /**
     * Measures whether our overlay is inside the capture, once per session.
     *
     * RetroArch's config had to be written before RetroArch even started, from
     * a prediction; this is where that prediction gets checked. Only a
     * mismatch costs the user anything, and only once - the answer is
     * remembered, so the next session predicts correctly.
     */
    private var captureModeProbeRequested = false

    /**
     * Set when the measured capture mode contradicts the prediction the
     * RetroArch config was written from. Suppresses painting until the user has
     * restarted the emulator, because until then it is drawing to the old
     * layout and every sample lands somewhere meaningless.
     */
    private var awaitingRaRestart = false

    /** On-screen banner shown for the whole of [awaitingRaRestart]. */
    private var restartNoticeView: View? = null

    /**
     * Says out loud why the picture went away.
     *
     * A blank overlay plus a Toast that lasts a few seconds reads as a crash -
     * the first reaction to it here was "完蛋，黑屏了". The message has to stay
     * up for as long as the condition does, so it is obvious this is a state
     * waiting on the user rather than a failure.
     *
     * Only while the emulator is in front: over the launcher it would just be
     * a banner covering someone else's app.
     */
    private fun showRestartNotice(show: Boolean) {
        if (!show) {
            val view = restartNoticeView ?: return
            restartNoticeView = null
            // Unconditional. Gating this on isAttachedToWindow orphaned the
            // banner: that flag is still false between addView and the first
            // traversal, so a removal landing in that window skipped the
            // removeView while still clearing the reference - and from then on
            // every later call short-circuited on a null reference while the
            // view sat on screen, outliving even the service.
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "restart notice removal failed", e)
            }
            return
        }

        if (restartNoticeView != null) return

        val pad = (resources.displayMetrics.density * 20).toInt()
        val view = TextView(this).apply {
            setText(R.string.banner_capture_mode_changed)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xE6000000.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        // The reference is only kept when the view really went up, so a failed
        // add can never leave us believing a banner exists - or, worse, believing
        // one does not while it is on screen.
        val wm = windowManager ?: return
        try {
            wm.addView(view, params)
            restartNoticeView = view
        } catch (e: Exception) {
            Log.w(TAG, "restart notice could not be shown", e)
        }
    }

    private fun scheduleCaptureModeProbe() {
        // The probe asks whether our own overlay lands in the captured frame.
        // Nothing is captured from the screen in shim mode, so the question has
        // no meaning and the marker it paints would only flash on screen.
        if (shimMode) return
        if (captureModeProbeRequested) return
        captureModeProbeRequested = true
        // After the auto-detect window, so the two readback users never overlap.
        mainHandler.postDelayed({
            if (!isRenderingActive) {
                captureModeProbeRequested = false
                return@postDelayed
            }
            Log.i(TAG, "requesting capture-mode probe")
            nativeBridge.nativeRequestCaptureModeProbe()
            pollCaptureMode(0)
        }, 3000L)
    }

    private fun pollCaptureMode(attempt: Int) {
        val result = nativeBridge.nativeGetCaptureMode()
        if (result < 0) {
            if (attempt > 40) {
                Log.w(TAG, "capture-mode probe did not settle; keeping the predicted mode")
                return
            }
            mainHandler.postDelayed({ pollCaptureMode(attempt + 1) }, 100L)
            return
        }

        val measured = if (result == 1) CaptureMode.WHOLE_SCREEN else CaptureMode.SINGLE_APP
        Log.i(TAG, "CAPTURE MODE = $measured")
        ProfilePreference.setCaptureMode(this, measured)

        val manager = floatingBallManager ?: return
        if (manager.profile.captureMode == measured) return

        Log.i(TAG, "capture mode differs from the prediction - re-applying geometry and config")
        // Stay blank until RetroArch has been restarted. It is still drawing
        // where the OLD mode asked it to, so anything painted now is sampled
        // from the wrong place - a scrambled picture on top of a working game.
        // The rewritten config only takes effect when content is next loaded.
        awaitingRaRestart = true
        manager.applyCaptureMode(measured)
        applyRenderingState()
        // The measured window belongs to the old layout, and RetroArch is about
        // to be told to draw somewhere else entirely.
        pushGeometry()
        rewriteRetroArchConfigForCaptureMode()
    }

    /**
     * The capture window moves between the corner and the centre with the mode,
     * so RetroArch has to be told again - and it only reads its overrides at
     * content load, hence the prompt.
     */
    private fun rewriteRetroArchConfigForCaptureMode() {
        if (shimMode) return
        Thread {
            try {
                val profile = floatingBallManager?.profile ?: return@Thread
                val manager = RetroArchConfigManager(this)
                val folders = manager.coreFoldersFor(profile.console)
                if (folders.isEmpty()) return@Thread
                val result = manager.applyViewport(
                    folders,
                    profile.console.nativeWidth * profile.sourceScale,
                    profile.console.nativeHeight * profile.sourceScale,
                    profile.effectiveBiasX,
                    profile.effectiveBiasY,
                    profile.disableRaShader
                )
                Log.i(TAG, "capture-mode config rewrite: ${result.message}")
                if (result.ok) {
                    mainHandler.post {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_capture_mode_mismatch),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture-mode config rewrite failed", e)
            }
        }.apply { name = "CaptureModeConfig"; isDaemon = true }.start()
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
        showRestartNotice(isRenderingActive && awaitingRaRestart)
        if (isRenderingActive && !awaitingRaRestart) {
            setOverlayObscuring(true)
            nativeBridge.nativeSetRenderPaused(false)
            frameSource?.resumeCapture()
        } else {
            frameSource?.pauseCapture()
            // The wipe runs ON the capture thread, where the EGL context
            // already is. Doing it from here instead means handing the context
            // across threads and back on every app switch, and after that
            // round trip the renderer kept reporting healthy frames that never
            // reached the screen.
            val bridge = frameSource
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

        if (intent?.getBooleanExtra(EXTRA_SHIM_MODE, false) == true) {
            // No MediaProjection is requested, granted, or needed. The frames
            // come from inside RetroArch.
            shimMode = true
            Log.i(TAG, "Starting in shim frame-source mode - no screen capture.")
            startCapturePipeline()
            return START_NOT_STICKY
        }

        if (intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            if (resultCode != 0 && data != null) {
                // Screen capture arriving while the direct source is live is
                // the GPU-core fallback being taken. Tear the old source down
                // first: startCapturePipeline refuses to start while one
                // exists, so without this the service would keep the direct
                // source that has no frames to give and quietly ignore the
                // projection the player just granted.
                if (shimSource != null) {
                    Log.i(TAG, "switching from the direct source to screen capture")
                    frameSource?.pauseCapture()
                    /*
                     * Wipe the last frame WHILE the source thread still exists.
                     *
                     * That thread owns the EGL context, and clearing has to
                     * happen on it (AGENT.md §10.3b). Tearing the source down
                     * first leaves nowhere to run, the clear silently does
                     * nothing, and the previous console's last frame stays
                     * frozen on the glass over whatever is really on screen -
                     * which here is this app's own settings page.
                     */
                    frameSource?.runOnCaptureThread { nativeBridge.nativeClearOverlay() }
                    frameSource?.detachEglContext()
                    stopFrameSource()
                }
                // The console was preselected from the core name a moment ago,
                // in the activity. This is the copy that has to be told.
                floatingBallManager?.reloadProfile()
                shimMode = false
                /*
                 * None of the capture state survives the switch.
                 *
                 * The detected window belongs to whatever console was last
                 * measured under capture, and the capture mode to whatever was
                 * last probed - both from a different session, possibly a
                 * different console. Carrying either across is how a
                 * PlayStation ends up sampled through a window measured for a
                 * Super Famicom, with the hole landing in the middle of the
                 * picture instead of in a corner.
                 */
                floatingBallManager?.clearDetectedWindow()
                /*
                 * And start painting nothing.
                 *
                 * Screen capture mirrors the whole screen, and at this instant
                 * the screen is our own settings page - the consent dialog just
                 * closed on top of it. Carrying the previous session's "we are
                 * rendering" state across meant the first captured frames were
                 * of this app, blown up over itself. The foreground poll turns
                 * it back on when the emulator returns, which is exactly when
                 * the player restarts the game as instructed.
                 */
                isRenderingActive = false
                applyRenderingState()
                /*
                 * Tell RetroArch where to draw, now.
                 *
                 * Queued behind the snapshot on the config thread, so the
                 * backup is always of the file as the player had it. And before
                 * the pipeline rather than after: the player is about to be
                 * shown "close RetroArch and launch the game again", and that
                 * relaunch is the only moment RetroArch will read this.
                 */
                configExecutor.execute { applyConfigOnStart(RetroArchConfigManager(this)) }
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
        // FLAG_NOT_TOUCHABLE comes off while painting, and that is what buys
        // full opacity: the system clamps a NOT_TOUCHABLE overlay to 0.8 so
        // touches can pass, and the missing 20% let the small native picture
        // ghost through the enhanced one wherever the two overlap.
        //
        // Nothing is lost by dropping it. At 0.8 this device already withholds
        // touches from other apps, so while the game is up they were never
        // getting through anyway - and it is a handheld with physical controls.
        // The flag and the low alpha both come back the moment we stop
        // painting, which is when reaching the launcher actually matters.
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.flags = if (obscuring) {
            base
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

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
                val notTouchable =
                    params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
                Log.i(TAG, "Overlay alpha -> $wanted notTouchable=$notTouchable " +
                        "(obscuring=$obscuring)")
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
                Log.d(TAG, "Render profile updated: ${profile.console.name} ${profile.getOutputScale(screenWidth, screenHeight)}x")
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

        if (shimMode) {
            /*
             * The whole of the capture geometry problem disappears here.
             *
             * Under MediaProjection the source rect had to be MEASURED out of a
             * screenshot and snapped to an exact integer multiple of the native
             * resolution, because two pixels of error accumulate into a
             * one-texel drift by the bottom of the picture and every engine
             * goes soft (AGENT.md §10.1). A shim frame IS the native
             * resolution: the source rect is the whole buffer, exactly, with
             * nothing to detect and nothing to snap.
             *
             * protectSource is false because the reason it existed is gone. It
             * punched a hole in our own output so the next captured frame would
             * not contain the previous one - self-feedback. Nothing here is
             * captured from the screen, so there is no loop to break, and the
             * output covers the whole display.
             */
            val out = outputRectForShim()
            nativeBridge.nativeSetGeometry(
                0, 0, shimFrameWidth, shimFrameHeight,
                out.left, out.top, out.width(), out.height(),
                false,
                false,
                true
            )
            return
        }

        val src = profile.getSourceRect(screenWidth, screenHeight)
        val out = profile.getOutputRect(screenWidth, screenHeight)
        nativeBridge.nativeSetGeometry(
            src.left, src.top, src.width(), src.height(),
            out.left, out.top, out.width(), out.height(),
            profile.showSourceGuide,
            profile.captureMode == CaptureMode.WHOLE_SCREEN,
            false
        )
    }

    private val shimFrameWidth: Int
        get() = ShimFrameService.lastWidth.takeIf { it > 0 }
            ?: floatingBallManager?.profile?.console?.nativeWidth ?: 240
    private val shimFrameHeight: Int
        get() = ShimFrameService.lastHeight.takeIf { it > 0 }
            ?: floatingBallManager?.profile?.console?.nativeHeight ?: 160

    /**
     * The largest whole multiple of the native resolution that fits the screen,
     * centred.
     *
     * Integer, for the reason the capture path already records: a fractional
     * scale means a game pixel does not land on a whole number of screen
     * pixels, every pixel boundary gets interpolated, and the picture is soft.
     * Centred, because with nothing to avoid there is nothing to bias towards.
     */
    private fun outputRectForShim(): android.graphics.Rect {
        val nw = shimFrameWidth
        val nh = shimFrameHeight
        val k = maxOf(1, minOf(screenWidth / nw, screenHeight / nh))
        val w = nw * k
        val h = nh * k
        val x = (screenWidth - w) / 2
        val y = (screenHeight - h) / 2
        return android.graphics.Rect(x, y, x + w, y + h)
    }

    private fun startCapturePipeline() {
        if (frameSource != null || !isSurfaceReady) return

        if (shimMode) {
            val source = ShimFrameSource(nativeBridge) { w, h ->
                // Arrives on the frame thread; everything it touches - the
                // profile, the renderer config, the geometry - belongs to the
                // main thread.
                mainHandler.post {
                    HardwareCoreNotice.forget()
                    val adopted = floatingBallManager
                        ?.adoptNativeSize(w, h, ShimFrameService.coreFile)
                    if (adopted == true) pushGeometry()
                }
            }
            source.start()
            shimSource = source
            pushGeometry()
            applyRenderingState()
            Log.i(TAG, "Pipeline started on the libretro shim frame source.")
            return
        }

        val mp = mediaProjection ?: return

        val bridge = CaptureBridge(
            mp,
            screenWidth,
            screenHeight,
            screenDensityDpi,
            nativeBridge,
            onProjectionStopped = {
                // Restore RIGHT HERE, not just from onDestroy. Quitting the
                // emulator ends the projection, and a mediaProjection
                // foreground service that outlives its projection gets killed
                // by the system - onDestroy never runs, and RetroArch is left
                // with our viewport override, drawing into a corner of its own
                // window with nothing else on screen. Under single-app capture
                // that is not an edge case: it happens every time the user
                // finishes playing.
                restoreConfigOnStop()
                mainHandler.post { stopSelf() }
            }
        )
        if (!bridge.startCapture()) {
            Toast.makeText(this, R.string.toast_capture_start_failed, Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, R.string.toast_render_init_failed, Toast.LENGTH_LONG).show()
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
        val bridge = frameSource
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
            Toast.makeText(this, R.string.toast_rotate_render_failed, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        isSurfaceReady = true

        // The renderer is a new object: the ESPCN weights and every render
        // setting live in it and have to be pushed again.
        floatingBallManager?.pushAllSettings()
        pushGeometry()

        // Only the screen mirror is sized in screen pixels. A shim frame is
        // its own native resolution whatever the panel is doing, so rotation
        // changes nothing about the source - pushGeometry above has already
        // recentred the OUTPUT, which is the only part that moved.
        val mirror = captureBridge
        if (mirror != null && !mirror.resizeTo(width, height, screenDensityDpi)) {
            Log.e(TAG, "capture resize failed after rotation - stopping.")
            Toast.makeText(this, R.string.toast_rotate_capture_failed, Toast.LENGTH_LONG).show()
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
            frameSource?.pauseCapture()
            frameSource?.detachEglContext()
            nativeBridge.nativeRelease()
            return
        }

        stopFrameSource()
        nativeBridge.nativeRelease()
    }

    /** Tears down whichever source is live. Both are always cleared: only one
     *  is ever set, but leaving a stale reference behind would let
     *  startCapturePipeline() decide a pipeline is already running. */
    private fun stopFrameSource() {
        shimSource?.stopCapture()
        shimSource = null
        captureBridge?.stopCapture()
        captureBridge = null
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
                "RetroAI-Scaler Service",
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
            !hasUsageAccess -> getString(R.string.notif_no_usage_access)
            targetPackage == null -> getString(R.string.notif_no_target)
            isRenderingActive -> getString(R.string.notif_rendering, targetLabel)
            else -> getString(R.string.notif_idle, targetLabel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_preferences, getString(R.string.notif_action_menu), openMenuPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopPendingIntent)
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
    /**
     * Guards against the restore running twice: it is now kicked off the moment
     * the projection ends as well as from onDestroy, and those can overlap.
     */
    private val configRestoreStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun restoreConfigOnStop() {
        if (!configRestoreStarted.compareAndSet(false, true)) return
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
        showRestartNotice(false)

        // Order matters: stop producing frames, then wipe the overlay, then
        // tear down GL, then finally detach the window.
        stopFrameSource()
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
