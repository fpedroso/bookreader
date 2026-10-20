package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Word boundaries, and the tap-to-word resolver built on them.
 *
 * These are the cases the renderer depends on: every token has to index the sentence exactly as it
 * is drawn, because a tap arrives as a character offset from `getOffsetForPosition` and nothing
 * else lines the two up.
 */
class WordsTest {

    private fun words(text: String) = Words.tokenize(text).map { it.text }

    @Test
    fun splitsOnSpacesAndDropsThePunctuation() {
        assertEquals(
            listOf("Der", "Struwwelpeter", "war", "ein", "Junge"),
            words("Der Struwwelpeter war ein Junge."),
        )
    }

    @Test
    fun umlautsAndSharpSAreLetters() {
        assertEquals(listOf("Über", "die", "Straße", "gehen"), words("Über die Straße gehen"))
    }

    @Test
    fun anApostropheInsideAWordKeepsItWhole() {
        assertEquals(listOf("wie", "geht's"), words("wie geht's"))
    }

    @Test
    fun aHyphenBetweenLettersKeepsTheCompoundWhole() {
        // E-Mail is one word; so is the hyphenated name. Splitting either would offer the reader a
        // meaningless half ("E", "Guck") as the tap target.
        assertEquals(listOf("E-Mail", "und", "Guck-in-die-Luft"), words("E-Mail und Guck-in-die-Luft"))
    }

    @Test
    fun aTrailingHyphenIsNotPartOfTheWord() {
        assertEquals(listOf("Tag"), words("Tag -"))
    }

    @Test
    fun anEmDashSeparates() {
        // The `3—6 Jahre` shape: an em dash is not a hyphen, so it is a boundary, not a joiner.
        assertEquals(listOf("Jahre"), words("3—6Jahre"))
        assertEquals(listOf("Er", "ging"), words("Er—ging"))
    }

    @Test
    fun digitsAndQuotesAreNotWords() {
        assertEquals(listOf("Auflage"), words("564. Auflage"))
        assertEquals(listOf("Nein", "sagte", "er"), words("„Nein“, sagte er"))
    }

    @Test
    fun offsetsIndexTheOriginalString() {
        val text = "Der Junge heißt Fritz."
        for (w in Words.tokenize(text)) {
            assertEquals(w.text, text.substring(w.start, w.end))
        }
    }

    @Test
    fun atFindsTheWordUnderAnOffset() {
        // "Der Junge heißt Fritz." - J of Junge at 4, z of Fritz at 20, the full stop at 21.
        val text = "Der Junge heißt Fritz."
        assertEquals("Der", Words.at(text, 0)?.text)
        assertEquals("Junge", Words.at(text, 4)?.text)
        assertEquals("Junge", Words.at(text, 8)?.text)
        assertEquals("heißt", Words.at(text, 12)?.text)
        assertEquals("Fritz", Words.at(text, 20)?.text)
    }

    @Test
    fun atReturnsNothingForWhitespace() {
        // The reader view answers these with the whole sentence, so "no word here" has to be
        // distinguishable from "the neighbouring word" - otherwise a tap in the gap between two
        // words would silently pick one of them.
        val text = "Der Junge heißt Fritz."
        assertNull(Words.at(text, 3))
        assertNull(Words.at(text, 9))
        assertNull(Words.at(text, 15))
    }

    @Test
    fun theFinalFullStopIsNotAWord() {
        assertNull(Words.at("Der Junge heißt Fritz.", 21))
    }

    @Test
    fun atReturnsNothingPastTheEnd() {
        val text = "Der Junge"
        assertNull(Words.at(text, text.length))
        assertNull(Words.at(text, text.length + 40))
        assertNull(Words.at(text, -1))
    }

    @Test
    fun anEmptyStringHasNoWords() {
        assertEquals(emptyList<String>(), words(""))
        assertNull(Words.at("", 0))
    }
}
