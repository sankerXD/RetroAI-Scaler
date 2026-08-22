package com.retroai.scaler.detector

import android.content.Context
import com.retroai.scaler.R
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
         * The marker, the record format and the three ways back off a patched
         * file all live in [RetroArchOverridePatch], which is pure text and
         * therefore testable on a desktop. These two are kept as aliases
         * because the whole app already speaks them.
         */
        const val MARKER = RetroArchOverridePatch.MARKER

        /** Every marker this tool has ever written. */
        val MARKERS = RetroArchOverridePatch.MARKERS

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
            ),
            ConsoleType.DC to listOf("Flycast", "Flycast GL", "Redream")
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
     *
     * Not the primary way back any more: a patched file carries its own record
     * of what was displaced, and the restore prefers that. The snapshots answer
     * a different question - a file that is corrupt rather than merely patched.
     */
    fun backupManager(): RetroArchBackupManager? =
        findConfigRoot()?.let { RetroArchBackupManager(context, it) }

    fun ensureFreshBackup(): RetroArchBackupManager.Result {
        if (!hasAllFilesAccess()) {
            return RetroArchBackupManager.Result(false, context.getString(R.string.cfg_no_all_files))
        }
        val manager = backupManager()
            ?: return RetroArchBackupManager.Result(false, context.getString(R.string.cfg_no_config_dir))
        return manager.ensureFreshBackup()
    }

    fun hasModifiedFiles(): Boolean =
        hasAllFilesAccess() && backupManager()?.hasModifiedFiles(MARKERS) == true

    fun restoreFromLatestBackup(): RetroArchBackupManager.Result {
        if (!hasAllFilesAccess()) {
            return RetroArchBackupManager.Result(false, context.getString(R.string.cfg_no_all_files))
        }
        val manager = backupManager()
            ?: return RetroArchBackupManager.Result(false, context.getString(R.string.cfg_no_config_dir))
        return manager.restoreFromLatest(MARKERS)
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
            return Result(false, context.getString(R.string.cfg_no_all_files_write))
        }
        if (folders.isEmpty()) {
            return Result(false, context.getString(R.string.cfg_no_core_dir))
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
            patched == 0 -> Result(false, context.getString(R.string.cfg_write_failed,
                failures.joinToString().ifEmpty { context.getString(R.string.cfg_no_writable_cfg) }))
            failures.isEmpty() -> Result(true, context.getString(R.string.cfg_written, patched))
            else -> Result(true, context.getString(R.string.cfg_written_partial, patched, failures.size))
        }
    }

    /**
     * Rewrites the listed keys in place, keeping every other line untouched,
     * and - on the first touch - writing down what it displaced, so the restore
     * does not depend on a snapshot still existing and still being clean. See
     * [RetroArchOverridePatch].
     */
    private fun patchFile(cfg: File, values: Map<String, String>): Boolean {
        return try {
            val out = RetroArchOverridePatch.patch(cfg.readLines(), values)
            cfg.writeText(out.joinToString("\n") + "\n")
            Log.i(TAG, "patched ${cfg.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "patch failed for ${cfg.absolutePath}", e)
            false
        }
    }
}
