package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German sentence splitting, which is the hard case: `.` doubles as the abbreviation and
 * ordinal marker, so the naive splitter breaks on exactly the strings this book contains.
 */
class SentencesTest {

    private fun texts(s: String) = Sentences.splitGerman(s).map { it.text }

    @Test
    fun splitsOnTerminators() {
        assertEquals(
            listOf("Er ging nach Hause.", "Sie blieb hier.", "Und dann?"),
            texts("Er ging nach Hause. Sie blieb hier. Und dann?"),
        )
    }

    @Test
    fun abbreviationsDoNotEndSentences() {
        // Both of these are in Der Struwwelpeter and both break a naive splitter.
        assertEquals(
            listOf("Dr. Heinrich Hoffmann schrieb das Buch."),
            texts("Dr. Heinrich Hoffmann schrieb das Buch."),
        )
        assertEquals(
            listOf("Er kam um ca. 8 Uhr an, usw. und so fort."),
            texts("Er kam um ca. 8 Uhr an, usw. und so fort."),
        )
    }

    @Test
    fun ordinalsAndNumbersDoNotEndSentences() {
        // "564. Auflage" and "1. Januar" - capitalised nouns follow the dot, so the
        // lowercase test cannot catch these; the digit test must.
        assertEquals(listOf("564. Auflage erschien später."), texts("564. Auflage erschien später."))
        assertEquals(listOf("Am 1. Januar war es kalt."), texts("Am 1. Januar war es kalt."))
    }

    @Test
    fun initialsDoNotEndSentences() {
        assertEquals(
            listOf("La Monte H.P. Yarroll schrieb mit."),
            texts("La Monte H.P. Yarroll schrieb mit."),
        )
    }

    @Test
    fun lowercaseAfterTheDotMeansTheSentenceContinues() {
        // z.B. is not in the list here on purpose: the lowercase signal alone must be
        // enough, because that is the strongest rule in the literature.
        assertEquals(listOf("Er kam, z.T. sehr spät an."), texts("Er kam, z.T. sehr spät an."))
    }

    @Test
    fun closersBelongToTheSentenceTheyClose() {
        assertEquals(
            listOf("»Komm her!«", "Dann ging er."),
            texts("»Komm her!« Dann ging er."),
        )
    }

    @Test
    fun speechAttributionContinuesTheSentence() {
        // A terminator inside quotes followed by a comma is not a sentence end: the
        // attribution is part of the same sentence.
        assertEquals(
            listOf("»Komm her!«, rief er, und sie kam."),
            texts("»Komm her!«, rief er, und sie kam."),
        )
    }

    @Test
    fun terminatorsRunsAreOneBreak() {
        assertEquals(listOf("Wirklich?!", "Ja."), texts("Wirklich?! Ja."))
    }

    @Test
    fun verseNewlinesAreNotSentenceBreaks() {
        // Verse lines are mid-sentence breaks. The splitter must join them and split on
        // punctuation, otherwise every line becomes a context-free fragment.
        val stanza = "Sieh einmal, hier steht er,\npfui, der Struwwelpeter!"
        assertEquals(listOf("Sieh einmal, hier steht er, pfui, der Struwwelpeter!"), texts(stanza))
    }

    @Test
    fun shortFragmentsAreJoinedToTheNextSentence() {
        // A bare interjection has no referent of its own; ML Kit translates it badly in
        // isolation, so it rides along with the next sentence.
        val out = texts("Pfui! Der Struwwelpeter war ein ungezogenes Kind.")
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("Pfui!"))
    }

    @Test
    fun trailingFragmentIsKept() {
        // Text after the last terminator is still a unit a reader can ask about.
        assertEquals(listOf("Er ging.", "Und dann kam"), texts("Er ging. Und dann kam"))
    }

    @Test
    fun chineseSplitsOnFullWidthTerminators() {
        val out = Sentences.splitChinese("我叫小明。我是中国人！你呢？")
        assertEquals(listOf("我叫小明。", "我是中国人！", "你呢？"), out.map { it.text })
    }

    @Test
    fun chineseCommasAreNotSentenceEnds() {
        assertEquals(1, Sentences.splitChinese("爸爸、妈妈、姐姐和我，都住在北京。").size)
    }

    @Test
    fun chineseClosersAreAbsorbed() {
        assertEquals(1, Sentences.splitChinese("他说：“我来了。”").size)
    }

    // ------------------------------------------------------- token -> sentence

    @Test
    fun mapsATappedTokenToItsSentence() {
        val text = "Er ging nach Hause. Sie blieb hier."
        val tokens = GermanTokenizer.tokenize(text)
        val idx = tokens.indexOfFirst { it.word == "blieb" }
        assertTrue(idx >= 0)
        val s = Sentences.sentenceForToken(text, tokens, idx, "de")
        assertNotNull(s)
        assertEquals("Sie blieb hier.", s!!.text)
    }

    @Test
    fun mappingSurvivesVerseLineBreaks() {
        // Token offsets are computed on whitespace-collapsed text, so a newline inside the
        // block cannot shift the mapping.
        val text = "Sieh einmal, hier steht er,\npfui, der Struwwelpeter!"
        val tokens = GermanTokenizer.tokenize(text)
        val idx = tokens.indexOfFirst { it.word == "Struwwelpeter" }
        assertTrue(idx >= 0)
        val s = Sentences.sentenceForToken(text, tokens, idx, "de")
        assertNotNull(s)
        assertTrue(s!!.text.contains("Struwwelpeter"))
    }
}
