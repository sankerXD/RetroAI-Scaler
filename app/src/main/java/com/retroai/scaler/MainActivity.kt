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
import com.retroai.scaler.detector.PegasusConfigManager
import com.retroai.scaler.detector.RetroArchConfigManager
import com.retroai.scaler.detector.TargetAppPreference
import com.retroai.scaler.ui.AppLanguage
import com.retroai.scaler.ui.ConsoleType
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

    /** Filled in by syncLaunchFiles, read by the status line. */
    @Volatile private var shimCoreCount = 0
    @Volatile private var shimConvertedCount = 0
    private lateinit var tvShimProbe: TextView

    private val foregroundMonitor by lazy { ForegroundAppMonitor(this) }

    private val isServiceRunning: Boolean
        get() = OverlayService.isRunning

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
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
                if (result.ok) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.toast_restored_stale_config),
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

        // The console no longer has to be declared: direct mode reads the
        // resolution out of the frames themselves.
        findViewById<View>(R.id.cardConsole).visibility = View.GONE

        btnShimProbe = findViewById(R.id.btnShimProbe)
        btnShimDump = findViewById(R.id.btnShimDump)
        btnShimStart = findViewById(R.id.btnShimStart)
        tvShimProbe = findViewById(R.id.tvShimProbe)
        btnShimProbe.setOnClickListener { showSetupGuide() }
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

    private fun showSetupGuide() {
        val target = File(
            Environment.getExternalStorageDirectory(),
            "RetroAIScaler/cores/vbam_shim_libretro_android.so"
        )
        // Written out where the guide points, so the instruction is true by the
        // time anyone follows it.
        Thread {
            runCatching {
                target.parentFile?.mkdirs()
                assets.open("shim/vbam_shim_libretro_android.so").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }.onFailure { Log.w(TAG, "could not stage the shim core", it) }
        }.apply { isDaemon = true }.start()

        AlertDialog.Builder(this)
            .setTitle(R.string.shim_guide_title)
            .setMessage(getString(R.string.shim_guide_body, target.absolutePath))
            .setPositiveButton(R.string.shim_guide_ok, null)
            .show()
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
    private fun showReloadNotice(changed: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reload_notice_title)
            .setMessage(getString(R.string.reload_notice_body, changed))
            .setPositiveButton(R.string.shim_guide_ok, null)
            .show()
    }

    private fun confirmRestoreLaunchFiles() {
        AlertDialog.Builder(this)
            .setTitle(R.string.shim_restore)
            .setMessage(getString(R.string.shim_guide_body_restore))
            .setPositiveButton(R.string.shim_restore) { _, _ -> restoreLaunchFiles() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** The link runs on its own threads, so the only way this Activity learns
     *  anything about it is to look. */
    private fun updateShimProbeUi() {
        // Only offered when it is the answer to something: a core that draws
        // on the GPU is the one case direct mode cannot serve.
        btnShimCapture.visibility =
            if (ShimFrameService.hardwareRenderedCore) View.VISIBLE else View.GONE

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
        if (ShimFrameService.isRunning) {
            stopService(Intent(this, ShimFrameService::class.java))
        }
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
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
                if (result.changed > 0) showReloadNotice(result.changed)
                else Toast.makeText(
                    this, getString(R.string.toast_nothing_to_apply), Toast.LENGTH_LONG
                ).show()
            }
        }.apply { name = "LaunchApply"; isDaemon = true }.start()
    }

    /** Puts every launch line back to its real core. */
    private fun restoreLaunchFiles() {
        Thread {
            val result = runCatching { PegasusConfigManager().restore() }
                .getOrElse { PegasusConfigManager.Result(0, 0, it.message ?: "failed") }
            shimConvertedCount = 0
            Log.i(TAG, "launch restore: ${result.message}")
            runOnUiThread {
                updateShimProbeUi()
                if (!isFinishing) showReloadNotice(result.changed)
            }
        }.apply { name = "LaunchRestore"; isDaemon = true }.start()
    }
}
