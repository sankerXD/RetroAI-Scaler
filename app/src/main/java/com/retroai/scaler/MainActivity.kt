package com.retroai.scaler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import java.io.File
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.retroai.scaler.detector.ForegroundAppMonitor
import com.retroai.scaler.detector.CaptureModePreference
import com.retroai.scaler.detector.PegasusConfigManager
import com.retroai.scaler.detector.RetroArchBackupManager
import com.retroai.scaler.detector.RetroArchConfigManager
import com.retroai.scaler.detector.TargetAppPreference
import com.retroai.scaler.ui.AppLanguage
import com.retroai.scaler.ui.ConsoleType
import com.retroai.scaler.ui.consoleForCore
import com.retroai.scaler.ui.LocaleHelper
import com.retroai.scaler.ui.ProfilePreference
import com.retroai.scaler.service.OverlayService
import com.retroai.scaler.shim.ShimFrameService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

    }

    private lateinit var tvStatusText: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var btnToggleService: Button
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantUsage: Button
    private lateinit var btnGrantStorage: Button
    private lateinit var tvTargetApp: TextView
    private lateinit var tvConsole: TextView
    private lateinit var tvOutputPlan: TextView
    private lateinit var btnPickConsole: Button
    private lateinit var btnPickTargetApp: Button
    private lateinit var btnPickLanguage: Button

    // Shim frame link (gate 3). Developer tooling until gate 4 makes it the
    // ordinary frame source.
    private lateinit var btnShimProbe: Button
    private lateinit var btnShimDump: Button
    private lateinit var btnShimStart: Button

    private lateinit var btnShimCapture: Button
    private lateinit var cardPermissions: View
    private lateinit var cardSetup: View

    /** Filled in by syncLaunchFiles, read by the status line. */
    @Volatile private var shimCoreCount = 0
    @Volatile private var shimConvertedCount = 0
    private var lastPreselectedCore: String? = null
    private lateinit var tvShimProbe: TextView

    private val foregroundMonitor by lazy { ForegroundAppMonitor(this) }

    private val isServiceRunning: Boolean
        get() = OverlayService.isRunning

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
            showRestartGameNotice()
        } else {
            Toast.makeText(this, R.string.toast_no_capture_permission, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Applies the stored language before any resource is resolved.
     *
     * Following the SYSTEM language needs nothing here - Android already picks
     * values-zh/ or falls back to values/. This exists for the override, and
     * has to be attachBaseContext rather than anything later: by onCreate the
     * theme and the layout have already been resolved against the old locale.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkHardwareCapabilities()
        updatePermissionButtons()
        healLeftoverRetroArchConfig()
        syncLaunchFiles()
    }

    /**
     * Puts RetroArch's config back if a previous session did not get to.
     *
     * The service restores on stop, but it cannot always run: ending the
     * projection - which is what quitting the emulator does - lets the system
     * kill a mediaProjection foreground service outright, and nothing runs
     * after that. RetroArch is then left drawing its viewport into a corner,
     * which reads as "RetroArch is broken".
     *
     * Opening this app is exactly what someone does next, so heal here too. It
     * is only ever a no-op or a repair: the restore touches nothing but files
     * carrying our own marker line.
     */
    private fun healLeftoverRetroArchConfig() {
        Thread {
            try {
                val manager = RetroArchConfigManager(applicationContext)
                if (!manager.hasModifiedFiles()) return@Thread
                val result = manager.restoreFromLatestBackup()
                Log.i(TAG, "healed leftover RetroArch config: ${result.message}")
                runOnUiThread {
                    // A failure gets said out loud too. This heal running,
                    // reporting nothing, and leaving the viewport in place is
                    // precisely how RetroArch stayed stuck in its corner while
                    // every screen in this app claimed to be clean.
                    Toast.makeText(
                        this,
                        if (result.ok) getString(R.string.toast_restored_stale_config)
                        else result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.w(TAG, "healing leftover config failed", e)
            }
        }.apply { name = "ConfigHeal"; isDaemon = true }.start()
    }

    private fun initViews() {
        tvStatusText = findViewById(R.id.tvStatusText)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantUsage = findViewById(R.id.btnGrantUsage)
        btnGrantStorage = findViewById(R.id.btnGrantStorage)

        btnGrantStorage.setOnClickListener {
            if (!RetroArchConfigManager.hasAllFilesAccess()) {
                Toast.makeText(this, R.string.toast_open_all_files_switch, Toast.LENGTH_LONG).show()
                startActivity(RetroArchConfigManager.allFilesAccessIntent(this))
            } else {
                Toast.makeText(this, R.string.toast_all_files_ready, Toast.LENGTH_SHORT).show()
            }
        }
        tvTargetApp = findViewById(R.id.tvTargetApp)
        btnPickTargetApp = findViewById(R.id.btnPickTargetApp)

        btnGrantUsage.setOnClickListener {
            if (!ForegroundAppMonitor.hasUsageAccess(this)) {
                Toast.makeText(this, R.string.toast_find_in_list, Toast.LENGTH_LONG).show()
                startActivity(ForegroundAppMonitor.usageAccessSettingsIntent())
            } else {
                Toast.makeText(this, R.string.toast_usage_ready, Toast.LENGTH_SHORT).show()
            }
        }

        btnPickTargetApp.setOnClickListener { showTargetAppPicker() }

        btnPickLanguage = findViewById(R.id.btnPickLanguage)
        btnPickLanguage.setOnClickListener { showLanguagePicker() }
        updateLanguageLabel()

        tvConsole = findViewById(R.id.tvConsole)
        tvOutputPlan = findViewById(R.id.tvOutputPlan)
        btnPickConsole = findViewById(R.id.btnPickConsole)
        btnPickConsole.setOnClickListener { showConsolePicker() }

        btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.toast_overlay_ready, Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopOverlayService()
            } else {
                requestStartPipeline()
            }
        }

        // Hidden by default: direct mode reads the resolution out of the
        // frames. It comes back when screen capture is on the table, because
        // that route computes RetroArch's viewport from the declared console
        // and cannot work without one - see updateShimProbeUi.
        // Never shown. The console is worked out from the core name, for the
        // capture route as well - see preselectConsoleFromCore - so a picker
        // would only be one more thing on screen that nobody needs to touch.
        findViewById<View>(R.id.cardConsole).visibility = View.GONE
        cardPermissions = findViewById(R.id.cardPermissions)
        cardSetup = findViewById(R.id.cardSetup)

        btnShimProbe = findViewById(R.id.btnShimProbe)
        btnShimDump = findViewById(R.id.btnShimDump)
        btnShimStart = findViewById(R.id.btnShimStart)
        tvShimProbe = findViewById(R.id.tvShimProbe)
        btnShimProbe.setOnClickListener { showSetupGuide(coreOnly = true) }
        btnShimStart.setOnClickListener { applyLaunchFiles() }
        btnShimDump.setOnClickListener { confirmRestoreLaunchFiles() }
        btnShimCapture = findViewById(R.id.btnShimCapture)
        btnShimCapture.setOnClickListener { requestStartCapturePipeline() }
    }

    /** The service can stop itself while this Activity stays resumed (floating
     *  menu, notification, watchdog), so poll instead of relying on onResume. */
    private val stateRefreshRunnable = object : Runnable {
        override fun run() {
            updateServiceStateUi()
            updateShimProbeUi()
            btnToggleService.postDelayed(this, 1000)
        }
    }

    /**
     * The one-time setup, or just its RetroArch half.
     *
     * [coreOnly] is the "1 · Install the core" button, which is the same
     * instructions minus the step that comes after them - there is no reason
     * for the two to drift, and someone re-reading the first half should see
     * the same words they saw the first time.
     */
    private fun showSetupGuide(coreOnly: Boolean = false) {
        val target = File(
            Environment.getExternalStorageDirectory(),
            "RetroAIScaler/cores/vbam_shim_libretro_android.so"
        )
        // Written out where the instructions point, so they are true by the
        // time anyone follows them.
        Thread {
            runCatching {
                target.parentFile?.mkdirs()
                assets.open("shim/vbam_shim_libretro_android.so").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { Log.w(TAG, "could not stage the shim core", it) }
        }.apply { isDaemon = true }.start()

        val retroArch = getString(R.string.shim_guide_retroarch, target.absolutePath)
        AlertDialog.Builder(this)
            .setTitle(if (coreOnly) R.string.shim_guide_core_title else R.string.shim_guide_title)
            .setMessage(
                if (coreOnly) retroArch
                else retroArch + "\n\n" + getString(R.string.shim_guide_launcher)
            )
            .setPositiveButton(R.string.shim_guide_start) { _, _ -> openRetroArch() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Opens the emulator, because the next three steps are all inside it.
     *
     * Whichever app is the enhancement target, rather than a hardcoded package:
     * the same person may be running a differently-packaged RetroArch, and it
     * is already the one thing this app is pointed at.
     */
    private fun openRetroArch() {
        val pkg = TargetAppPreference.get(this) ?: foregroundMonitor.detectRetroArch()
        val intent = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            Toast.makeText(this, R.string.target_app_not_found, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(intent)
    }

    /**
     * Says that the frontend has to be reloaded, at the moment it becomes true.
     *
     * The frontend most people here run is a fork of Pegasus that replaced
     * "rescan at every startup" with a static scan, to save five seconds of
     * startup. Nothing we write under Roms takes effect until it is told to
     * look again, and restarting it is not enough - the scan is persisted.
     *
     * Without this the app would quietly rewrite launch entries, report every
     * console as set up, and the player would launch a game and get no
     * enhancement at all with nothing anywhere saying why. That exact failure
     * cost a day of debugging on this side; it must not be shipped pointing at
     * the player.
     *
     * Shown when something actually changed, which after the first run is
     * never - so this is twice in the life of an install, not a nag.
     */
    private fun showReloadNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reload_notice_title)
            .setMessage(getString(R.string.reload_notice_body))
            .setPositiveButton(R.string.shim_guide_ok, null)
            .show()
    }

    /**
     * Picks the console from the core name, for the capture route.
     *
     * The shim connects and reports which core it is proxying even when that
     * core renders on the GPU and sends no frames at all - so the console is
     * known without a single pixel arriving, and the player does not have to
     * work out that swanstation means PlayStation.
     *
     * Only ever a preselection: the picker is right there, and a core that maps
     * to nothing leaves whatever was chosen before.
     */
    private fun preselectConsoleFromCore() {
        // The static dies with the process and this button can be pressed much
        // later, so the name is persisted alongside the flag that put the
        // button on screen in the first place.
        val core = ShimFrameService.coreFile
            ?: CaptureModePreference.hardwareCoreFile(this)
            ?: return
        if (core == lastPreselectedCore) return
        lastPreselectedCore = core
        val console = consoleForCore(core) ?: return
        if (console == ProfilePreference.currentConsole(this)) return
        ProfilePreference.setConsole(this, console)
        Log.i(TAG, "capture fallback: $core is ${console.name}, preselected it")
        updateConsoleLabel()
    }

    private fun confirmRestoreLaunchFiles() {
        AlertDialog.Builder(this)
            .setTitle(R.string.shim_restore)
            .setMessage(getString(R.string.shim_guide_body_restore))
            .setPositiveButton(R.string.btn_confirm) { _, _ -> restoreLaunchFiles() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** The link runs on its own threads, so the only way this Activity learns
     *  anything about it is to look. */
    private fun updateShimProbeUi() {
        // Only offered when it is the answer to something: a core that draws
        // on the GPU is the one case direct mode cannot serve.
        /*
         * A GPU-rendering core is the one case direct mode cannot serve, and
         * the only case where any of this needs to be visible.
         *
         * The console card comes back with the button. Screen capture computes
         * RetroArch's viewport from the declared console, so it cannot work
         * without one - and hiding the picker for direct mode quietly took
         * away the only way to configure the route it was falling back to. The
         * symptom was a PlayStation drawn to the previous console's dimensions,
         * with the capture window landing in the middle of the picture.
         */
        // Remembered rather than live: the service that saw the GPU core has
        // stopped by the time anyone gets here to press this.
        val needsCapture = CaptureModePreference.sawHardwareCore(this)
        btnShimCapture.visibility = if (needsCapture) View.VISIBLE else View.GONE
        if (needsCapture) preselectConsoleFromCore()

        /*
         * Setup is a thing you do once, so it stops being a thing you look at.
         *
         * Everything on this screen was a control the whole time, which read as
         * a box of switches rather than something to play with. Once the
         * launcher is pointed at the shim there is nothing here to press again
         * - only a line saying so, and the way back out.
         */
        val done = shimConvertedCount > 0
        btnShimProbe.visibility = if (done) View.GONE else View.VISIBLE
        btnShimStart.visibility = if (done) View.GONE else View.VISIBLE

        val converted = shimConvertedCount
        val total = shimCoreCount
        btnShimDump.isEnabled = converted > 0
        tvShimProbe.text = when {
            total == 0 -> getString(R.string.shim_link_idle)
            converted == 0 -> getString(R.string.shim_status_none)
            else -> getString(R.string.shim_status, converted, total)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
        btnToggleService.removeCallbacks(stateRefreshRunnable)
        btnToggleService.post(stateRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        btnToggleService.removeCallbacks(stateRefreshRunnable)
    }

    private fun updatePermissionButtons() {
        val hasOverlay = Settings.canDrawOverlays(this)
        btnGrantOverlay.setText(if (hasOverlay) R.string.btn_granted else R.string.btn_grant)
        btnGrantOverlay.isEnabled = !hasOverlay

        val hasUsage = ForegroundAppMonitor.hasUsageAccess(this)
        btnGrantUsage.setText(if (hasUsage) R.string.btn_granted else R.string.btn_grant)
        btnGrantUsage.isEnabled = !hasUsage

        val hasStorage = RetroArchConfigManager.hasAllFilesAccess()
        btnGrantStorage.setText(if (hasStorage) R.string.btn_granted else R.string.btn_grant)
        btnGrantStorage.isEnabled = !hasStorage

        /*
         * Once all three are granted there is nothing to grant, and three rows
         * of "Granted ✓" are just three rows. They come back the moment one is
         * revoked, which is the only time they say anything.
         */
        cardPermissions.visibility =
            if (hasOverlay && hasUsage && hasStorage) View.GONE else View.VISIBLE

        updateTargetAppLabel()
        updateConsoleLabel()
    }

    /**
     * The platform is chosen here rather than only in the floating menu: the
     * service writes RetroArch's config the moment enhancement starts, and it
     * needs to know which core to configure before any menu has been opened.
     */
    private fun updateLanguageLabel() {
        btnPickLanguage.text = LocaleHelper.labelOf(this, LocaleHelper.stored(this))
    }

    /**
     * Recreates the Activity so the new language is applied through
     * attachBaseContext.
     *
     * The running service is NOT restarted: the floating menu inflates its
     * views once when the service starts, so its strings are fixed for that
     * session. Tearing the pipeline down to change a label would cost the user
     * their capture window and their RetroArch config write, which is a far
     * worse trade than one line of text - so say so instead of doing it.
     */
    private fun showLanguagePicker() {
        val options = AppLanguage.values()
        val labels = options.map { LocaleHelper.labelOf(this, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_language_title)
            .setItems(labels) { _, which ->
                if (options[which] == LocaleHelper.stored(this)) return@setItems
                LocaleHelper.store(this, options[which])
                if (isServiceRunning) {
                    Toast.makeText(
                        this,
                        R.string.toast_language_restart_service,
                        Toast.LENGTH_LONG
                    ).show()
                }
                recreate()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showConsolePicker() {
        // Switching while enhancement runs does not work and never did, which
        // was not visible from here.
        //
        // The console is read once when the service starts: OverlayService
        // loads the profile in applyConfigOnStart, and FloatingBallManager
        // snapshots it in its constructor. Nothing notifies a running service
        // that this key changed. So the renderer keeps inferring at the old
        // console's native resolution, RetroArch keeps the viewport written for
        // the old console - which is what showed up as "RA comes up black
        // after switching" - and the floating menu keeps offering the old
        // console's settings.
        //
        // Rather than build half a hot-switch, refuse: enhancement has to stop,
        // and stopping it is also what restores RetroArch's config. Note that
        // force-stopping the app instead KILLS that restore mid-way (it runs on
        // a non-daemon thread at service stop, AGENT.md 2), which leaves the
        // previous console's viewport in place and is the other half of the
        // same black screen.
        if (isServiceRunning) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_stop_first_title)
                .setMessage(R.string.dialog_stop_first_message)
                .setPositiveButton(R.string.dialog_stop_and_pick) { _, _ ->
                    stopOverlayService()
                    updateServiceStateUi()
                    showConsoleList()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
            return
        }
        showConsoleList()
    }

    private fun showConsoleList() {
        val consoles = ConsoleType.values()
        val labels = consoles
            .map { getString(R.string.console_picker_item, it.label(this), it.nativeWidth, it.nativeHeight) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_console_title)
            .setItems(labels) { _, which ->
                // Only the console key changes; that console's own saved
                // settings are then loaded as-is.
                ProfilePreference.setConsole(this, consoles[which])
                updateConsoleLabel()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun updateConsoleLabel() {
        val profile = ProfilePreference.load(this)
        tvConsole.text = getString(
            R.string.console_picker_item,
            profile.console.label(this), profile.console.nativeWidth, profile.console.nativeHeight
        )

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val out = profile.getOutputRect(metrics.widthPixels, metrics.heightPixels)
        val k = profile.getOutputScale(metrics.widthPixels, metrics.heightPixels)
        val src = profile.getPlannedSourceRect(metrics.widthPixels, metrics.heightPixels)
        tvOutputPlan.text = getString(
            R.string.console_plan,
            src.width(), src.height(), profile.sourceCorner.label(this),
            out.width(), out.height(), k
        )
    }

    private fun updateTargetAppLabel() {
        val stored = TargetAppPreference.get(this)
        val detected = stored ?: foregroundMonitor.detectRetroArch()
        tvTargetApp.text = if (detected == null) {
            getString(R.string.target_app_not_found)
        } else {
            val suffix = if (stored == null) getString(R.string.target_app_auto_suffix) else ""
            getString(R.string.target_app_label, foregroundMonitor.labelFor(detected), suffix)
        }
    }

    /**
     * The overlay follows exactly one app. Defaults to RetroArch but any
     * emulator works, so let the user pick.
     */
    private fun showTargetAppPicker() {
        val apps = foregroundMonitor.launchableApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_app_title)
            .setItems(labels) { _, which ->
                TargetAppPreference.set(this, apps[which].packageName)
                updateTargetAppLabel()
                if (isServiceRunning) {
                    Toast.makeText(this, R.string.toast_restart_to_apply, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** The service can stop itself (watchdog, notification, menu), so the
     *  button must be driven by the service's real state, not a local flag. */
    private fun updateServiceStateUi() {
        if (isServiceRunning) {
            tvStatusText.text = getString(R.string.status_service_running)
            tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            btnToggleService.text = getString(R.string.btn_stop_service)
            btnToggleService.setBackgroundResource(R.drawable.bg_card)
        } else {
            tvStatusText.text = getString(R.string.status_service_stopped)
            tvStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btnToggleService.text = getString(R.string.btn_start_service)
            btnToggleService.setBackgroundResource(R.drawable.bg_btn_primary)
        }
    }

    private fun checkHardwareCapabilities() {
        val abi = android.os.Build.SUPPORTED_ABIS.joinToString(", ")
        val cores = Runtime.getRuntime().availableProcessors()
        // Report what was actually detected - the big cluster is discovered at
        // runtime from cpufreq, not assumed to be a specific SoC.
        // The second line used to describe thread affinity and the inference
        // backend. True, and of no use to anyone holding the device: what a
        // player needs on this screen is the order to do things in.
        tvDeviceInfo.text = "SoC: ${android.os.Build.HARDWARE} | CPU Cores: $cores | ABI: $abi"
    }

    /**
     * Starts enhancement the direct way: frames from inside the emulator.
     *
     * This is now what the main button does, and screen capture is no longer a
     * mode the player picks. The capture route still exists and is still the
     * only one that works for cores that draw on the GPU, so it is reachable -
     * but as an exception offered when it is needed, not as a choice to be
     * understood up front.
     */
    private fun requestStartPipeline() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_grant_overlay_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!ShimFrameService.isRunning) {
            ContextCompat.startForegroundService(this, Intent(this, ShimFrameService::class.java))
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_SHIM_MODE, true)
        )
    }

    /**
     * The screen-capture route, for cores the shim cannot see into.
     *
     * Cannot be offered automatically: capture needs the projection consent
     * dialog, and only an Activity can show one - which is exactly why the
     * service can do no more than say which button to press.
     */
    private fun requestStartCapturePipeline() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_grant_overlay_first, Toast.LENGTH_SHORT).show()
            return
        }
        preselectConsoleFromCore()
        if (ShimFrameService.isRunning) {
            stopService(Intent(this, ShimFrameService::class.java))
        }
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    /**
     * Says the one thing left to do after consent, and only that.
     *
     * It used to explain WHY as well - that RetroArch reads its override only
     * when it loads content, so the running session cannot pick it up. True,
     * and beside the point at the moment someone is standing there waiting to
     * play: the two sentences that are actually instructions were competing
     * with a sentence that was documentation. §11.3 is where the reason lives.
     */
    private fun showRestartGameNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_ready_title)
            .setMessage(R.string.capture_ready_body)
            // Gets out of the way afterwards. Capture deliberately does not
            // start while this app is the thing on screen, so staying here
            // would leave someone looking at a settings page that is, quite
            // correctly, doing nothing.
            .setPositiveButton(R.string.shim_guide_ok) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun startOverlayService(resultCode: Int, data: Intent) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_PROJECTION_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
        // No toast here: the capture window is measured off a real frame, so
        // there is nothing for the user to line up by hand. The service's own
        // "已配置 <平台>，请重启 RetroArch" is the one that still tells them
        // something they have to act on.
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ShimFrameService::class.java))
    }

    /**
     * Reads what the device has, and publishes what the shim should cover.
     *
     * Deliberately does NOT touch the launch files. Rewriting them silently on
     * every start meant the app could change how a player's whole library
     * launches without ever saying so, and then report every console as set up
     * while the frontend was still holding a stale scan and nothing was
     * actually enhanced. Editing those files is now something the player asks
     * for, once, by pressing a button - and gets told what to do next.
     */
    private fun syncLaunchFiles() {
        Thread {
            try {
                val manager = PegasusConfigManager()
                val scan = manager.scan()
                if (scan.files.isEmpty()) {
                    Log.i(TAG, "no frontend launch files found - nothing to sync")
                    return@Thread
                }
                // Publishing the core list is safe to do silently: it only
                // tells the shim what to replicate itself for, and changes
                // nothing the frontend reads.
                manager.writeCoresList(scan.cores)
                shimCoreCount = scan.cores.size
                shimConvertedCount = scan.converted.size
                Log.i(TAG, "scan: ${scan.cores.size} cores, ${scan.converted.size} converted")
                runOnUiThread {
                    updateShimProbeUi()
                    if (isFinishing) return@runOnUiThread
                    // Nothing set up means nothing works yet, and a button
                    // someone has to notice is a worse way to say so than
                    // simply saying it.
                    if (shimConvertedCount == 0) showSetupGuide()
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncing the launch files failed", e)
            }
        }.apply { name = "LaunchSync"; isDaemon = true }.start()
    }

    /** Points the launch entries at the shim, on the player's say-so. */
    private fun applyLaunchFiles() {
        Thread {
            val manager = PegasusConfigManager()
            val scan = manager.scan()
            manager.writeCoresList(scan.cores)
            val result = runCatching { manager.applyShim() }
                .getOrElse { PegasusConfigManager.Result(0, 0, it.message ?: "failed") }
            val after = manager.scan()
            shimCoreCount = after.cores.size
            shimConvertedCount = after.converted.size
            Log.i(TAG, "apply: ${result.message}")
            runOnUiThread {
                updateShimProbeUi()
                if (isFinishing) return@runOnUiThread
                if (result.changed > 0) showReloadNotice()
                else Toast.makeText(
                    this, getString(R.string.toast_nothing_to_apply), Toast.LENGTH_LONG
                ).show()
            }
        }.apply { name = "LaunchApply"; isDaemon = true }.start()
    }

    /**
     * Undo, and undo everything.
     *
     * This button used to put the launch lines back and stop there, which was
     * wrong in the one way an undo must not be: it left the OTHER thing this
     * app writes - RetroArch's per-core viewport override, from a screen
     * capture session - in place. The player then reloaded the frontend, opened
     * a game, and found RetroArch drawing into the bottom-right corner with the
     * app already saying it had undone everything. Whichever console's override
     * was written is not necessarily the one they were playing, so the damage
     * usually surfaces on a completely different platform later, with nothing
     * connecting it back to here.
     *
     * The config half is a no-op on a machine that only ever ran direct mode -
     * that route writes no RetroArch keys at all - so this costs nothing in the
     * normal case and is the only way back in the abnormal one.
     */
    private fun restoreLaunchFiles() {
        Thread {
            val result = runCatching { PegasusConfigManager().restore() }
                .getOrElse { PegasusConfigManager.Result(0, 0, it.message ?: "failed") }
            shimConvertedCount = 0
            Log.i(TAG, "launch restore: ${result.message}")

            val config = RetroArchConfigManager(applicationContext)
            val configResult = runCatching {
                if (config.hasModifiedFiles()) config.restoreFromLatestBackup() else null
            }.getOrElse {
                Log.w(TAG, "restoring RetroArch's config failed", it)
                RetroArchBackupManager.Result(false, it.message ?: "failed")
            }
            configResult?.let { Log.i(TAG, "config restore on undo: ${it.message}") }

            runOnUiThread {
                updateShimProbeUi()
                if (isFinishing) return@runOnUiThread
                // Only when there was something to say. A clean machine gets
                // the reload notice and nothing else.
                configResult?.let {
                    Toast.makeText(
                        this,
                        if (it.ok) getString(R.string.toast_restored_stale_config) else it.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
                showReloadNotice()
            }
        }.apply { name = "LaunchRestore"; isDaemon = true }.start()
    }
}
