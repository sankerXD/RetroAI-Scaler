package com.retroai.scaler.detector

/**
 * The text half of the RetroArch override edit: what a patch writes, and the
 * three ways a restore can take it back off.
 *
 * Pure and Android-free on purpose, like the launch-file rewriter next door,
 * and for the same reason: this is the code that decides whether someone's
 * RetroArch comes back or stays squeezed into a corner of its own window
 * forever, and that is not a thing to discover an hour of round trips later on
 * a handheld.
 *
 * ## Why a patch records what it displaced
 *
 * The restore used to be "copy the file back out of the newest dated
 * snapshot", and that has two failure modes which both end the same way -
 * RetroArch permanently in its corner, with every restore cheerfully
 * reporting success:
 *
 *  - **no snapshot holds that file.** Taken before the file existed, pruned
 *    (five are kept), or the very first run found leftovers from an older
 *    build and had nothing to restore them from.
 *  - **the snapshot holds OUR edit.** A snapshot taken while a patch was live
 *    makes our own viewport the "original", and from then on restoring puts
 *    it right back. Nothing detects this, because a restore that changed the
 *    file counts as a restore.
 *
 * So the patch now records what it displaced *inside the file it displaced it
 * in*. RetroArch ignores '#' lines, so the record is free, and it travels with
 * the thing it describes: it cannot go missing, go stale, or be pruned. That
 * is the same discipline the launch-file rewriter got (§17.7 rule 4), arrived
 * at the same way.
 *
 * The snapshots stay - they are the answer to a corrupt file, which a
 * self-describing edit cannot help with - but they are no longer the only way
 * back.
 */
object RetroArchOverridePatch {

    /**
     * Stamped into every file we touch. RetroArch ignores '#' lines, and it
     * lets restore tell "this file is ours but its backup is gone" apart from
     * "this file was never touched" - otherwise a file that cannot be restored
     * is silently skipped and the user is told everything is fine.
     */
    const val MARKER = "# --- modified by RetroAI-Scaler ---"

    /**
     * What the marker used to say, before the project name lost its hyphens.
     * Detection has to accept it or a config written by an older build is
     * unrecognisable, and "only restore files we marked" quietly becomes
     * "never restore that file" - leaving someone's RetroArch permanently
     * configured for our viewport with no way back.
     *
     * Only ever recognised, never written. Renaming again means ADDING to this
     * list, never replacing.
     */
    const val LEGACY_MARKER = "# --- modified by Retro-AI-Scaler ---"

    /** Every marker this tool has ever written. */
    val MARKERS = listOf(MARKER, LEGACY_MARKER)

    /** A key we overwrote, carrying its whole original line, byte for byte. */
    private const val REPLACED = "# --- RetroAI-Scaler replaced: "

    /** A key the file did not have. Restoring one means deleting the line. */
    private const val ADDED = "# --- RetroAI-Scaler added: "

    /**
     * Every key this tool has ever written, current build or not.
     *
     * Only the last-resort strip uses this, and only for files patched before
     * the record existed - which is exactly the case where the current key set
     * is the wrong question. `custom_viewport_x/y` are deliberately absent:
     * they have never been written by any build (AGENT.md §2), so deleting one
     * outright would be deleting something that can only be the user's own.
     */
    val KEYS = listOf(
        "aspect_ratio_index",
        "video_scale_integer",
        "custom_viewport_width",
        "custom_viewport_height",
        "video_viewport_bias_x",
        "video_viewport_bias_y",
        "input_overlay_enable",
        "video_shader_enable",
        "video_filter",
        "video_smooth"
    )

    /**
     * The rest of the custom-viewport group: ours to delete only when the size
     * goes with them.
     *
     * A custom viewport is width, height and an offset, and it means nothing
     * without the size. A repack override that read
     *
     *     custom_viewport_width  = "1200"   <- ours to take over
     *     custom_viewport_height = "800"    <- ours to take over
     *     custom_viewport_y      = "-45"    <- theirs, never touched
     *
     * came out of a strip as an offset with no size, which RetroArch then
     * combined with whatever the MAIN config had for a size - and drew the
     * picture at some unrelated magnification, shifted up 45 pixels. Seen on
     * the test device: a Game Boy Advance title screen blown up past the edges
     * of the panel. Half a viewport is worse than none.
     */
    private val VIEWPORT_ORPHANS = listOf("custom_viewport_x", "custom_viewport_y")

    /** True if this tool wrote into these lines - any build, any era. */
    fun isOurs(lines: List<String>): Boolean = lines.any { it.trim() in MARKERS }

    /** True if the file carries the record an exact restore needs. */
    fun hasRecord(lines: List<String>): Boolean =
        lines.any { it.startsWith(REPLACED) || it.startsWith(ADDED) }

    /**
     * Rewrites the listed keys, leaving every other line exactly where it was,
     * and - on the first touch only - writes down what it displaced.
     *
     * "First touch only" is the whole correctness argument for the record: a
     * second patch would otherwise record OUR values as the originals, which is
     * the poisoned-snapshot bug moved inside the file. It is also why the
     * marker doubles as the flag: a file carrying it has already been recorded.
     */
    fun patch(lines: List<String>, values: Map<String, String>): List<String> {
        val firstTouch = !isOurs(lines)
        val remaining = LinkedHashMap(values)
        val body = ArrayList<String>(lines.size + values.size)
        val record = ArrayList<String>(values.size)

        for (line in lines) {
            val key = keyOf(line)
            val replacement = remaining.remove(key)
            if (replacement == null) {
                body.add(line)
                continue
            }
            if (firstTouch) record.add(REPLACED + line)
            body.add(assign(key, replacement))
        }
        for ((key, value) in remaining) {
            if (firstTouch) record.add(ADDED + key)
            body.add(assign(key, value))
        }

        if (!firstTouch) return body
        return ArrayList<String>(1 + record.size + body.size).apply {
            add(MARKER)
            addAll(record)
            addAll(body)
        }
    }

    /**
     * The exact restore: puts back what the record says was there, deletes what
     * the record says we added, and drops our own comment lines.
     *
     * Returns null when the file carries no record, which means it was patched
     * by a build older than this one - the caller then has the snapshot and, as
     * a last resort, [strip].
     *
     * Only the keys we touched are reverted, so anything the player changed in
     * that file since survives. That is the one thing the whole-file snapshot
     * restore cannot promise.
     */
    fun unpatch(lines: List<String>): List<String>? {
        if (!hasRecord(lines)) return null

        // Per key rather than one entry per key: a .cfg may legitimately name
        // the same key twice, and a patch only ever replaced the first of them.
        // Restoring in the same order restores the same one.
        val replaced = LinkedHashMap<String, ArrayDeque<String>>()
        val added = LinkedHashMap<String, Int>()
        for (line in lines) {
            when {
                line.startsWith(REPLACED) -> {
                    val original = line.substring(REPLACED.length)
                    replaced.getOrPut(keyOf(original)) { ArrayDeque() }.addLast(original)
                }
                line.startsWith(ADDED) -> {
                    val key = line.substring(ADDED.length).trim()
                    added[key] = (added[key] ?: 0) + 1
                }
            }
        }

        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (isOurLine(line)) continue
            val key = keyOf(line)
            val original = replaced[key]?.removeFirstOrNull()
            if (original != null) {
                out.add(original)
                continue
            }
            val insertions = added[key] ?: 0
            if (insertions > 0) {
                added[key] = insertions - 1
                continue
            }
            out.add(line)
        }
        return out
    }

    /**
     * The last resort: delete every key this tool has ever written, and our own
     * comment lines with them.
     *
     * For a file with neither a record nor a usable snapshot this is the only
     * way out, and it is a sound one *because these are override files*. An
     * override is a layer on top of retroarch.cfg; a key that is not in the
     * layer falls through to the player's real setting. So the cost is losing
     * whatever the repack itself had set for those ten keys - a bezel toggle, a
     * shader switch - against the alternative of RetroArch drawing into a
     * corner forever. It is tried last for exactly that reason.
     *
     * What it must never do is leave HALF a setting behind: taking the viewport
     * size out and leaving the offset in is not a smaller change than taking
     * both, it is a worse one. See [VIEWPORT_ORPHANS].
     */
    fun strip(lines: List<String>): List<String> {
        val losesViewportSize = lines.any {
            keyOf(it) == "custom_viewport_width" || keyOf(it) == "custom_viewport_height"
        }
        val drop = if (losesViewportSize) KEYS + VIEWPORT_ORPHANS else KEYS
        return lines.filterNot { isOurLine(it) || keyOf(it) in drop }
    }

    /** `foo = "bar"` -> `foo`. Blank and comment lines yield no real key. */
    private fun keyOf(line: String): String = line.substringBefore('=').trim()

    private fun assign(key: String, value: String): String = "$key = \"$value\""

    private fun isOurLine(line: String): Boolean =
        line.trim() in MARKERS || line.startsWith(REPLACED) || line.startsWith(ADDED)
}
