package com.retroai.scaler.detector

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.retroai.scaler.ui.ConsoleType
import java.io.File

/**
 * Writes the capture-window viewport straight into RetroArch's config.
 *
 * RetroArch's MAIN retroarch.cfg lives in Android/data/<pkg>/files/ on modern
 * builds, and since Android 11 no third-party app can touch another app's
 * Android/data - not even with MANAGE_EXTERNAL_STORAGE. What IS reachable is
 * the override directory (`rgui_config_directory`, normally
 * /storage/emulated/0/RetroArch/config), and with `auto_overrides_enable=true`
 * RetroArch layers those files on top of the main config whenever content
 * loads. So the viewport goes in as a per-core override.
 *
 * Every .cfg inside the core's folder gets patched, because RetroArch applies
 * core -> content-directory -> game overrides in that order and a later one
 * would otherwise put the old viewport back.
 */
class RetroArchConfigManager(private val context: Context) {

    companion object {
        private const val TAG = "RAConfigManager"

        /**
         * Stamped into every file we touch. RetroArch ignores '#' lines, and it
         * lets restore tell "this file is ours but its backup is gone" apart
         * from "this file was never touched" - otherwise a file that cannot be
         * restored is silently skipped and the user is told everything is fine.
         */
        const val MARKER = "# --- modified by Retro-AI-Scaler ---"

        private val CONFIG_ROOT_CANDIDATES = listOf(
            "/storage/emulated/0/RetroArch/config",
            "/storage/emulated/0/RetroArch64/config",
            "/sdcard/RetroArch/config"
        )

        /**
         * Core folder names RetroArch uses for overrides, per console. Matched
         * case-insensitively against the folders that actually exist.
         */
        private val CORE_FOLDERS: Map<ConsoleType, List<String>> = mapOf(
            ConsoleType.GBA to listOf("mGBA", "VBA Next", "VBA-M", "Beetle GBA", "gpSP"),
            ConsoleType.GBC to listOf("Gambatte", "SameBoy", "Gearboy", "TGB Dual"),
            ConsoleType.SFC to listOf(
                "Snes9x", "Snes9x 2010", "Snes9x 2005", "bsnes", "bsnes-mercury Accuracy",
                "Snes9x 2002", "nSide-Balanced"
            ),
            ConsoleType.FC to listOf(
                "FCEUmm", "Nestopia", "Mesen", "QuickNES", "FCEUX", "bnes"
            ),
            ConsoleType.MD to listOf("Genesis Plus GX", "PicoDrive"),
            ConsoleType.PS1 to listOf(
                "Beetle PSX", "Beetle PSX HW", "PCSX-ReARMed", "SwanStation", "DuckStation"
            )
        )

        fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

        fun allFilesAccessIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    data class Result(val ok: Boolean, val message: String)

    /**
     * Snapshot-backed safety net. Per-file .rascaler-bak copies are gone: they
     * lived next to the originals, were consumed (deleted) by a restore, and a
     * file whose backup had disappeared could not be rolled back at all.
     * RetroArchBackupManager keeps dated full snapshots instead, and never
     * deletes them.
     */
    fun backupManager(): RetroArchBackupManager? =
        findConfigRoot()?.let { RetroArchBackupManager(it) }

    fun ensureFreshBackup(): RetroArchBackupManager.Result {
        if (!hasAllFilesAccess()) {
            return RetroArchBackupManager.Result(false, "缺少「所有文件访问」权限")
        }
        val manager = backupManager()
            ?: return RetroArchBackupManager.Result(false, "没找到 RetroArch/config 目录")
        return manager.ensureFreshBackup()
    }

    fun hasModifiedFiles(): Boolean =
        hasAllFilesAccess() && backupManager()?.hasModifiedFiles(MARKER) == true

    fun restoreFromLatestBackup(): RetroArchBackupManager.Result {
        if (!hasAllFilesAccess()) {
            return RetroArchBackupManager.Result(false, "缺少「所有文件访问」权限")
        }
        val manager = backupManager()
            ?: return RetroArchBackupManager.Result(false, "没找到 RetroArch/config 目录")
        return manager.restoreFromLatest(MARKER)
    }

    fun findConfigRoot(): File? =
        CONFIG_ROOT_CANDIDATES.map { File(it) }.firstOrNull { it.isDirectory }

    /** Existing override folders for this console, most specific first. */
    fun coreFoldersFor(console: ConsoleType): List<File> {
        val root = findConfigRoot() ?: return emptyList()
        val wanted = CORE_FOLDERS[console] ?: return emptyList()
        val present = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return wanted.mapNotNull { name ->
            present.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    /** All override folders, for the manual picker. */
    fun allCoreFolders(): List<File> {
        val root = findConfigRoot() ?: return emptyList()
        return (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Writes the capture window into every override of the given core folders.
     *
     * Deliberately does NOT write custom_viewport_x/y. Those are only honoured
     * when video_scale_integer is off, and their origin convention differs
     * between RetroArch's video drivers (this repack runs Vulkan). Writing them
     * blanked the screen entirely on a real device.
     *
     * Instead: keep integer scaling on (which also guarantees an exact 1:1
     * pixel copy - the best possible input for the upscaler), give it a
     * native-sized bounding box so the scale lands on 1x, and place it with
     * video_viewport_bias_x/y, which are normalised 0..1 and driver
     * independent. Where the game actually lands is then measured from a
     * captured frame rather than assumed.
     */
    fun applyViewport(
        folders: List<File>,
        nativeWidth: Int,
        nativeHeight: Int,
        biasX: Float,
        biasY: Float,
        disableShader: Boolean
    ): Result {
        if (!hasAllFilesAccess()) {
            return Result(false, "缺少「所有文件访问」权限，无法写入 RetroArch 配置")
        }
        if (folders.isEmpty()) {
            return Result(false, "没找到该平台的 RetroArch 核心配置目录")
        }

        val values = mutableMapOf(
            // Custom aspect ratio - the only mode that honours a viewport size.
            "aspect_ratio_index" to "23",
            // Integer scaling ON: the bounding box below then resolves to
            // exactly 1x, i.e. pristine native pixels with no resampling.
            "video_scale_integer" to "true",
            "custom_viewport_width" to nativeWidth.toString(),
            "custom_viewport_height" to nativeHeight.toString(),
            // Normalised placement, no coordinate-origin guesswork.
            "video_viewport_bias_x" to "%.6f".format(biasX),
            "video_viewport_bias_y" to "%.6f".format(biasY),
            // Repack bezels are full-screen input overlays drawn on top of the
            // game - they land inside the capture window and get magnified.
            "input_overlay_enable" to "false",
            // RetroArch's own CPU filters (LCD grids, scanline softpatches)
            // rewrite the pixels before we ever see them. Whatever we capture
            // then is somebody else's interpretation, not the emulator's
            // output - fatal both for upscaling and for training data.
            "video_filter" to "",
            // Bilinear smoothing. Sampling block centres survives it, but only
            // exactly; anything that shifts the sampling point by half a pixel
            // starts blending neighbours. Nearest keeps that margin.
            "video_smooth" to "false"
        )
        if (disableShader) {
            // RetroArch's own shader runs BEFORE we upscale, so the network
            // would be fed an already-filtered image.
            values["video_shader_enable"] = "false"
        }

        var patched = 0
        val failures = mutableListOf<String>()
        for (folder in folders) {
            val cfgFiles = folder.listFiles { f ->
                f.isFile && f.name.endsWith(".cfg", ignoreCase = true)
            } ?: continue
            for (cfg in cfgFiles) {
                if (patchFile(cfg, values)) patched++ else failures.add(cfg.name)
            }
        }

        return when {
            patched == 0 -> Result(false, "写入失败：${failures.joinToString().ifEmpty { "没有可写的 .cfg" }}")
            failures.isEmpty() -> Result(true, "已写入 $patched 个配置文件，重启 RetroArch 或重新载入游戏生效")
            else -> Result(true, "已写入 $patched 个，失败 ${failures.size} 个")
        }
    }

    /**
     * Rewrites the listed keys in place, keeping every other line untouched, and
     * keeps a one-time backup so the user's original repack config is never lost.
     */
    private fun patchFile(cfg: File, values: Map<String, String>): Boolean {
        return try {
            val original = cfg.readLines()
            val remaining = values.toMutableMap()
            val out = ArrayList<String>(original.size + values.size + 1)
            if (original.none { it.trim() == MARKER }) {
                out.add(MARKER)
            }

            for (line in original) {
                val key = line.substringBefore('=').trim()
                val replacement = remaining.remove(key)
                if (replacement != null) {
                    out.add("$key = \"$replacement\"")
                } else {
                    out.add(line)
                }
            }
            for ((key, value) in remaining) {
                out.add("$key = \"$value\"")
            }

            cfg.writeText(out.joinToString("\n") + "\n")
            Log.i(TAG, "patched ${cfg.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "patch failed for ${cfg.absolutePath}", e)
            false
        }
    }
}
