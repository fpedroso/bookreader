package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the translation modal says about languages, and the decision not to call the model at
 * all when the sentence is already English.
 *
 * Both are pure functions, so they are tested here rather than on a device: the naming comes
 * from the platform's own CLDR data, and the "already English" rule is what a German book's
 * English front matter hits.
 */
class LanguagePairTest {

    @Test
    fun pairNamesTheSourceInEnglish() {
        assertEquals("German → English", languagePair("de"))
    }

    @Test
    fun namesAreDerivedFromTheCodeRatherThanATable() {
        // Any code works, including ones this app has no other handling for.
        assertEquals("French → English", languagePair("fr"))
        assertEquals("Japanese → English", languagePair("ja"))
        assertEquals("Japanese", languageName("ja"))
    }

    @Test
    fun regionSubtagsStillNameTheLanguage() {
        assertEquals("German → English", languagePair("de-DE"))
    }

    @Test
    fun nothingToTranslateWhenTheSourceIsTheTarget() {
        // The front-matter case: an English block inside a German book. No model call, and so
        // no "English → English" pair to show either.
        assertTrue(alreadyTarget("en"))
        assertTrue(alreadyTarget("EN"))
        assertTrue(alreadyTarget("en-US"))
        assertFalse(alreadyTarget("de"))
    }

    @Test
    fun anAlreadyEnglishSentenceHasNoPair() {
        assertNull(languagePair("en"))
    }

    @Test
    fun noPairBeforeTheLanguageIsKnown() {
        // The resolution chain (or Language ID) has not answered yet.
        assertNull(languagePair(null))
        assertNull(languagePair(""))
        assertFalse(alreadyTarget(null))
    }
}
