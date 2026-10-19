package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chunking for ML Kit, which degrades on long input. The point is that the model never sees
 * a fragment: splits land on sentence boundaries, and the caller rejoins with spaces, so the
 * seams are invisible in the UI.
 */
class TranslateChunksTest {

    @Test
    fun shortParagraphIsASingleChunk() {
        assertEquals(listOf("Hallo Welt."), Translate.chunks("Hallo   Welt.", "de"))
    }

    @Test
    fun paragraphWhitespaceIsFlattenedForTheModel() {
        // Verse keeps newlines for display; the model should see flowing text.
        assertEquals(
            listOf("Sieh einmal, hier steht er, pfui, der Kritzel-Karl!"),
            Translate.chunks("Sieh einmal, hier steht er,\npfui, der Kritzel-Karl!", "de"),
        )
    }

    @Test
    fun longParagraphSplitsOnlyAtSentenceBoundaries() {
        val sentence = "Der Musterfritz, der Musterfritz, das war ein arger Wirrkopf! "
        val text = sentence.repeat(12)
        val chunks = Translate.chunks(text, "de")
        assertTrue("expected more than one chunk", chunks.size > 1)
        for (c in chunks.dropLast(1)) {
            assertTrue("every chunk should be within budget: ${c.length}", c.length <= 420)
            assertTrue(
                "a chunk must not end mid-sentence: ...${c.takeLast(30)}",
                c.last() in listOf('!', '?', '.', '»', '«', '"'),
            )
        }
    }

    @Test
    fun everyCharacterSurvivesChunking() {
        // Nothing may be dropped, or the translation silently loses text.
        val text = ("Er ging nach Hause. Sie blieb hier. Und dann? ").repeat(30)
        val joined = Translate.chunks(text, "de").joinToString(" ")
        assertEquals(text.trim(), joined)
    }
}
