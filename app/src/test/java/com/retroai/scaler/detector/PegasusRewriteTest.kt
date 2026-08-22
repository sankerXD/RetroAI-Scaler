package com.retroai.scaler.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch-file rewriter, on the desktop.
 *
 * This is the code that edits how a player's entire game library starts. A
 * mistake here does not look like a worse picture, it looks like nothing
 * launching any more - so it gets checked before it ever reaches a device,
 * and by something that can fail.
 *
 * The fixture reproduces the shape of the real files rather than a tidy
 * imagined one: CRLF endings, a collection-level launch block, a per-game
 * override naming a different core, and an unrelated line that happens to
 * mention a core filename in prose.
 */
class PegasusRewriteTest {

    private val crlf = "\r\n"

    private val fixture = listOf(
        "collection: GBA",
        "extensions: gba, 7z, zip",
        "launch: am start --user 0",
        "  -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture",
        "  -e ROM {file.path}",
        "  -e LIBRETRO /data/data/com.retroarch.aarch64/cores/vbam_libretro_android.so",
        "  -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
        "  --activity-clear-top",
        "",
        "game: A Game",
        "file: a.zip",
        "description: mentions vbam_libretro_android.so in prose, which is not a launch line",
        "",
        "game: Another",
        "file: b.zip",
        "launch: ",
        "  am start --user 0",
        "  -e LIBRETRO /data/data/com.retroarch.aarch64/cores/gpsp_libretro_android.so",
        "  --activity-clear-top"
    ).joinToString(crlf) + crlf

    private fun toShim(name: String): String? =
        PegasusConfigManager.shimNameOf(name)

    @Test
    fun `finds every launch token and only launch tokens`() {
        val names = PegasusConfigManager.coreNamesIn(fixture)
        // The prose mention must not be picked up: it is not preceded by the
        // launch token, and treating it as one would rewrite a description.
        assertEquals(listOf("vbam_libretro_android.so", "gpsp_libretro_android.so"), names)
    }

    @Test
    fun `rewrites per token, not per file`() {
        val (out, edits) = PegasusConfigManager.rewriteText(fixture) { toShim(it) }
        assertEquals(2, edits)
        assertTrue(out.contains("cores/vbam_shim_libretro_android.so"))
        assertTrue(out.contains("cores/gpsp_shim_libretro_android.so"))
    }

    @Test
    fun `leaves every other byte alone, CRLF included`() {
        val (out, _) = PegasusConfigManager.rewriteText(fixture) { toShim(it) }
        // Exactly the length difference of the two insertions, and not one byte
        // more: no re-indenting, no reordering, no newline normalisation.
        assertEquals(fixture.length + 2 * "_shim".length, out.length)
        assertEquals(fixture.count { it == '\r' }, out.count { it == '\r' })
        assertEquals(fixture.count { it == '\n' }, out.count { it == '\n' })
        assertTrue(out.contains("description: mentions vbam_libretro_android.so in prose"))
    }

    @Test
    fun `converting and restoring returns the original bytes`() {
        val (applied, _) = PegasusConfigManager.rewriteText(fixture) { toShim(it) }
        val (restored, edits) = PegasusConfigManager.rewriteText(applied) { name ->
            if (name.contains("_shim_")) PegasusConfigManager.realCoreOf(name) else null
        }
        assertEquals(2, edits)
        assertEquals(fixture, restored)
    }

    @Test
    fun `a null replacement leaves that token untouched`() {
        // Half the shims installed is the normal state during activation, and
        // the cores without one must be left pointing at the real core.
        val (out, edits) = PegasusConfigManager.rewriteText(fixture) { name ->
            if (name.startsWith("vbam")) toShim(name) else null
        }
        assertEquals(1, edits)
        assertTrue(out.contains("cores/vbam_shim_libretro_android.so"))
        assertTrue(out.contains("cores/gpsp_libretro_android.so"))
    }

    @Test
    fun `applying twice changes nothing the second time`() {
        val (once, _) = PegasusConfigManager.rewriteText(fixture) { toShim(it) }
        val (twice, edits) = PegasusConfigManager.rewriteText(once) { toShim(it) }
        // shimNameOf refuses a name that is already a shim, so a re-run is a
        // no-op rather than producing vbam_shim_shim_libretro_android.so.
        assertEquals(0, edits)
        assertEquals(once, twice)
    }

    @Test
    fun `a converted line still names the core the device uses`() {
        // The rescan lesson: an already-converted line describes a real core,
        // and dropping it loses track of cores already covered.
        assertEquals(
            "vbam_libretro_android.so",
            PegasusConfigManager.realCoreOf("vbam_shim_libretro_android.so")
        )
        assertEquals(
            "genesis_plus_gx_libretro_android.so",
            PegasusConfigManager.realCoreOf("genesis_plus_gx_shim_libretro_android.so")
        )
    }

    @Test
    fun `names that are not cores get no shim`() {
        assertNull(PegasusConfigManager.shimNameOf("vbam_shim_libretro_android.so"))
        assertNull(PegasusConfigManager.shimNameOf("vbam_libretro.so"))
        assertNull(PegasusConfigManager.shimNameOf("something.else"))
    }

    @Test
    fun `a file with no launch line is returned unchanged`() {
        val plain = "collection: Test${crlf}game: X${crlf}"
        val (out, edits) = PegasusConfigManager.rewriteText(plain) { toShim(it) }
        assertEquals(0, edits)
        assertEquals(plain, out)
    }
}
