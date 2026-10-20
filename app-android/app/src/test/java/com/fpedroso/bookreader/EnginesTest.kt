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
 * when the reader switches to a local model - and it would look like the new model is bad, not like
 * the cache is wrong.
 */
class EnginesTest {

    @Test
    fun theSameTextUnderTwoEnginesGetsTwoKeys() {
        val text = "Der Hund läuft nach Hause."
        assertNotEquals(unitKey(Engine.MLKIT, text), unitKey(Engine.QWEN_1_7B, text))
        assertNotEquals(unitKey(Engine.MLKIT, text), unitKey(Engine.QWEN_4B, text))
        assertNotEquals(unitKey(Engine.QWEN_1_7B, text), unitKey(Engine.QWEN_4B, text))
    }

    @Test
    fun theSameInputsAlwaysGiveTheSameKey() {
        assertEquals(
            unitKey(Engine.QWEN_1_7B, "Bank", "Eine wirkliche Bank."),
            unitKey(Engine.QWEN_1_7B, "Bank", "Eine wirkliche Bank."),
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
            unitKey(Engine.QWEN_1_7B, word, bench),
            unitKey(Engine.QWEN_1_7B, word, money),
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
        assertTrue(Engine.QWEN_1_7B.usesContext)
    }

    @Test
    fun aMissingOrUnknownStoredIdReadsAsTheDefault() {
        assertEquals(Engine.MLKIT, Engine.fromId(null))
        assertEquals(Engine.MLKIT, Engine.fromId(""))
        assertEquals(Engine.MLKIT, Engine.fromId("gpt-9"))
        assertEquals(Engine.QWEN_4B, Engine.fromId("qwen3-4b"))
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