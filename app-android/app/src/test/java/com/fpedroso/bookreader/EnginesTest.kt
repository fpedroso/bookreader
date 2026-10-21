package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine registry and the cache namespace it defines.
 *
 * The engine-scoped key is the whole reason this file exists: two engines return different
 * translations for the same text, so a cache keyed on the text alone would hand ML Kit's answer back
 * when the reader switches to the cloud engine - and it would look like the new engine is bad, not
 * like the cache is wrong.
 */
class EnginesTest {

    @Test
    fun theSameTextUnderTwoEnginesGetsTwoKeys() {
        val text = "Der Hund läuft nach Hause."
        assertNotEquals(unitKey(Engine.MLKIT, text), unitKey(Engine.OPENROUTER, text))
    }

    @Test
    fun theSameInputsAlwaysGiveTheSameKey() {
        assertEquals(
            unitKey(Engine.OPENROUTER, "Bank", "Eine wirkliche Bank."),
            unitKey(Engine.OPENROUTER, "Bank", "Eine wirkliche Bank."),
        )
    }

    @Test
    fun differentTextNeverSharesAKey() {
        assertNotEquals(unitKey(Engine.MLKIT, "Er ging."), unitKey(Engine.MLKIT, "Sie ging."))
        assertNotEquals(unitKey(Engine.MLKIT, "Er ging."), unitKey(Engine.MLKIT, "Er ging!"))
    }

    @Test
    fun whitespaceStillDoesNotMatter() {
        // Inherited from translationKey: the extractors normalise whitespace differently, so the key
        // has to ignore it or a re-extracted book would re-translate itself.
        assertEquals(
            unitKey(Engine.MLKIT, "Er ging nach Hause."),
            unitKey(Engine.MLKIT, "Er  ging\nnach Hause. "),
        )
    }

    @Test
    fun contextIsPartOfTheKeyForEnginesThatReadIt() {
        val word = "Bank"
        val bench = "Eine wirkliche Bank."
        val money = "Das Geld liegt auf der Bank."
        assertNotEquals(
            unitKey(Engine.OPENROUTER, word, bench),
            unitKey(Engine.OPENROUTER, word, money),
        )
    }

    @Test
    fun contextIsNotPartOfTheKeyForMlKit() {
        // ML Kit's API only ever receives the text, so its answer cannot depend on the sentence -
        // and folding context into its key would only duplicate entries for the same word.
        val word = "Bank"
        assertEquals(
            unitKey(Engine.MLKIT, word, "Eine wirkliche Bank."),
            unitKey(Engine.MLKIT, word, "Das Geld liegt auf der Bank."),
        )
        assertFalse(Engine.MLKIT.usesContext)
        assertTrue(Engine.OPENROUTER.usesContext)
    }

    @Test
    fun aMissingOrUnknownStoredIdReadsAsTheDefault() {
        assertEquals(Engine.MLKIT, Engine.fromId(null))
        assertEquals(Engine.MLKIT, Engine.fromId(""))
        assertEquals(Engine.MLKIT, Engine.fromId("gpt-9"))
        assertEquals(Engine.OPENROUTER, Engine.fromId("openrouter"))
    }

    @Test
    fun anIdFromARemovedEngineFallsBackRatherThanCrashing() {
        // The Qwen engines were removed from the registry. A phone still holding one of their ids in
        // settings.json - written by an older build - must read as ML Kit, not fail on launch.
        assertEquals(Engine.MLKIT, Engine.fromId("qwen3-1.7b"))
        assertEquals(Engine.MLKIT, Engine.fromId("qwen3-4b"))
    }

    @Test
    fun onlyTheCloudEngineSendsTextAway() {
        assertEquals(2, Engine.entries.size)
        assertTrue(Engine.OPENROUTER.cloud)
        assertFalse(Engine.MLKIT.cloud)
    }

    @Test
    fun engineIdsAreDistinct() {
        val ids = Engine.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // An id is written to settings.json, so it must not be blank or carry whitespace.
        ids.forEach { assertTrue(it.isNotBlank() && it.none { c -> c.isWhitespace() }) }
    }

    @Test
    fun everyEngineLabelsWhatItCosts() {
        Engine.entries.forEach {
            assertTrue("${it.id} has an empty label", it.label.isNotBlank())
            assertTrue("${it.id} does not say what it costs", it.note.isNotBlank())
        }
    }

    @Test
    fun theDefaultEngineIsRunnable() {
        // A default that cannot run would make every fresh install fail on its first tap.
        assertTrue(Engine.MLKIT.runnable)
    }
}