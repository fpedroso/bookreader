package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Translate more": growing the open unit by the next sentence in the same paragraph.
 *
 * The reader-facing case is the fragment the structural splitter leaves at a paragraph start.
 * `Mr. und Mrs.` is one unit (there is no abbreviation list - see [Sentences]), and it translates
 * badly alone because its verb and object are in the next sentence. One press composes the two
 * into the sentence the reader actually wanted.
 */
class TranslationUnitTest {

    // The split of this paragraph is asserted in [SentencesTest.honourificNowSplitsBecauseThereIsNoList].
    private val sentences = listOf(
        "Mr. und Mrs.",
        "Beispiel in der Gartenstraße 4 waren stolz darauf, ganz und gar gewöhnlich zu sein, sehr stolz sogar.",
    )

    @Test
    fun aFreshSpanIsTheTappedSentenceAlone() {
        assertEquals("Mr. und Mrs.", unitSource(sentences, UnitSpan(0, 1)))
    }

    @Test
    fun extendingGrowsByOneSentenceAndJoinsWithASingleSpace() {
        val span = nextSpan(sentences, UnitSpan(0, 1))!!
        assertEquals(UnitSpan(0, 2), span)
        assertEquals(
            "Mr. und Mrs. Beispiel in der Gartenstraße 4 waren stolz darauf, ganz und gar " +
                "gewöhnlich zu sein, sehr stolz sogar.",
            unitSource(sentences, span),
        )
    }

    @Test
    fun pressingAgainComposesOneMoreSentenceEachTime() {
        // Each press extends by exactly one, so a two-press unit is three sentences.
        val three = listOf("Er ging.", "Dann kam sie.", "Und blieb.")
        var span = UnitSpan(0, 1)
        span = nextSpan(three, span)!!
        assertEquals(UnitSpan(0, 2), span)
        assertEquals("Er ging. Dann kam sie.", unitSource(three, span))
        span = nextSpan(three, span)!!
        assertEquals(UnitSpan(0, 3), span)
        assertEquals("Er ging. Dann kam sie. Und blieb.", unitSource(three, span))
    }

    @Test
    fun noFollowingSentenceInTheParagraphMeansNoExtension() {
        // Null at the last sentence, which is what hides "Translate more" instead of showing an
        // action that would do nothing.
        assertNull(nextSpan(sentences, UnitSpan(0, 2)))
        assertNull(nextSpan(sentences, UnitSpan(1, 1)))
    }

    @Test
    fun extendingChangesTheCacheKey() {
        // The whole point of the text-hash key: the joined unit is a different string, so it can
        // never be served the fragment's translation. A refactor that reused the shorter key
        // would fail here.
        val fragment = unitSource(sentences, UnitSpan(0, 1))
        val joined = unitSource(sentences, nextSpan(sentences, UnitSpan(0, 1))!!)
        assertNotEquals(translationKey(fragment), translationKey(joined))
    }

    @Test
    fun theSameSpanAlwaysHashesToTheSameKey() {
        // Re-pressing to a span already translated re-hashes the same text, so it hits the cache
        // rather than translating again - and the shorter unit keeps its own separate entry.
        val joined = unitSource(sentences, UnitSpan(0, 2))
        assertEquals(translationKey(joined), translationKey(unitSource(sentences, UnitSpan(0, 2))))
        val fragment = unitSource(sentences, UnitSpan(0, 1))
        assertNotEquals(translationKey(fragment), translationKey(joined))
    }
}
