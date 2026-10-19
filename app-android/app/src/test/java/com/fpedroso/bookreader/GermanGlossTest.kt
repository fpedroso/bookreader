package com.fpedroso.bookreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [isPointerGloss], which decides whether a result's gloss actually defines the
 * word or merely redirects to another one ("plural of Haus" vs "house").
 *
 * It matters because the ranking in GermanDict.query depends on it: bucket 1 leads the
 * popup with a real meaning, bucket 3 defers a bare pointer. Getting this predicate wrong
 * is invisible in coverage numbers and only shows up as a misleading headline.
 */
class GermanGlossTest {

    @Test
    fun redirectOnlyGlossesArePointers() {
        val pointers = listOf(
            // Trailing colon is how the source actually spells the form_of glosses.
            "inflection of lustig:",
            "inflection of lustig",
            "plural of Geschichte",
            "genitive of ich",
            "nominative/accusative/genitive plural of Haus",
            "first/third-person singular preterite of gehen",
            "past participle of gehen",
            "comparative of gut",
            // Redirect with no meaning attached, from the real `das` data.
            "nominative/accusative neuter singular of der",
            "obsolete spelling of dass",
        )
        for (g in pointers) {
            assertTrue("expected a pointer: $g", isPointerGloss(g))
        }
    }

    @Test
    fun redirectThatCarriesAMeaningIsNotAPointer() {
        // Also real `das` data. Opening with a grammar phrase but ending in a definition
        // after the colon, so it must stay in the meaning bucket - if this were demoted,
        // tapping `das` would lead with the bare lemma `der` again.
        assertFalse(isPointerGloss("nominative/accusative neuter singular of der: the"))
        assertFalse(isPointerGloss("nominative masculine singular definite article, the"))
    }

    @Test
    fun definitionsContainingOfAreNotPointers() {
        val real = listOf(
            "my",
            "funny, humorous",
            "to go, to walk",
            "house",
            // Contains " of " but describes meaning, so it must not be demoted.
            "a kind of bread",
            "part of a building",
            // A leading "of" is not the redirect construction.
            "of or pertaining to",
            // Empty/blank is not a pointer in itself; the caller demotes it separately,
            // via `all` over an empty list. This guards that the predicate alone stays
            // false so the two rules cannot disagree.
            "",
            "   ",
        )
        for (g in real) {
            assertFalse("expected a real definition: $g", isPointerGloss(g))
        }
    }
}
