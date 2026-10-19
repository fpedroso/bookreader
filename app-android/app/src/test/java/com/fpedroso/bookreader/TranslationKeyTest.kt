package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The property that stops a repeat of the stale-translation bug: the key a translation is cached
 * under must depend on the text being translated and on nothing else.
 *
 * This is what the old `"<blockhash>#<index>"` scheme got wrong. The block hash identified the
 * paragraph and the index identified a piece *within* that paragraph, so the pair was stable
 * only while the splitter was stable. Removing the abbreviation list changed where that
 * paragraph split, and index 0 - an unchanged key - silently became a different piece of text.
 * The cache returned the old unit's translation, and tapping `Mr. und Mrs.` showed the
 * translation of the whole sentence. The first test below fails against that scheme.
 */
class TranslationKeyTest {

    @Test
    fun aResplitBlockCannotReuseTheOldUnitsTranslation() {
        // Before the splitter change the whole sentence was unit 0 of this block; now unit 0 is
        // the fragment. Under the old key both were "<blockhash>#0" - identical - so one
        // translation answered for both.
        val wholeSentence = "Mr. und Mrs. Dursley im Ligusterweg Nummer 4 waren stolz darauf."
        val fragment = "Mr. und Mrs."
        assertNotEquals(translationKey(wholeSentence), translationKey(fragment))
    }

    @Test
    fun differentTextNeverSharesAKey() {
        assertNotEquals(translationKey("Er ging."), translationKey("Sie ging."))
        assertNotEquals(translationKey("Er ging."), translationKey("Er ging!"))
    }

    @Test
    fun theSameTextSharesOneEntryWhereverItAppears() {
        // Identical sentences are common in prose, and one translation legitimately answers for
        // both, which is the cost this scheme accepts in exchange for never mismatching.
        assertEquals(translationKey("Er ging nach Hause."), translationKey("Er ging nach Hause."))
    }

    @Test
    fun reExtractionThatOnlyMovesWhitespaceCannotOrphanATranslation() {
        // The hash strips whitespace, so a re-import whose extractor normalises spacing
        // differently still resolves the translations the reader already paid for.
        assertEquals(translationKey("Er ging nach Hause."), translationKey("Er  ging\nnach Hause. "))
    }

    @Test
    fun aSentenceAndItsWholeParagraphAreDifferentKeys() {
        // Guards the other direction: a fragment must never inherit its paragraph's translation
        // either, which is the same bug seen from the block instead of the unit.
        val paragraph = "Mr. und Mrs. Dursley waren stolz darauf. Dann kam mehr Text."
        assertNotEquals(translationKey("Mr. und Mrs."), translationKey(paragraph))
    }
}
