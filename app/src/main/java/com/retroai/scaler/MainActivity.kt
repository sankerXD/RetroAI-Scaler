package com.retroai.scaler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
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
import com.retroai.scaler.detector.RetroArchConfigManager
import com.retroai.scaler.detector.TargetAppPreference
import com.retroai.scaler.ui.ConsoleType
import com.retroai.scaler.ui.ProfilePreference
import com.retroai.scaler.service.OverlayService

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

    private val foregroundMonitor by lazy { ForegroundAppMonitor(this) }

    private val isServiceRunning: Boolean
        get() = OverlayService.isRunning

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startOverlayService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "未授予录屏捕获权限，无法启动 AI 增强", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkHardwareCapabilities()
        updatePermissionButtons()
        healLeftoverRetroArchConfig()
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
                            "检测到上次未还原的 RetroArch 配置，已自动还原",
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
                Toast.makeText(this, "打开「允许管理所有文件」开关", Toast.LENGTH_LONG).show()
                startActivity(RetroArchConfigManager.allFilesAccessIntent(this))
            } else {
                Toast.makeText(this, "所有文件访问已就绪", Toast.LENGTH_SHORT).show()
            }
        }
        tvTargetApp = findViewById(R.id.tvTargetApp)
        btnPickTargetApp = findViewById(R.id.btnPickTargetApp)

        btnGrantUsage.setOnClickListener {
            if (!ForegroundAppMonitor.hasUsageAccess(this)) {
                Toast.makeText(this, "在列表里找到 RetroAI-Scaler 并打开开关", Toast.LENGTH_LONG).show()
                startActivity(ForegroundAppMonitor.usageAccessSettingsIntent())
            } else {
                Toast.makeText(this, "使用情况访问已就绪", Toast.LENGTH_SHORT).show()
            }
        }

        btnPickTargetApp.setOnClickListener { showTargetAppPicker() }

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
                Toast.makeText(this, "悬浮窗权限已就绪", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopOverlayService()
            } else {
                requestStartPipeline()
            }
        }
    }

    /** The service can stop itself while this Activity stays resumed (floating
     *  menu, notification, watchdog), so poll instead of relying on onResume. */
    private val stateRefreshRunnable = object : Runnable {
        override fun run() {
            updateServiceStateUi()
            btnToggleService.postDelayed(this, 1000)
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
        btnGrantOverlay.text = if (hasOverlay) "已授权 ✓" else "授权"
        btnGrantOverlay.isEnabled = !hasOverlay

        val hasUsage = ForegroundAppMonitor.hasUsageAccess(this)
        btnGrantUsage.text = if (hasUsage) "已授权 ✓" else "授权"
        btnGrantUsage.isEnabled = !hasUsage

        val hasStorage = RetroArchConfigManager.hasAllFilesAccess()
        btnGrantStorage.text = if (hasStorage) "已授权 ✓" else "授权"
        btnGrantStorage.isEnabled = !hasStorage

        updateTargetAppLabel()
        updateConsoleLabel()
    }

    /**
     * The platform is chosen here rather than only in the floating menu: the
     * service writes RetroArch's config the moment enhancement starts, and it
     * needs to know which core to configure before any menu has been opened.
     */
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
                .setTitle("需要先停止 AI 增强")
                .setMessage(
                    "机种是在增强启动时读取的，运行中切换不会生效，还会让 RetroArch 停在上一个机种的取景窗上。\n\n" +
                            "停止增强会同时还原 RetroArch 配置，请等通知栏的常驻项消失后再启动。"
                )
                .setPositiveButton("停止并选择") { _, _ ->
                    stopOverlayService()
                    updateServiceStateUi()
                    showConsoleList()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        showConsoleList()
    }

    private fun showConsoleList() {
        val consoles = ConsoleType.values()
        val labels = consoles
            .map { "${it.displayName}  ${it.nativeWidth}×${it.nativeHeight}" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择游玩机种")
            .setItems(labels) { _, which ->
                // Only the console key changes; that console's own saved
                // settings are then loaded as-is.
                ProfilePreference.setConsole(this, consoles[which])
                updateConsoleLabel()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateConsoleLabel() {
        val profile = ProfilePreference.load(this)
        tvConsole.text = "${profile.console.displayName} " +
                "${profile.console.nativeWidth}×${profile.console.nativeHeight}"

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val out = profile.getOutputRect(metrics.widthPixels, metrics.heightPixels)
        val k = profile.getOutputScale(metrics.widthPixels, metrics.heightPixels)
        val src = profile.getPlannedSourceRect(metrics.widthPixels, metrics.heightPixels)
        tvOutputPlan.text = "取景窗 ${src.width()}×${src.height()} ${profile.sourceCorner.displayName}" +
                " → 输出 ${out.width()}×${out.height()}（整数 ${k}x）\n" +
                "启动时会自动写入 RetroArch 配置"
    }

    private fun updateTargetAppLabel() {
        val stored = TargetAppPreference.get(this)
        val detected = stored ?: foregroundMonitor.detectRetroArch()
        tvTargetApp.text = if (detected == null) {
            "目标应用: 未找到 RetroArch，请手动选择"
        } else {
            val suffix = if (stored == null) "（自动检测）" else ""
            "目标应用: ${foregroundMonitor.labelFor(detected)}$suffix"
        }
    }

    /**
     * The overlay follows exactly one app. Defaults to RetroArch but any
     * emulator works, so let the user pick.
     */
    private fun showTargetAppPicker() {
        val apps = foregroundMonitor.launchableApps()
        if (apps.isEmpty()) {
            Toast.makeText(this, "没有可选应用", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { "${it.label}\n${it.packageName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要增强的应用")
            .setItems(labels) { _, which ->
                TargetAppPreference.set(this, apps[which].packageName)
                updateTargetAppLabel()
                if (isServiceRunning) {
                    Toast.makeText(this, "重启 AI 增强后生效", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
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

    private fun requestStartPipeline() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
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
    }
}
