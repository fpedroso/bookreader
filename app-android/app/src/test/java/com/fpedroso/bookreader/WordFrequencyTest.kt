package com.fpedroso.bookreader

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The book-local word counts and the band ladder.
 *
 * Two things are load-bearing here and both are pinned below. First the LADDER's shape: the cuts
 * come from the book's own length, because fixed counts were measured against a real novel and put
 * 77% of its page in the top band. Second the SPANS: shading is drawn by appending the words as
 * styled runs separated by plain text, so if that ever failed to reproduce the sentence character
 * for character, every tap on a shaded word would resolve to the wrong word - a bug that would look
 * like the tap handler breaking.
 */
class WordFrequencyTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 100 words: one constant, one right on the frequent cut, one on the occasional cut, 63 singles.
     *
     * The singles are built from letters only on purpose - a name like `wort7` does not tokenise as
     * one word, because a digit is not a letter, so 63 "different" names collapse into `wort`.
     */
    private fun sample(): WordFrequency {
        val words = buildList {
            repeat(30) { add("Hund") }
            repeat(5) { add("Katze") }
            repeat(2) { add("Maus") }
            repeat(63) { i -> add("wort" + ('a' + i / 26) + ('a' + i % 26)) }
        }
        return WordFrequency.of(listOf(words.joinToString(" ")))
    }

    // ------------------------------------------------------------------ the ladder

    @Test
    fun theCutComesFromTheBooksLength() {
        // Measured values from the seven German books, so a change to the constants is a change to
        // numbers that were checked against real text.
        assertEquals(Triple(321, 128, 51), WordFrequency.cuts(80_147)) // Harry Potter
        assertEquals(Triple(46, 18, 7), WordFrequency.cuts(11_494)) // Ahoi aus Hamburg
        assertEquals(Triple(63, 25, 10), WordFrequency.cuts(15_777)) // Walzer in Wien
    }

    @Test
    fun aShortTextKeepsASensibleTopBand() {
        // The floor: a 3,000-word book would otherwise have a top band of about twelve words, which
        // is the one case where the ladder alone is too fine a comb.
        assertEquals(Triple(12, 5, 2), WordFrequency.cuts(1_200))
        assertEquals(Triple(12, 5, 2), WordFrequency.cuts(2_400))
        // And the honest word on the claim: at 3,171 words the ladder gives 13, not 12 - the floor
        // stops applying just above 3,000. One word in the top band differs; the band is still the
        // ~40 words the measurement promised, not the 12 the round-number version suggested.
        assertEquals(Triple(13, 5, 2), WordFrequency.cuts(3_171))
    }

    @Test
    fun bandsUseTheCutInclusiveOfItsBoundary() {
        val f = sample()
        assertEquals(Triple(12, 5, 2), Triple(f.constantAt, f.frequentAt, f.occasionalAt))
        assertEquals(Band.CONSTANT, f.bandAt(12))
        assertEquals(Band.FREQUENT, f.bandAt(11))
        assertEquals(Band.FREQUENT, f.bandAt(5))
        assertEquals(Band.OCCASIONAL, f.bandAt(4))
        assertEquals(Band.OCCASIONAL, f.bandAt(2))
        assertEquals(Band.RARE, f.bandAt(1))
        assertEquals(Band.RARE, f.bandAt(0))
    }

    // ------------------------------------------------------------------ counting

    @Test
    fun countingIgnoresCaseSoASentenceStartDoesNotSplitAWord() {
        val f = WordFrequency.of(listOf("Der Hund und der Hund."))
        assertEquals(2, f.countOf("der"))
        assertEquals(2, f.countOf("Der"))
        assertEquals(2, f.countOf("HUND"))
        assertEquals(1, f.countOf("und"))
        // Five occurrences, not four: `Der` at the start of the sentence and `der` inside it are the
        // same word seen twice, which is the whole point of lowercasing the count.
        assertEquals(5, f.total)
    }

    @Test
    fun countingKeepsHyphensAndApostrophesInsideWords() {
        val f = WordFrequency.of(listOf("E-Mail geht's"))
        assertEquals(1, f.countOf("e-mail"))
        assertEquals(1, f.countOf("geht's"))
        assertEquals(2, f.total)
    }

    @Test
    fun anUnknownWordIsRareAndSaysSo() {
        val f = sample()
        assertEquals(0, f.countOf("Elefant"))
        assertEquals(Band.RARE, f.bandOf("Elefant"))
        assertEquals("does not appear in the counted text", f.describe("Elefant"))
    }

    @Test
    fun describeStatesTheCountOrTheSingleSighting() {
        val f = sample()
        assertEquals("appears 30× in this book", f.describe("hund"))
        assertEquals("appears once in this book", f.describe("wortab"))
    }

    @Test
    fun everyCountedWordLandsInExactlyOneBand() {
        val f = sample()
        val byBand = Band.entries.associateWith { b -> f.counts.filterValues { f.bandAt(it) == b }.size }
        assertEquals(f.counts.size, byBand.values.sum())
        assertEquals(1, byBand.getValue(Band.CONSTANT))
        assertEquals(1, byBand.getValue(Band.FREQUENT))
        assertEquals(1, byBand.getValue(Band.OCCASIONAL))
        assertEquals(63, byBand.getValue(Band.RARE))
        assertEquals(0.30, f.textShare(Band.CONSTANT), 0.0001)
    }

    @Test
    fun anEmptyBookDoesNotDivideByZero() {
        val f = WordFrequency.of(emptyList())
        assertEquals(0, f.total)
        assertEquals(0.0, f.textShare(Band.RARE), 0.0)
        assertEquals(Band.RARE, f.bandOf("anything"))
    }

    // ------------------------------------------------------------------ the spans

    @Test
    fun everySpanIsAWordAndNothingElse() {
        val sentence = "Der Hund—ein grosser Hund—bellt, und die Katze geht's."
        val spans = shadedSpans(sentence) { Band.CONSTANT }
        val words = Words.tokenize(sentence)
        assertEquals(words.size, spans.size)
        assertEquals(words.map { it.start to it.end }, spans.map { it.start to it.end })
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test
    fun shadingCannotMoveASingleOffset() {
        // What Ui.buildAnnotatedString does with the spans: the words as their own runs, every gap
        // appended plain. The result must be the input text, character for character - the tap
        // handler resolves an offset against it.
        val sentence = "Aber plötzlich, am Ende der Straße, geschah etwas."
        val spans = shadedSpans(sentence) { w -> if (w == "geschah") Band.RARE else Band.CONSTANT }
        val rebuilt = StringBuilder()
        var at = 0
        for (s in spans) {
            rebuilt.append(sentence, at, s.start)
            rebuilt.append(sentence, s.start, s.end)
            at = s.end
        }
        rebuilt.append(sentence, at, sentence.length)
        assertEquals(sentence, rebuilt.toString())
    }

    @Test
    fun aWordIsShadedByItsOwnBand() {
        val f = sample()
        val sentence = "Der Hund sah die Maus und den Elefant."
        val spans = shadedSpans(sentence, f::bandOf)
        assertEquals(Band.CONSTANT, spans.first { sentence.substring(it.start, it.end) == "Hund" }.band)
        assertEquals(Band.OCCASIONAL, spans.first { sentence.substring(it.start, it.end) == "Maus" }.band)
        assertEquals(Band.RARE, spans.first { sentence.substring(it.start, it.end) == "Elefant" }.band)
    }

    // ------------------------------------------------------------------ the setting

    @Test
    fun settingsWrittenBeforeShadingExistedReadAsOff() {
        val old = json.decodeFromString(
            AppSettings.serializer(),
            """{"engine":"mlkit","apiKey":"k","theme":"light"}""",
        )
        assertFalse(old.wordShading)
        assertEquals("light", old.theme)
        assertEquals("k", old.apiKey)
    }
}
