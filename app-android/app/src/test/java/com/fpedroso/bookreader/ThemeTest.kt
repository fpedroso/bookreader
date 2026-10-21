package com.fpedroso.bookreader

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored theme id.
 *
 * The id goes into `settings.json`, which is the reader's own file on disk - so an unknown value
 * has to resolve to a palette rather than throw or leave the window unthemed, and a settings file
 * written before the setting existed (no key at all) has to keep loading. Same rule as
 * [Engine.fromId], and the reason it is worth a test rather than a comment: a reader who upgrades
 * keeps their API key, model and endpoint, and gains the default palette.
 */
class ThemeTest {

    @Test
    fun idsAreDistinct() {
        val ids = ThemeMode.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun unknownIdFallsBackToDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromId("chartreuse"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromId(""))
        assertEquals(ThemeMode.DARK, ThemeMode.fromId(null))
    }

    @Test
    fun everyIdRoundTrips() {
        for (m in ThemeMode.entries) assertEquals(m, ThemeMode.fromId(m.id))
    }

    @Test
    fun settingsWrittenBeforeTheSettingExistedStillLoad() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            AppSettings.serializer(),
            """{"engine":"openrouter","apiKey":"k","apiModel":"some/model"}""",
        )
        assertEquals(ThemeMode.DARK.id, old.theme)
        // and nothing else is lost in the upgrade
        assertEquals("k", old.apiKey)
        assertEquals("some/model", old.apiModel)
    }

    // ------------------------------------------------------------- frequency shading

    /** Stock Material 3 backgrounds and body-text colours for the two schemes. */
    private val darkPage = Color(0xFF141218)
    private val lightPage = Color(0xFFFFFBFE)
    private val darkBase = Color(0xFFE6E0E9)
    private val lightBase = Color(0xFF1D1B20)

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Per-channel difference out of 255 - how far apart two tones look. */
    private fun step(a: Color, b: Color): Int = listOf(
        a.red to b.red, a.green to b.green, a.blue to b.blue,
    ).maxOf { (x, y) -> Math.round(Math.abs(x - y) * 255f) }

    @Test
    fun everyBandExceptConstantHasATintInBothThemes() {
        // The bug this exists for: the reader built its colour map by hand, and when the fifth band
        // was added to the ladder and the palette but NOT to that map, the UNCOMMON words rendered as
        // ordinary text - which reads as "this word repeats constantly". A missing tone is not
        // cosmetic, and it is invisible to every other test in this file.
        for (dark in listOf(true, false)) {
            for (b in Band.entries) {
                if (b == Band.CONSTANT) {
                    assertNull("constant must stay unshaded", tintFor(b, dark))
                } else {
                    assertNotNull("$b has no tint (dark=$dark)", tintFor(b, dark))
                }
            }
        }
    }

    @Test
    fun theFourStepsAreDistinctAndFadeInOrder() {
        for (dark in listOf(true, false)) {
            val ramp = listOf(Band.FREQUENT, Band.OCCASIONAL, Band.UNCOMMON, Band.RARE)
                .map { tintFor(it, dark)!! }
            assertEquals("four bands must be four colours", 4, ramp.toSet().size)
            val lums = ramp.map { luminance(it) }
            val ordered =
                if (dark) lums.zipWithNext().all { (a, b) -> a > b }
                else lums.zipWithNext().all { (a, b) -> a < b }
            assertTrue("steps must get fainter in order (dark=$dark): $lums", ordered)
        }
    }

    @Test
    fun theShadesAreCloseEnoughToReadAsAGentleRamp() {
        // Felipe's brief: "keep the shades closer than it was before, less change, but still
        // noticeable". Measured per channel against the ordinary text colour: 15/255 is the floor for
        // noticeable, and 40/255 is the ceiling - the previous ramp's first step was 41, which is
        // exactly the "before" this improved on. Base colours are stock M3 onSurface, so this doubles
        // as a canary if a Material update moves them.
        for (dark in listOf(true, false)) {
            val tones = listOf(
                if (dark) darkBase else lightBase,
                tintFor(Band.FREQUENT, dark)!!,
                tintFor(Band.OCCASIONAL, dark)!!,
                tintFor(Band.UNCOMMON, dark)!!,
                tintFor(Band.RARE, dark)!!,
            )
            for ((a, b) in tones.zipWithNext()) {
                val d = step(a, b)
                assertTrue("step of $d is too small to notice (dark=$dark)", d >= 15)
                assertTrue("step of $d is too big a jump (dark=$dark)", d <= 40)
            }
        }
    }

    @Test
    fun theFaintestBandStaysReadable() {
        // The faint band is around a fifth of the running text, so it has to be READ, not merely
        // noticed: 4:1 against the page is the floor this was designed to.
        assertTrue(contrast(tintFor(Band.RARE, true)!!, darkPage) >= 4.0)
        assertTrue(contrast(tintFor(Band.RARE, false)!!, lightPage) >= 4.0)
    }
}
