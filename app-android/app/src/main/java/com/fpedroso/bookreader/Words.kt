package com.fpedroso.bookreader

/**
 * Word boundaries inside one rendered unit of prose, and the mapping from a tap to the word
 * under it.
 *
 * A word is a maximal run of letters, plus an apostrophe or hyphen that has a letter on BOTH
 * sides of it. That keeps `geht's` and `E-Mail` whole - the apostrophe and the hyphen really are
 * inside German words - while an em dash (the `3—6` form) still separates, and a trailing
 * punctuation mark is never part of the word.
 *
 * Offsets are into the string handed in, so [Word.start]/[Word.end] index the sentence exactly as
 * it is rendered. Nothing here holds state: [at] is called with the character offset a tap
 * resolved to, and works on the tapped sentence alone.
 *
 * Not a segmenter. Han, kana and Hangul are letters, so a run of them with no space between
 * comes back as ONE token, which is the wrong unit for those scripts - they need a real
 * segmenter. Translation is gated to German books for now (see [translationOffered]), so this
 * never runs on them; if that gate widens, this is the place that has to change.
 */
object Words {

    data class Word(val text: String, val start: Int, val end: Int)

    /** Inside a word, but never at its edge: a joiner only joins when a letter follows it. */
    private fun isJoiner(c: Char): Boolean = c == '\'' || c == '’' || c == '-'

    /**
     * Every word in [text], in order.
     *
     * A joiner is consumed without requiring the character BEFORE it to be re-checked: the scan
     * only ever starts on a letter and only ever extends past a joiner, so a leading `'` or `-`
     * can never begin a token.
     */
    fun tokenize(text: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            val start = i
            var end = i + 1
            while (end < text.length) {
                val c = text[end]
                if (c.isLetter()) {
                    end++
                    continue
                }
                // The joiner joins only when a letter follows it AND the token already has one,
                // which is guaranteed here because end > start. Consume both characters so the
                // letter after the joiner is part of this same token.
                if (isJoiner(c) && text.getOrNull(end + 1)?.isLetter() == true) {
                    end += 2
                    continue
                }
                break
            }
            // A joiner with nothing after it would have taken end past the string; clamp.
            val stop = end.coerceAtMost(text.length)
            out.add(Word(text.substring(start, stop), start, stop))
            i = stop
        }
        return out
    }

    /**
     * The word containing [offset], or null when the offset lands on a space, a punctuation mark
     * or a digit - positions that are not a word and must not silently resolve to a neighbour.
     *
     * The caller decides what an empty answer means. The reader view falls back to [atNearest],
     * because a tap between two words should not be a dead tap - see that function for why the old
     * "ask for the sentence instead" answer is gone.
     */
    fun at(text: String, offset: Int): Word? {
        if (offset < 0 || offset >= text.length) return null
        return tokenize(text).firstOrNull { offset in it.start until it.end }
    }

    /**
     * The word nearest [offset], for a tap that landed between two words.
     *
     * Deliberately different from [at], and the difference is a design change. While the sentence was
     * a tap unit, a tap in whitespace asked for the SENTENCE: the unit was the honest answer to a
     * vague tap, and resolving to a neighbouring word would have been a guess. Sentences are no
     * longer reachable from the text - they come from a word's modal - so there is nothing left for
     * a miss to mean, and a tap that does nothing at all reads as a broken app.
     *
     * Distance is measured to the nearest edge of each word, so a tap just past a word's last letter
     * prefers that word. Ties go to the EARLIER word: `minByOrNull` keeps the first minimum, and in a
     * left-to-right line a tap on the boundary belongs to the word it just left.
     */
    fun atNearest(text: String, offset: Int): Word? {
        val words = tokenize(text)
        if (words.isEmpty()) return null
        return words.minByOrNull { w ->
            when {
                offset in w.start until w.end -> 0
                offset < w.start -> w.start - offset
                else -> offset - (w.end - 1)
            }
        }
    }
}
