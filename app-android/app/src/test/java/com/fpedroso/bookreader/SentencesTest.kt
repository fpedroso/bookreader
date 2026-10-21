package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German sentence splitting, which is the hard case: `.` doubles as the abbreviation and
 * ordinal marker, so the naive splitter breaks on exactly the strings this book contains.
 */
class SentencesTest {

    private fun texts(s: String) = Sentences.splitGerman(s).map { it.text }

    /** The same, with an abbreviation list, which is what the app passes from Settings. */
    private fun textsWith(s: String, list: String) =
        Sentences.splitGerman(s, Sentences.abbreviationSet(list)).map { it.text }

    private val DEFAULTS = Sentences.DEFAULT_ABBREVIATIONS

    @Test
    fun mrAndMrsStopEndingSentencesOnceTheyAreInTheList() {
        // The case that matters for an English novel read in German, and the one the old
        // structure-only splitter could not fix: "Capital. Capital" is lexically identical to a real
        // boundary, so only the word itself can decide.
        val s = "Mr. und Mrs. Beispiel in der Gartenstraße 4 waren stolz darauf, gewöhnlich zu sein."
        assertEquals(listOf(s), textsWith(s, DEFAULTS))
    }

    @Test
    fun aListEntryAlsoFixesTheGermanHonourific() {
        val s = "Dr. Egon Muster kam um acht Uhr an."
        assertEquals(listOf(s), textsWith(s, DEFAULTS))
    }

    @Test
    fun dottedAbbreviationsNeedNoEntryBecauseStructureAlreadyKeepsThem() {
        // The initial-chain rule handles `z.B.` / `u.a.`: the dot follows a capital whose own
        // predecessor is a dot, so there is nothing for the list to do.
        assertEquals(listOf("Ich mag Tiere, z.B. Hunde."), texts("Ich mag Tiere, z.B. Hunde."))
    }

    @Test
    fun anEntryThatAlsoEndsSentencesMergesThemAndThatIsTheTradeOff() {
        // Why usw./etc./bzw./ca. are not defaults: after "usw." a capitalised word is exactly what a
        // new sentence looks like. Listing it merges two real sentences - the reader's call, not ours.
        val s = "Ich mag Katzen, usw. Das ist schön."
        assertEquals(listOf("Ich mag Katzen, usw.", "Das ist schön."), textsWith(s, DEFAULTS))
        assertEquals(listOf(s), textsWith(s, "$DEFAULTS, usw"))
    }

    @Test
    fun aPlainWordBeforeTheDotStillEndsTheSentence() {
        // The negative control: the list must not suppress real boundaries, which is why entries are
        // exact words rather than a "short capitalised word" shape rule.
        assertEquals(listOf("Er ging.", "Beispiel kam."), textsWith("Er ging. Beispiel kam.", DEFAULTS))
    }

    @Test
    fun aCustomEntryWorksExactlyLikeABuiltInOne() {
        val s = "Wir sahen Zorg. Dann gingen wir heim."
        assertEquals(listOf("Wir sahen Zorg.", "Dann gingen wir heim."), textsWith(s, DEFAULTS))
        assertEquals(listOf(s), textsWith(s, "$DEFAULTS, Zorg"))
    }

    @Test
    fun entriesAreMatchedWithoutTheirDotAndWithoutCase() {
        val set = Sentences.abbreviationSet("MR., mrs Prof; \n Zzz")
        assertEquals(setOf("mr", "mrs", "prof", "zzz"), set)
        assertEquals(
            listOf("Mr. Smith blieb hier."),
            Sentences.splitGerman("Mr. Smith blieb hier.", set).map { it.text },
        )
    }

    @Test
    fun anEmptyListIsTheOldBehaviour() {
        // Documented and pinned: with no list, structure alone decides, and Mr./Mrs. split again.
        val s = "Mr. Smith blieb hier."
        assertEquals(listOf("Mr.", "Smith blieb hier."), texts(s))
    }

    @Test
    fun splitsOnTerminators() {
        assertEquals(
            listOf("Er ging nach Hause.", "Sie blieb hier.", "Und dann?"),
            texts("Er ging nach Hause. Sie blieb hier. Und dann?"),
        )
    }

    @Test
    fun noAbbreviationListSoALowercaseNodeIsTheOnlySignalThatADotIsNotAnEnd() {
        // The list is gone, so the structural rules are all that is left. `usw. und` stays
        // joined because the next word is lowercase; `ca. 8` splits because the next token is
        // a digit and therefore not lowercase at all. Both are the honest new behaviour.
        assertEquals(
            listOf("Er kam um ca.", "8 Uhr an, usw. und so fort."),
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
        val stanza = "Sieh einmal, hier steht er,\npfui, der Kritzel-Karl!"
        assertEquals(listOf("Sieh einmal, hier steht er, pfui, der Kritzel-Karl!"), texts(stanza))
    }

    @Test
    fun shortFragmentStandsAloneBecauseThereIsNoMerging() {
        // ACCEPTED TRADE-OFF, not a bug to rediscover: `Pfui!` used to be joined to the next
        // sentence because ML Kit translates a bare interjection badly without its referent.
        // The merge rule is gone, so the fragment is now its own unit and its translation may
        // well be poor. Each unit is exactly what the punctuation says instead.
        assertEquals(
            listOf("Pfui!", "Der Kritzel-Karl war ein ungezogenes Kind."),
            texts("Pfui! Der Kritzel-Karl war ein ungezogenes Kind."),
        )
    }

    @Test
    fun honourificNowSplitsBecauseThereIsNoList() {
        // ACCEPTED TRADE-OFF again. With "mrs" in the abbreviation list this was one sentence;
        // without a list, "Capital. Capital" is indistinguishable from a real boundary, so
        // "Beispiel" starts a new unit. `Mr. und` stays joined on its own, because the next
        // word is lowercase - only the capitalised continuation after `Mrs.`/`Dr.` splits.
        val s = Sentences.splitGerman(
            "Mr. und Mrs. Beispiel in der Gartenstraße 4 waren stolz darauf, ganz und gar gewöhnlich zu sein."
        )
        assertEquals(2, s.size)
        assertEquals("Mr. und Mrs.", s[0].text)
        assertTrue(s[1].text.startsWith("Beispiel in der Gartenstraße"))
    }

    @Test
    fun trailingFragmentIsKept() {
        // Text after the last terminator is still a unit a reader can ask about.
        assertEquals(listOf("Er ging.", "Und dann kam"), texts("Er ging. Und dann kam"))
    }

    @Test
    fun proseBecomesOneUnitPerSentence() {
        // sentenceUnits is what the reader renders as one line per sentence, so these ARE the
        // tap targets: text, not offsets, because the composable is the sentence.
        assertEquals(
            listOf("Er ging nach Hause.", "Sie blieb hier."),
            sentenceUnits("Er ging nach Hause. Sie blieb hier.", heading = false, lang = "de"),
        )
    }

    @Test
    fun headingsAndVerseKeepTheWholeParagraph() {
        // A heading is not translated a sentence at a time, and verse must not be split: the
        // splitter would turn its line breaks back into prose and undo the verse rendering.
        assertNull(sentenceUnits("Ein Neuer Anfang", heading = true, lang = "de"))
        assertNull(
            sentenceUnits(
                "Sieh einmal, hier steht er,\npfui, der Kritzel-Karl!",
                heading = false,
                lang = "de",
            )
        )
    }
}
