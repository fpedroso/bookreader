package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
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
    fun titleAbbreviationsDoNotEndSentence() {
        // "Mr. und Mrs. Dursley ... waren stolz darauf." is one sentence. Without "mrs" in the
        // list it split after "Mrs.", which was invisible until sentences got borders.
        val s = Sentences.splitGerman(
            "Mr. und Mrs. Dursley im Ligusterweg Nummer 4 waren stolz darauf, ganz und gar normal zu sein."
        )
        assertEquals(1, s.size)
        assertTrue(s[0].text.startsWith("Mr. und Mrs."))
    }

    @Test
    fun trailingFragmentIsKept() {
        // Text after the last terminator is still a unit a reader can ask about.
        assertEquals(listOf("Er ging.", "Und dann kam"), texts("Er ging. Und dann kam"))
    }
}
