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
     * The caller decides what an empty answer means; the reader view treats it as "the unit",
     * because a tap between two words should not be a dead tap.
     */
    fun at(text: String, offset: Int): Word? {
        if (offset < 0 || offset >= text.length) return null
        return tokenize(text).firstOrNull { offset in it.start until it.end }
    }
}
