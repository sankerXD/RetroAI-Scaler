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
 *  - a snapshot never contains our own edit, even when one is live at the time
 *    it is taken - see [cleanCopyOf]
 *
 * The snapshots are no longer the primary way back, though. A patched file
 * records what it displaced, in itself, and that record is what a restore
 * prefers ([RetroArchOverridePatch]). These stay as the answer to a corrupt
 * file, which a self-describing edit cannot help with.
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

        /**
         * A snapshot directory is named by its stamp, and a directory that is
         * not named by a stamp is not a snapshot.
         *
         * This was a one-word assumption - "every directory under backups/ is a
         * snapshot" - and it was false from the moment the launch-file rewriter
         * shipped, because that keeps its pristine copies in
         * `backups/pegasus`. "pegasus" sorts ABOVE every yyyyMMdd stamp, so it
         * became "the newest snapshot": `File(pegasus, "config")` is not a
         * directory, so **every restore since then found no usable backup**.
         * The old code said "backup incomplete" and restored nothing; the
         * marker stayed, and the next start restored nothing again.
         *
         * It was also one snapshot away from `pruneOldSnapshots` deleting those
         * launch-file copies as an old snapshot.
         *
         * Checked by shape rather than by excluding the one name we know about:
         * anything else that ever lands in this directory is excluded too.
         */
        private val STAMP = Regex("""^\d{8}-\d{6}$""")

        fun isSnapshotName(name: String): Boolean = STAMP.matches(name)

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
        (backupRoot().listFiles { f -> f.isDirectory && isSnapshotName(f.name) } ?: emptyArray())
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
        var sanitised = 0
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
                /*
                 * A snapshot must never contain our own edit.
                 *
                 * Callers restore before they snapshot for exactly this reason,
                 * but that only works when the restore had something to restore
                 * FROM - on a first run, or after the snapshots were pruned, it
                 * has nothing, and the very next line would then enshrine our
                 * viewport as the user's original. From then on every restore
                 * "succeeds" and puts the corner window straight back, forever,
                 * with nothing anywhere reporting a problem.
                 *
                 * So the copy goes through the same un-patcher the restore
                 * uses. The file on disk is left exactly as it is; only what
                 * lands in the snapshot is cleaned.
                 */
                val clean = cleanCopyOf(src)
                if (clean != null) {
                    dst.writeText(clean.joinToString("\n") + "\n")
                    sanitised++
                } else {
                    src.copyTo(dst, overwrite = true)
                }
                copied++
                bytes += src.length()
            } catch (e: Exception) {
                Log.e(TAG, "backup copy failed: ${src.absolutePath}", e)
            }
        }
        if (sanitised > 0) {
            Log.w(TAG, "snapshot: $sanitised file(s) still carried our marker and were un-patched first")
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

    /**
     * The version of [src] that belongs in a snapshot, or null when the file on
     * disk is already free of our edits and can simply be copied.
     */
    private fun cleanCopyOf(src: File): List<String>? {
        if (!src.name.endsWith(".cfg", ignoreCase = true)) return null
        val lines = try {
            src.readLines()
        } catch (e: Exception) {
            return null
        }
        if (!RetroArchOverridePatch.isOurs(lines)) return null
        return RetroArchOverridePatch.unpatch(lines) ?: RetroArchOverridePatch.strip(lines)
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

    /** True if any config still carries our marker, i.e. a session did not clean up. */
    fun hasModifiedFiles(markers: List<String>): Boolean = modifiedFiles(markers).isNotEmpty()

    private fun modifiedFiles(markers: List<String>): List<File> =
        configRoot.walkTopDown().filter { f ->
            f.isFile && f.name.endsWith(".cfg", ignoreCase = true) &&
                    try { f.useLines { l -> l.any { it.trim() in markers } } } catch (e: Exception) { false }
        }.toList()

    /**
     * Takes our edit back off every file that carries the marker.
     *
     * Three ways back, tried in this order, because they are ordered by how
     * much they can promise:
     *
     *  1. **the file's own record** - exact, per key, and it leaves alone
     *     anything the player changed in that file since. Present on every file
     *     patched by this build or later.
     *  2. **the newest snapshot** - the whole file as it was on that date. For
     *     files patched by an older build, which carry no record.
     *  3. **stripping our keys** - for a file with neither. An override is a
     *     layer over retroarch.cfg, so a deleted key falls through to the
     *     player's real setting; see [RetroArchOverridePatch.strip].
     *
     * There used to be only (2), and it could not succeed at all when the
     * snapshot was missing the file - or, worse, when the snapshot had been
     * taken while our own edit was live, in which case restoring wrote our
     * viewport straight back and reported success for doing it. **Every file is
     * therefore re-read afterwards**: still carrying the marker is a failure,
     * whatever the step in front of it returned.
     *
     * Snapshots are never deleted here. A restore must not reduce the safety
     * net.
     */
    fun restoreFromLatest(markers: List<String>): Result {
        val snapshot = latestSnapshot()
        val snapConfig = snapshot?.let { File(it, "config") }?.takeIf { it.isDirectory }

        var restored = 0
        var fromSnapshot = 0
        var strippedKeys = 0
        val failed = mutableListOf<String>()

        for (live in modifiedFiles(markers)) {
            val rel = live.relativeToOrNull(configRoot)?.path ?: live.name
            val lines = try {
                live.readLines()
            } catch (e: Exception) {
                Log.e(TAG, "restore could not read ${live.absolutePath}", e)
                failed.add(rel)
                continue
            }

            var how = "record"
            var out = RetroArchOverridePatch.unpatch(lines)
            if (out == null) {
                // No record: an older build wrote this. The snapshot is the
                // next best thing - unless it holds our own edit, which is the
                // failure this whole ordering exists to survive.
                val backupFile = snapConfig?.let { File(it, rel) }?.takeIf { it.isFile }
                val backupLines = backupFile?.let {
                    try { it.readLines() } catch (e: Exception) { null }
                }
                if (backupLines != null && !RetroArchOverridePatch.isOurs(backupLines)) {
                    out = backupLines
                    how = "snapshot"
                    fromSnapshot++
                } else {
                    out = RetroArchOverridePatch.strip(lines)
                    how = "stripped"
                    strippedKeys++
                }
            }

            if (!writeAtomically(live, out)) {
                failed.add(rel)
                continue
            }
            // Believe the file, not the step that just wrote it.
            val stillOurs = try {
                RetroArchOverridePatch.isOurs(live.readLines())
            } catch (e: Exception) {
                true
            }
            if (stillOurs) {
                Log.e(TAG, "restore left our marker in ${live.absolutePath} (via $how)")
                failed.add(rel)
            } else {
                Log.i(TAG, "restored ${live.absolutePath} via $how")
                restored++
            }
        }

        val message = buildString {
            append(context.getString(R.string.backup_restored_files, restored))
            if (fromSnapshot > 0) {
                append(context.getString(
                    R.string.backup_restored_via_snapshot,
                    fromSnapshot,
                    snapshot?.name ?: "-"
                ))
            }
            if (strippedKeys > 0) {
                append(context.getString(R.string.backup_restored_via_strip, strippedKeys))
            }
            append(context.getString(R.string.backup_restored_kept, listSnapshots().size))
            if (failed.isNotEmpty()) {
                append(context.getString(R.string.backup_restore_failed, failed.size))
                append(failed.take(4).joinToString("\n"))
            }
        }
        return Result(failed.isEmpty(), message)
    }

    /**
     * Temp file plus rename, so a process killed half way through a restore
     * leaves the live config as either the old one or the new one, never a
     * truncated hybrid. The temp is a sibling because a cross-filesystem rename
     * is not atomic, and on a removable volume that is not hypothetical.
     */
    private fun writeAtomically(target: File, lines: List<String>): Boolean {
        val tmp = File(target.parentFile, target.name + ".rascaler-tmp")
        return try {
            tmp.writeText(lines.joinToString("\n") + "\n")
            if (tmp.renameTo(target)) return true
            target.writeText(lines.joinToString("\n") + "\n")
            tmp.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "restore write failed: ${target.absolutePath}", e)
            tmp.delete()
            false
        }
    }
}
