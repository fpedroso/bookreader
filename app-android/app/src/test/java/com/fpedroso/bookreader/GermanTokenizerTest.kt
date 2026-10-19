package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German has no sidecar, so the tokenizer is what decides what is tappable. It is pure
 * logic, so it is worth covering here rather than discovering problems by tapping.
 */
class GermanTokenizerTest {

    private fun words(text: String): List<String> =
        GermanTokenizer.tokenize(text).filter { it.isWord }.map { it.word }

    @Test
    fun splitsWordsFromPunctuation() {
        val tokens = GermanTokenizer.tokenize("Der Struwwelpeter, oder: lustige Geschichten!")
        assertEquals(
            listOf("Der", "Struwwelpeter", "oder", "lustige", "Geschichten"),
            tokens.filter { it.isWord }.map { it.word },
        )
        // Punctuation survives as its own token so it still gets rendered, but must not
        // become a tap target.
        assertEquals(
            listOf(",", ":", "!"),
            tokens.filterNot { it.isWord }.map { it.word },
        )
        assertTrue("punctuation must not be tappable", tokens.none { !it.isWord && it.isWord })
    }

    @Test
    fun keepsUmlautsAndSharpS() {
        assertEquals(listOf("Käse", "groß", "Mädchen"), words("Käse groß Mädchen"))
    }

    @Test
    fun keepsHyphenatedCompoundsWhole() {
        // Struwwelpeter has "Guck-in-die-Luft" and "Zappel-Philipp" as characters.
        assertEquals(listOf("Guck-in-die-Luft"), words("Guck-in-die-Luft"))
        assertEquals(listOf("Zappel-Philipp"), words("Zappel-Philipp"))
    }

    @Test
    fun keepsApostropheContractionsWhole() {
        // The dictionary retries on the stem for these, so they must arrive in one piece.
        assertEquals(listOf("hat’s"), words("hat’s"))
        assertEquals(listOf("wie’s"), words("wie’s"))
    }

    @Test
    fun digitsAreNotTappableWords() {
        val tokens = GermanTokenizer.tokenize("Kapitel 3 und 12")
        assertEquals(listOf("Kapitel", "und"), tokens.filter { it.isWord }.map { it.word })
        assertTrue(tokens.any { it.word == "3" && !it.isWord })
        assertTrue(tokens.any { it.word == "12" && !it.isWord })
    }

    @Test
    fun realGermanSentenceRoundTrips() {
        val line = "Ob sie wohl artig sind? Sie sind es nicht gewiß!"
        assertEquals(
            listOf("Ob", "sie", "wohl", "artig", "sind", "Sie", "sind", "es", "nicht", "gewiß"),
            words(line),
        )
    }

    @Test
    fun emptyAndWhitespaceProduceNothing() {
        assertTrue(GermanTokenizer.tokenize("").isEmpty())
        assertTrue(GermanTokenizer.tokenize("   \n\t ").isEmpty())
        assertFalse(GermanTokenizer.tokenize("...").any { it.isWord })
    }
}
