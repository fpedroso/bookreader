package com.fpedroso.bookreader

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
}
