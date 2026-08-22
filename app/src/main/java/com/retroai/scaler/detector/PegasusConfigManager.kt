package com.retroai.scaler.detector

import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Points the frontend's launch lines at the shim, and puts them back.
 *
 * The frontend names the core to run in plain text, in files we can write, so
 * activating this route is a one-token edit rather than anything clever. That
 * is also why it deserves care: these files are how the player launches games,
 * and breaking one is the worst failure this project can produce - not a bad
 * picture, but a library that will not start.
 *
 * Every rule below was paid for by something, most of it measured on the test
 * device: 27 files, 59 launch lines, 22 distinct cores, one console referencing
 * four of them, and 2859 CRLF line endings in a single file.
 */
class PegasusConfigManager {

    companion object {
        private const val TAG = "PegasusConfig"

        private const val CORE_SUFFIX = "_libretro_android.so"
        private const val SHIM_SUFFIX = "_shim_libretro_android.so"
        private const val TOKEN = "-e LIBRETRO "

        /** Where the shim reads and writes; see shim/shim.c. */
        fun shimDir(): File =
            File(Environment.getExternalStorageDirectory(), "RetroAIScaler/shim")

        fun coresFile(): File = File(shimDir(), "cores.txt")
        fun installedFile(): File = File(shimDir(), "installed.txt")

        private fun backupDir(): File =
            File(Environment.getExternalStorageDirectory(), "RetroAIScaler/backups/pegasus")

        /**
         * "vbam_shim_libretro_android.so" -> "vbam_libretro_android.so".
         *
         * The edit is self-describing, and that is the point: any name carrying
         * "_shim" is one of ours, and removing it is the whole of the restore.
         * Nothing external has to be kept, so nothing external can be lost or
         * drift out of step - which is exactly the failure AGENT.md §2 records
         * from the marker-string era.
         */
        fun realCoreOf(name: String): String =
            if (name.contains("_shim_")) name.replaceFirst("_shim_", "_") else name

        fun shimNameOf(realCore: String): String? =
            if (realCore.endsWith(CORE_SUFFIX) && !realCore.contains("_shim_"))
                realCore.dropLast(CORE_SUFFIX.length) + SHIM_SUFFIX
            else null

        /** Every core filename named in [text], in order, with the path stripped. */
        fun coreNamesIn(text: String): List<String> {
            val names = mutableListOf<String>()
            rewriteText(text) { name -> names.add(name); null }
            return names
        }

        /**
         * Replaces core filenames in [text], leaving every other byte alone.
         * Returns the new text and how many tokens changed.
         *
         * Pure and Android-free on purpose: this is the code that edits the
         * files a player's whole library launches from, so it has to be
         * testable on a desktop rather than only on a handheld an hour of round
         * trips away.
         *
         * Three properties, each measured rather than assumed:
         *
         *  - it replaces the FILENAME ONLY, in place. These files are CRLF, and
         *    a rewriter that normalised newlines would produce a 2859-line diff
         *    in the file whose corruption the player actually feels. Replacing
         *    one token leaves the byte count changed by exactly the length
         *    difference and nothing else;
         *  - it works per TOKEN, not per file. One file holds a
         *    collection-level launch line and per-game overrides naming
         *    different cores;
         *  - [replacement] returning null means "leave this one exactly as it
         *    is", which is what makes a half-installed set of shims safe to
         *    apply: convert what exists, touch nothing else.
         */
        fun rewriteText(text: String, replacement: (String) -> String?): Pair<String, Int> {
            val out = StringBuilder(text.length)
            var from = 0
            var edits = 0

            while (true) {
                val at = text.indexOf(TOKEN, from)
                if (at < 0) break
                val start = at + TOKEN.length
                var stop = start
                while (stop < text.length && !text[stop].isWhitespace()) stop++

                val path = text.substring(start, stop)
                val slash = path.lastIndexOf('/')
                val want = replacement(path.substring(slash + 1))

                out.append(text, from, start)
                if (want == null) {
                    out.append(path)
                } else {
                    out.append(path, 0, slash + 1).append(want)
                    edits++
                }
                from = stop
            }
            out.append(text, from, text.length)
            return out.toString() to edits
        }
    }

    data class Scan(
        /** Every metadata file that carries at least one launch line. */
        val files: List<File>,
        /** Real core filenames, with any "_shim" already normalised away. */
        val cores: Set<String>,
        /** Cores whose launch lines currently point at the shim. */
        val converted: Set<String>
    )

    data class Result(val changed: Int, val files: Int, val message: String)

    /**
     * Finds the frontend's metadata files across every storage volume.
     *
     * Internal storage and every removable volume, because the rule is
     * "wherever the ROMs are". On the test device internal storage has no Roms
     * directory at all and all 27 files live on the SD card, so "both places
     * have some" is not safe to assume in either direction.
     */
    private fun metadataFiles(): List<File> {
        val volumes = mutableListOf(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach { v ->
            if (v.isDirectory && v.name != "emulated" && v.name != "self") volumes.add(v)
        }
        val found = mutableListOf<File>()
        for (vol in volumes) {
            val systems = File(vol, "Roms").listFiles() ?: continue
            for (system in systems) {
                val meta = File(system, "metadata.pegasus.txt")
                if (meta.isFile) found.add(meta)
            }
        }
        return found
    }

    fun scan(): Scan {
        val files = mutableListOf<File>()
        val cores = mutableSetOf<String>()
        val converted = mutableSetOf<String>()

        for (meta in metadataFiles()) {
            val text = try {
                meta.readText()
            } catch (t: Throwable) {
                Log.w(TAG, "cannot read ${meta.absolutePath}", t)
                continue
            }
            var any = false
            for (name in coreNamesIn(text)) {
                any = true
                // Normalise before collecting. A line we already converted still
                // describes a core this device uses, and forgetting that is how
                // a rescan after activation loses track of what it converted -
                // seen as 21 cores on a device that references 22.
                val real = realCoreOf(name)
                cores.add(real)
                if (name != real) converted.add(real)
            }
            if (any) files.add(meta)
        }
        Log.i(TAG, "scan: ${files.size} files, ${cores.size} cores, ${converted.size} converted")
        return Scan(files, cores, converted)
    }

    /** Publishes the core list the shim replicates itself for. */
    fun writeCoresList(cores: Set<String>): Boolean = try {
        shimDir().mkdirs()
        val tmp = File(shimDir(), "cores.txt.tmp")
        tmp.writeText(cores.sorted().joinToString("\n", postfix = "\n"))
        tmp.renameTo(coresFile()).also { if (!it) tmp.delete() }
    } catch (t: Throwable) {
        Log.w(TAG, "cannot write the core list", t)
        false
    }

    /** The shims that actually exist, as reported from inside RetroArch. */
    fun installedShims(): Set<String> = try {
        installedFile().takeIf { it.isFile }?.readLines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    } catch (t: Throwable) {
        Log.w(TAG, "cannot read the installed list", t)
        emptySet()
    }

    /**
     * Points launch lines at the shim, but only where the shim is really there.
     *
     * That interlock is the whole safety story. A launch line naming a core
     * that does not exist is the one way this route hard-fails for the player:
     * RetroArch refuses to open it and the game simply does not start. So the
     * app never guesses - it acts on the list the shim wrote from inside
     * RetroArch's own core directory.
     */
    fun applyShim(): Result {
        val installed = installedShims()
        if (installed.isEmpty()) {
            return Result(0, 0, "no shim cores are installed yet")
        }
        return rewrite { name ->
            val shim = shimNameOf(name)
            if (shim != null && shim in installed) shim else null
        }
    }

    /** Puts every converted launch line back to its real core. */
    fun restore(): Result = rewrite { name ->
        if (name.contains("_shim_")) realCoreOf(name) else null
    }

    fun isActive(): Boolean = scan().converted.isNotEmpty()

    private fun rewrite(replacement: (String) -> String?): Result {
        var changed = 0
        var touched = 0
        var failed = 0

        for (meta in scan().files) {
            val text = try {
                meta.readText()
            } catch (t: Throwable) {
                Log.w(TAG, "cannot read ${meta.absolutePath}", t)
                failed++
                continue
            }

            val (updated, edits) = rewriteText(text, replacement)
            if (edits == 0) continue

            if (!backupOnce(meta)) {
                Log.w(TAG, "skipping ${meta.absolutePath}: could not back it up first")
                failed++
                continue
            }
            if (writeAtomically(meta, updated)) {
                changed += edits
                touched++
            } else {
                failed++
            }
        }

        val message = "rewrote $changed launch line(s) in $touched file(s)" +
            if (failed > 0) ", $failed failed" else ""
        Log.i(TAG, message)
        return Result(changed, touched, message)
    }

    /**
     * Keeps one pristine copy of each file before it is ever modified.
     *
     * The edit is self-describing, so a restore does not need this - but a
     * restore only helps against a wrong edit, not against a corrupt file, and
     * these are the files the player's entire library launches from. Once per
     * file and never overwritten, so it always holds the version from before
     * anything of ours touched it.
     */
    private fun backupOnce(meta: File): Boolean = try {
        val dir = File(backupDir(), meta.parentFile?.name ?: "unknown")
        dir.mkdirs()
        val backup = File(dir, meta.name)
        if (backup.isFile) true else meta.copyTo(backup, overwrite = false).isFile
    } catch (t: Throwable) {
        Log.w(TAG, "backup of ${meta.absolutePath} failed", t)
        false
    }

    /**
     * Temp file plus rename, so a process killed mid-write cannot leave a
     * truncated launch file behind. The temp lives in the same directory
     * because a rename across filesystems is not atomic and on a removable
     * volume that is not a hypothetical.
     */
    private fun writeAtomically(target: File, content: String): Boolean = try {
        val tmp = File(target.parentFile, "${target.name}.retroai-tmp")
        tmp.writeText(content)
        if (tmp.renameTo(target)) {
            true
        } else {
            tmp.delete()
            Log.w(TAG, "rename into ${target.absolutePath} failed")
            false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "writing ${target.absolutePath} failed", t)
        false
    }
}
