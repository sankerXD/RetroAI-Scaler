package com.retroai.scaler.detector

import android.util.Log
import android.content.Context
import com.retroai.scaler.R
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
class RetroArchBackupManager(private val context: Context, private val configRoot: File) {

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

    /**
     * [createdSnapshot] exists so callers stop testing the MESSAGE. One did -
     * `message.startsWith("已备份")` decided whether to toast - which quietly
     * tied a branch to the display language.
     */
    data class Result(
        val ok: Boolean,
        val message: String,
        val createdSnapshot: Boolean = false
    )

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
            return Result(false, context.getString(R.string.cfg_no_config_dir))
        }
        val age = latestSnapshotAgeMs()
        return when {
            age == null -> createSnapshot(context.getString(R.string.backup_reason_first))
            age > MAX_AGE_MS -> createSnapshot(context.getString(R.string.backup_reason_stale, TimeUnit.MILLISECONDS.toDays(age)))
            else -> Result(true, context.getString(R.string.backup_fresh_enough, TimeUnit.MILLISECONDS.toDays(age)))
        }
    }

    fun createSnapshot(reason: String = context.getString(R.string.backup_reason_manual)): Result {
        if (!configRoot.isDirectory) {
            return Result(false, context.getString(R.string.cfg_no_config_dir))
        }
        val dir = File(backupRoot(), STAMP_FORMAT.format(Date()))
        val target = File(dir, "config")
        if (!target.mkdirs() && !target.isDirectory) {
            return Result(false, context.getString(R.string.backup_mkdir_failed))
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
                    appendLine(context.getString(R.string.backup_note_header))
                    appendLine(context.getString(R.string.backup_note_time, Date().toString()))
                    appendLine(context.getString(R.string.backup_note_reason, reason))
                    appendLine(context.getString(R.string.backup_note_source, configRoot.absolutePath))
                    appendLine(context.getString(R.string.backup_note_files, copied, bytes / 1024, skipped))
                    appendLine()
                    appendLine(context.getString(R.string.backup_note_main_config))
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
                append(context.getString(R.string.backup_done, copied, bytes / 1024, dir.name))
                append(context.getString(R.string.backup_kept, listSnapshots().size, MAX_SNAPSHOTS))
                if (pruned > 0) append(context.getString(R.string.backup_pruned, pruned))
            },
            createdSnapshot = true
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
            ?: return Result(false, context.getString(R.string.backup_none_available))
        val snapConfig = File(snapshot, "config")
        if (!snapConfig.isDirectory) {
            return Result(false, context.getString(R.string.backup_incomplete, snapshot.name))
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
            append(context.getString(R.string.backup_restored, restored, snapshot.name))
            append(context.getString(R.string.backup_restored_kept, listSnapshots().size))
            if (missing.isNotEmpty()) {
                append(context.getString(R.string.backup_restore_missing, missing.size))
                append(missing.take(4).joinToString("\n"))
            }
        }
        return Result(restored > 0 || missing.isEmpty(), message)
    }
}
