package com.fpedroso.bookreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rows offer translation.
 *
 * Deliberately narrow for now - German books only - so that widening it later is a one-line change
 * with a failing test to read first. The per-block half matters just as much: a German novel's front
 * matter is routinely English, and offering to translate it can only ever answer "this is already in
 * English".
 */
class TranslationGateTest {

    @Test
    fun aGermanBookOffersTranslation() {
        assertTrue(translationOffered("de", null))
        assertTrue(translationOffered("de-DE", null))
        assertTrue(translationOffered("DE", null))
    }

    @Test
    fun otherLanguagesOfferNothing() {
        assertFalse(translationOffered("en", null))
        assertFalse(translationOffered("fr", null))
        assertFalse(translationOffered("zh", null))
    }

    @Test
    fun anUnknownBookLanguageOffersNothing() {
        // Never guess: a missing dc:language must not be read as "probably German". The import
        // hint is already folded into the value the caller passes here.
        assertFalse(translationOffered("", null))
        assertFalse(translationOffered(null, null))
    }

    @Test
    fun anEnglishBlockInsideAGermanBookOffersNothing() {
        assertFalse(translationOffered("de", "en"))
        assertFalse(translationOffered("de", "en-US"))
    }

    @Test
    fun anUndeclaredBlockFallsBackToTheBook() {
        // The block declares nothing, so the book's German is what governs - offered, and resolved
        // per block at tap time.
        assertTrue(translationOffered("de", null))
        assertTrue(translationOffered("de", ""))
    }

    @Test
    fun aGermanBlockInAGermanBookIsOffered() {
        assertTrue(translationOffered("de", "de"))
    }
}
