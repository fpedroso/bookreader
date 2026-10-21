package com.fpedroso.bookreader

import kotlin.math.roundToInt

/**
 * How often each word occurs in one book, and the band that puts it in.
 *
 * Book-local by design: nothing outside the book is consulted, no word list is bundled and no word
 * is excluded. That is what makes the claim on screen exactly true - "you will meet this again
 * here" - and it is why a character's name is shaded like anything else: `Max` really does recur
 * 24 times in Max und Moritz. A language-wide frequency list would answer a different question and,
 * measured against this one, mostly repeats it.
 *
 * Counting is case-insensitive: German capitalises every noun, so `Der` at the start of a sentence
 * and `der` inside one are one word, not two. The cost is that a capitalised noun and a lowercase
 * verb with the same letters (`Essen`/`essen`) merge - accepted, because the alternative splits the
 * commonest words in the language in half. Counting is on exact FORMS: there is no lemmatiser here,
 * so `ging` and `geht` are counted, and shaded, separately even though both are `gehen`.
 *
 * Five bands, from a ladder anchored on the book's own length rather than fixed counts. Fixed counts
 * were measured first and break on a novel: 12+/5-11/2-4/1 puts 77% of an 80,000-word book's page in
 * the top band, one flat wash. Anchored, the page mix holds across seven German books from 8,500 to
 * 80,000 words - 37 / 10 / 12 / 20 / 21 percent of the running text on an 80,000-word novel, and
 * 38 / 16 / 12 / 21 / 14 on an 11,000-word one.
 *
 * The ladder was NOT cut on cumulative text share, and it must never be: that was tried and
 * collapses, because the share keeps climbing while you walk the once-only words, so the third cut
 * lands at 1 and the bottom two bands merge. Cut on COUNT.
 */
class WordFrequency private constructor(
    /** Lowercased surface form -> occurrences in this book. */
    val counts: Map<String, Int>,
    /** Every word occurrence in the book, the denominator of a band's share of the page. */
    val total: Int,
    /** A word occurring at least this often is "constant". */
    val constantAt: Int,
    /** ... "frequent". */
    val frequentAt: Int,
    /** ... "occasional". */
    val occasionalAt: Int,
    /** ... "uncommon". Everything below this is [Band.RARE], the faintest. */
    val uncommonAt: Int,
) {

    /** Occurrences of [word] in this book, 0 when it never appears. */
    fun countOf(word: String): Int = counts[word.lowercase()] ?: 0

    fun bandOf(word: String): Band = bandAt(countOf(word))

    /**
     * The band of a word seen [count] times.
     *
     * A count of 0 falls in [Band.RARE]: it means the word was not counted, which happens only for
     * a block outside the counted set. Faint is the honest answer there - the reader has no
     * evidence about it - and it is unreachable in the ordinary case, because shading is offered
     * exactly on the blocks that were counted.
     */
    fun bandAt(count: Int): Band = when {
        count >= constantAt -> Band.CONSTANT
        count >= frequentAt -> Band.FREQUENT
        count >= occasionalAt -> Band.OCCASIONAL
        count >= uncommonAt -> Band.UNCOMMON
        else -> Band.RARE
    }

    /** "appears 23× in this book", or "appears once in this book" - the modal's one factual line. */
    fun describe(word: String): String {
        val c = countOf(word)
        return when (c) {
            0 -> "does not appear in the counted text"
            1 -> "appears once in this book"
            else -> "appears $c× in this book"
        }
    }

    /** Share of the book's running text in [band], 0.0 to 1.0. Used by tests, not by the reader. */
    fun textShare(band: Band): Double {
        val n = counts.values.filter { bandAt(it) == band }.sum()
        return if (total == 0) 0.0 else n.toDouble() / total
    }

    /** The four cuts, most frequent first. Everything below [rare] is [Band.RARE]. */
    data class Cuts(val constant: Int, val frequent: Int, val occasional: Int, val rare: Int)

    companion object {

        /**
         * The ladder: the top cut is "about once per 250 words of this book", and each band below it
         * is about 2.5x wider - except the LAST step, which is a fifth.
         *
         * That last step is tighter deliberately, and it was settled by reading real pages rather
         * than by taste. At 2.5 the two bottom bands come out lopsided on a novel (28% of the page
         * against 12% of it) and, worse, a word read eighteen times shares a tone with one read
         * twice - exactly the distinction a fifth band exists to draw.
         *
         * Reproduces roughly 12/5/2/2 on a short children's book and 321/128/51/10 at 80,000 words.
         * The floors keep a very short text from producing a top band of three words, and keep
         * "occasional" from meaning a single sighting.
         */
        fun cuts(total: Int): Cuts {
            val t1 = maxOf(12, (total / 250f).roundToInt())
            val t2 = maxOf(3, (t1 / 2.5f).roundToInt())
            val t3 = maxOf(2, (t2 / 2.5f).roundToInt())
            val t4 = maxOf(2, (t3 / 5f).roundToInt())
            return Cuts(t1, t2, t3, t4)
        }

        /** Count [blocks] - the book's prose, already filtered to the text worth counting. */
        fun of(blocks: Iterable<String>): WordFrequency {
            val counts = HashMap<String, Int>()
            var total = 0
            for (block in blocks) {
                for (w in Words.tokenize(block)) {
                    // The same tokenizer the tap handler uses, so a shaded run is exactly a
                    // tappable word: shading and taps cannot disagree about where a word starts.
                    counts[w.text.lowercase()] = (counts[w.text.lowercase()] ?: 0) + 1
                    total++
                }
            }
            val c = cuts(total)
            return WordFrequency(counts, total, c.constant, c.frequent, c.occasional, c.rare)
        }
    }
}

/**
 * How often a word recurs in the book being read, most to least frequent.
 *
 * [CONSTANT] is the band the reader paints NOTHING for - see `wordTint`. The other four fade, one
 * step each, with [RARE] the faintest.
 */
enum class Band { CONSTANT, FREQUENT, OCCASIONAL, UNCOMMON, RARE }

/** One word's span inside a rendered sentence, and the band it is in. */
data class ShadedSpan(val start: Int, val end: Int, val band: Band)

/**
 * The word spans of [text], each with its band - what shading paints, and nothing more.
 *
 * Deliberately built on [Words.tokenize], the same function [Words.at] resolves a tap with, so the
 * spans and the tap targets are the same ranges by construction rather than by agreement. The spans
 * cover only the words: the gaps between them (spaces, punctuation) are left alone, and the
 * concatenation of [text] over the spans is [text] itself - shading may not move a single offset.
 */
fun shadedSpans(text: String, bandOf: (String) -> Band): List<ShadedSpan> =
    Words.tokenize(text).map { ShadedSpan(it.start, it.end, bandOf(it.text)) }
