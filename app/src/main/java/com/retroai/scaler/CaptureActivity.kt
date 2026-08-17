package com.retroai.scaler

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.retroai.scaler.capture.DatasetRecorder
import com.retroai.scaler.jni.NativeBridge
import com.retroai.scaler.service.OverlayService
import com.retroai.scaler.ui.ConsoleType
import com.retroai.scaler.ui.ProfilePreference

/**
 * Second launcher entry: the corpus capture tool.
 *
 * Separate icon, same APK. Collecting training frames needs almost everything
 * the enhancer already has - MediaProjection, the measured capture window, the
 * RetroArch config that forces native output - so a standalone project would
 * be a copy of the pipeline with a different button on top. This keeps one
 * implementation and still presents as its own tool on the device.
 *
 * The capture controls themselves live in the floating menu, because that is
 * the only surface that exists while the emulator is in the foreground. This
 * screen exists to explain the flow and show what has been collected so far.
 *
 * Meant to be retired from the launcher once the corpus is in - hiding the
 * entry, not deleting the code. GBA is only the first console; FC, SFC, WS and
 * GB will each want their own pass.
 */
class CaptureActivity : AppCompatActivity() {

    private val recorder by lazy { DatasetRecorder(applicationContext, NativeBridge()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        findViewById<TextView>(R.id.tvCaptureOpen).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val console = ProfilePreference.currentConsole(this)
        findViewById<TextView>(R.id.tvCaptureStatus).text = buildStatus(console)
    }

    private fun buildStatus(console: ConsoleType): String {
        val running = OverlayService.isRunning
        val counts = ConsoleType.values()
            .map { it to recorder.countFor(it) }
            .filter { it.second > 0 }

        return buildString {
            appendLine("当前机种：${console.displayName} ${console.nativeWidth}×${console.nativeHeight}")
            appendLine(if (running) "增强服务：运行中" else "增强服务：未启动")
            appendLine()
            if (counts.isEmpty()) {
                appendLine("尚未采集任何画面。")
            } else {
                appendLine("已采集：")
                counts.forEach { (c, n) -> appendLine("　${c.displayName}　$n 张") }
            }
            appendLine()
            appendLine("保存位置：")
            appendLine(recorder.outputPathFor(console))
        }
    }
}
