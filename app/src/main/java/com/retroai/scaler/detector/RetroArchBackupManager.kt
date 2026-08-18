package com.retroai.scaler.detector

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Timestamped snapshots of RetroArch's whole override tree.
 *
 * Policy (per user's spec):
 *  - full snapshot of everything reachable, not just the files being patched
 *  - checked on every service start; taken if missing or older than 15 days
 *  - restoring NEVER deletes a snapshot, so older ones stay available
 *
 * What is deliberately NOT included: RetroArch's main retroarch.cfg. It lives
 * in Android/data/<pkg>/files/, which Android 11+ blocks for every third-party
 * app (MANAGE_EXTERNAL_STORAGE does not cover it). We never write that file
 * either - only per-core overrides - so there is nothing there to roll back.
 */
class RetroArchBackupManager(private val configRoot: File) {

    companion object {
        private const val TAG = "RABackupManager"
        private const val BACKUP_ROOT = "/storage/emulated/0/RetroAIScaler/backups"
        private const val MANIFEST = "manifest.txt"

        /** Snapshots older than this are refreshed on the next start. */
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(15)

        /** How many snapshots to keep. Pruning happens when a NEW one is taken,
         *  never during a restore - a restore must never reduce the safety net. */
        private const val MAX_SNAPSHOTS = 5

        /** Bulk/binary payloads that live in the config tree but never change. */
        private val SKIP_EXTENSIONS = setOf("bin", "rom", "idx", "iso", "img", "chd")
        private const val MAX_FILE_BYTES = 256L * 1024

        private val STAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

        fun backupRoot(): File = File(BACKUP_ROOT)
    }

    data class Result(val ok: Boolean, val message: String)

    fun listSnapshots(): List<File> =
        (backupRoot().listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedByDescending { it.name }

    fun latestSnapshot(): File? = listSnapshots().firstOrNull()

    fun latestSnapshotAgeMs(): Long? {
        val latest = latestSnapshot() ?: return null
        val stamp = try {
            STAMP_FORMAT.parse(latest.name)?.time
        } catch (e: Exception) {
            null
        } ?: latest.lastModified()
        return System.currentTimeMillis() - stamp
    }

    /**
     * Called on every service start. Cheap when a recent snapshot exists.
     */
    fun ensureFreshBackup(): Result {
        if (!configRoot.isDirectory) {
            return Result(false, "没找到 RetroArch/config 目录")
        }
        val age = latestSnapshotAgeMs()
        return when {
            age == null -> createSnapshot("首次备份")
            age > MAX_AGE_MS -> createSnapshot("备份已超过 ${TimeUnit.MILLISECONDS.toDays(age)} 天")
            else -> Result(true, "备份是 ${TimeUnit.MILLISECONDS.toDays(age)} 天前的，无需重新备份")
        }
    }

    fun createSnapshot(reason: String = "手动备份"): Result {
        if (!configRoot.isDirectory) {
            return Result(false, "没找到 RetroArch/config 目录")
        }
        val dir = File(backupRoot(), STAMP_FORMAT.format(Date()))
        val target = File(dir, "config")
        if (!target.mkdirs() && !target.isDirectory) {
            return Result(false, "无法创建备份目录（检查「所有文件访问」权限）")
        }

        var copied = 0
        var skipped = 0
        var bytes = 0L
        configRoot.walkTopDown().forEach { src ->
            if (!src.isFile) return@forEach
            if (src.extension.lowercase() in SKIP_EXTENSIONS || src.length() > MAX_FILE_BYTES) {
                skipped++
                return@forEach
            }
            val rel = src.relativeToOrNull(configRoot) ?: return@forEach
            val dst = File(target, rel.path)
            try {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                copied++
                bytes += src.length()
            } catch (e: Exception) {
                Log.e(TAG, "backup copy failed: ${src.absolutePath}", e)
            }
        }

        try {
            File(dir, MANIFEST).writeText(
                buildString {
                    appendLine("RetroAI-Scaler 配置备份")
                    appendLine("时间: ${Date()}")
                    appendLine("原因: $reason")
                    appendLine("来源: ${configRoot.absolutePath}")
                    appendLine("文件: $copied 个 (${bytes / 1024} KB)，跳过 $skipped 个大文件/BIOS")
                    appendLine()
                    appendLine("注意: RetroArch 主配置 retroarch.cfg 位于")
                    appendLine("Android/data/com.retroarch.aarch64/files/ 下，安卓 11 起")
                    appendLine("第三方应用无法访问，因此不在此备份内。")
                    appendLine("本工具也从不修改主配置，只写核心覆盖配置。")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "manifest write failed", e)
        }

        val pruned = pruneOldSnapshots()

        Log.i(TAG, "snapshot created at ${dir.absolutePath}: $copied files, pruned $pruned")
        return Result(
            true,
            buildString {
                append("已备份 $copied 个配置文件 (${bytes / 1024} KB)\n${dir.name}")
                append("\n保留 ${listSnapshots().size}/$MAX_SNAPSHOTS 份")
                if (pruned > 0) append("，已清理 $pruned 份最旧的")
            }
        )
    }

    /** Keeps the newest [MAX_SNAPSHOTS] snapshots, deletes the rest. */
    private fun pruneOldSnapshots(): Int {
        val snapshots = listSnapshots()
        if (snapshots.size <= MAX_SNAPSHOTS) return 0

        var deleted = 0
        for (old in snapshots.drop(MAX_SNAPSHOTS)) {
            try {
                if (old.deleteRecursively()) {
                    deleted++
                    Log.i(TAG, "pruned old snapshot ${old.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "prune failed for ${old.name}", e)
            }
        }
        return deleted
    }

    /**
     * Puts back only the files this tool actually modified (they carry the
     * marker line), taken from the newest snapshot. The snapshot itself is kept.
     */
    /** True if any config still carries our marker, i.e. a session did not clean up. */
    fun hasModifiedFiles(markers: List<String>): Boolean =
        configRoot.walkTopDown().any { f ->
            f.isFile && f.name.endsWith(".cfg", ignoreCase = true) &&
                    try { f.useLines { l -> l.any { it.trim() in markers } } } catch (e: Exception) { false }
        }

    fun restoreFromLatest(markers: List<String>): Result {
        val snapshot = latestSnapshot()
            ?: return Result(false, "没有任何备份可用")
        val snapConfig = File(snapshot, "config")
        if (!snapConfig.isDirectory) {
            return Result(false, "备份 ${snapshot.name} 不完整")
        }

        var restored = 0
        val missing = mutableListOf<String>()

        configRoot.walkTopDown().forEach { live ->
            if (!live.isFile || !live.name.endsWith(".cfg", ignoreCase = true)) return@forEach
            val isOurs = try {
                live.useLines { lines -> lines.any { it.trim() in markers } }
            } catch (e: Exception) {
                false
            }
            if (!isOurs) return@forEach

            val rel = live.relativeToOrNull(configRoot) ?: return@forEach
            val backupFile = File(snapConfig, rel.path)
            if (!backupFile.isFile) {
                missing.add(rel.path)
                return@forEach
            }
            // Write to a sibling temp file and rename: if the process is killed
            // half way through a restore, the live config is either the old one
            // or the new one, never a truncated hybrid.
            val tmp = File(live.parentFile, live.name + ".rascaler-tmp")
            try {
                backupFile.copyTo(tmp, overwrite = true)
                if (tmp.renameTo(live)) {
                    restored++
                } else {
                    backupFile.copyTo(live, overwrite = true)
                    tmp.delete()
                    restored++
                }
            } catch (e: Exception) {
                Log.e(TAG, "restore failed: ${live.absolutePath}", e)
                tmp.delete()
                missing.add(rel.path)
            }
        }

        val message = buildString {
            append("已从备份 ${snapshot.name} 还原 $restored 个文件")
            append("\n（备份已保留，共 ${listSnapshots().size} 份）")
            if (missing.isNotEmpty()) {
                append("\n⚠ ${missing.size} 个文件在该备份中找不到:\n")
                append(missing.take(4).joinToString("\n"))
            }
        }
        return Result(restored > 0 || missing.isEmpty(), message)
    }
}
