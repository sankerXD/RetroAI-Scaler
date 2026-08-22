package com.retroai.scaler.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RetroArch override patch, on the desktop.
 *
 * The failure this guards against is not a worse picture: it is a player's
 * RetroArch left drawing into the bottom-right corner of its own window, on
 * some console they were not even playing when it happened, with every screen
 * in the app reporting that everything was restored. That bug shipped, so the
 * round trip back off a patched file gets checked here rather than on a
 * handheld an hour away.
 */
class RetroArchOverridePatchTest {

    /**
     * The shape of a real repack override: some keys we are about to take over,
     * some settings that are none of our business, a comment, a blank line.
     */
    private val original = listOf(
        "# repack defaults",
        "aspect_ratio_index = \"22\"",
        "video_scale_integer = \"false\"",
        "input_overlay_enable = \"true\"",
        "input_overlay = \"/storage/emulated/0/RetroArch/overlays/gba.cfg\"",
        "",
        "video_font_size = \"32.000000\""
    )

    private val viewport = linkedMapOf(
        "aspect_ratio_index" to "23",
        "video_scale_integer" to "true",
        "custom_viewport_width" to "240",
        "custom_viewport_height" to "160",
        "video_viewport_bias_x" to "1.000000",
        "video_viewport_bias_y" to "1.000000",
        "input_overlay_enable" to "false",
        "video_filter" to "",
        "video_smooth" to "false"
    )

    private fun valueOf(lines: List<String>, key: String): String? =
        lines.firstOrNull { it.substringBefore('=').trim() == key }
            ?.substringAfter('=')?.trim()?.trim('"')

    @Test
    fun `a patch takes over our keys and leaves everything else alone`() {
        val patched = RetroArchOverridePatch.patch(original, viewport)
        assertEquals("23", valueOf(patched, "aspect_ratio_index"))
        assertEquals("false", valueOf(patched, "input_overlay_enable"))
        assertEquals("240", valueOf(patched, "custom_viewport_width"))
        // Not ours, not touched - not even reordered.
        assertEquals(
            "/storage/emulated/0/RetroArch/overlays/gba.cfg",
            valueOf(patched, "input_overlay")
        )
        assertEquals("32.000000", valueOf(patched, "video_font_size"))
        assertTrue(patched.contains("# repack defaults"))
        assertTrue(RetroArchOverridePatch.isOurs(patched))
    }

    @Test
    fun `patch then unpatch returns the file it started from`() {
        val patched = RetroArchOverridePatch.patch(original, viewport)
        assertEquals(original, RetroArchOverridePatch.unpatch(patched))
    }

    @Test
    fun `patching twice still restores the ORIGINAL, not the first patch`() {
        // The whole correctness argument for recording only on first touch. A
        // second write recording its own values as the originals would be the
        // poisoned-snapshot bug moved inside the file.
        val once = RetroArchOverridePatch.patch(original, viewport)
        val twice = RetroArchOverridePatch.patch(
            once,
            viewport + linkedMapOf("video_viewport_bias_x" to "0.000000")
        )
        assertEquals("0.000000", valueOf(twice, "video_viewport_bias_x"))
        assertEquals(original, RetroArchOverridePatch.unpatch(twice))
    }

    @Test
    fun `an unrelated edit made between patch and restore survives`() {
        // The reason the record beats the snapshot: a whole-file rollback would
        // silently undo this too, and it is the player's own setting.
        val patched = RetroArchOverridePatch.patch(original, viewport)
        val edited = patched.map {
            if (it.startsWith("video_font_size")) "video_font_size = \"48.000000\"" else it
        }
        val restored = RetroArchOverridePatch.unpatch(edited)!!
        assertEquals("48.000000", valueOf(restored, "video_font_size"))
        assertEquals("22", valueOf(restored, "aspect_ratio_index"))
    }

    @Test
    fun `a key we added is deleted, not reset to a guess`() {
        val patched = RetroArchOverridePatch.patch(original, viewport)
        val restored = RetroArchOverridePatch.unpatch(patched)!!
        // The file never had a custom viewport; leaving one behind at any value
        // is what keeps RetroArch in the corner.
        assertNull(valueOf(restored, "custom_viewport_width"))
        assertNull(valueOf(restored, "video_viewport_bias_x"))
        assertFalse(RetroArchOverridePatch.isOurs(restored))
    }

    @Test
    fun `a file we never touched is not ours and has no record`() {
        assertFalse(RetroArchOverridePatch.isOurs(original))
        assertNull(RetroArchOverridePatch.unpatch(original))
    }

    @Test
    fun `a file from an older build has no record and falls back to stripping`() {
        // What an older build wrote: the marker, our keys, no record lines.
        val old = listOf(RetroArchOverridePatch.MARKER) +
            original.map {
                if (it.startsWith("aspect_ratio_index")) "aspect_ratio_index = \"23\"" else it
            } +
            listOf("custom_viewport_width = \"240\"", "video_viewport_bias_x = \"1.000000\"")

        assertTrue(RetroArchOverridePatch.isOurs(old))
        assertNull(RetroArchOverridePatch.unpatch(old))

        val stripped = RetroArchOverridePatch.strip(old)
        assertFalse(RetroArchOverridePatch.isOurs(stripped))
        // Our keys are gone, so the override layer falls through to the
        // player's real retroarch.cfg - which is the point.
        assertNull(valueOf(stripped, "custom_viewport_width"))
        assertNull(valueOf(stripped, "aspect_ratio_index"))
        // Theirs is untouched.
        assertEquals(
            "/storage/emulated/0/RetroArch/overlays/gba.cfg",
            valueOf(stripped, "input_overlay")
        )
        assertTrue(stripped.contains("# repack defaults"))
    }

    @Test
    fun `the legacy marker is still recognised`() {
        // Renaming the project once already turned "only restore what we
        // marked" into "never restore that file". Detection accepts every
        // marker this tool has ever written.
        val legacy = listOf(RetroArchOverridePatch.LEGACY_MARKER, "aspect_ratio_index = \"23\"")
        assertTrue(RetroArchOverridePatch.isOurs(legacy))
        assertFalse(RetroArchOverridePatch.isOurs(RetroArchOverridePatch.strip(legacy)))
    }

    @Test
    fun `a key that appears twice restores the one that was replaced`() {
        val twice = listOf(
            "aspect_ratio_index = \"22\"",
            "video_font_size = \"32.000000\"",
            "aspect_ratio_index = \"19\""
        )
        val patched = RetroArchOverridePatch.patch(twice, linkedMapOf("aspect_ratio_index" to "23"))
        // Only the first was taken over, so only the first comes back.
        assertEquals(twice, RetroArchOverridePatch.unpatch(patched))
    }

    @Test
    fun `stripping is idempotent`() {
        val patched = RetroArchOverridePatch.patch(original, viewport)
        val once = RetroArchOverridePatch.strip(patched)
        assertEquals(once, RetroArchOverridePatch.strip(once))
    }
}
