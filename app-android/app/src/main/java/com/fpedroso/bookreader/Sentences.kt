package com.fpedroso.bookreader

/**
 * Sentence splitting for translation.
 *
 * EPUB has no sentence markup: neither fixture contains a single `<sent>` element, and
 * `epub:type` carries no sentence term (the only place sentence-level markup exists in the
 * ecosystem is Media Overlays, which are capped by the content document's own markup).
 * So sentences have to be derived from punctuation - and the rules are completely
 * different per script, the same way rendering and segmentation are.
 *
 * German is the hard one. `.` is *also* the abbreviation and ordinal marker, so a naive
 * splitter breaks `Dr. Heinrich Hoffmann` and `564. Auflage` - both of which are in the
 * test book.
 *
 * There is deliberately NO abbreviation list and no fragment merging: this splitter relies on
 * structure alone - the case of the following token, ordinals, initial chains, the
 * comma/colon rule and closing punctuation. The cost is honest and accepted: `Mr. und Mrs.
 * Dursley` and `Dr. Heinrich Hoffmann` split again, because "Capital. Capital" is lexically
 * identical to a real boundary (`Er ging. Dursley kam.`) and only knowing which words are
 * honourifics can separate them. A shape-based substitute ("a short capitalised word before
 * the dot") was rejected too: it suppresses the boundary in `Er traf Hans. Dann ging er.`,
 * which is worse than the false split it would fix. Do not reintroduce either.
 *
 * Everything here is pure and works on whitespace-collapsed text.
 */
object Sentences {

    data class Sentence(val text: String, val start: Int, val end: Int)

    /** Trailing punctuation that belongs to the sentence it closes. */
    private val CLOSERS = setOf('»', '«', '“', '”', '\"', '\'', '’', '‘', ')', ']', '}')

    private val TERMINATORS = setOf('.', '!', '?', '…')

    /** Collapse every run of whitespace, so verse line breaks become plain spaces. */
    private fun normalise(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    fun split(text: String, lang: String): List<Sentence> = when {
        lang.startsWith("de") -> splitGerman(text)
        // Latin-script default; good enough for the other European languages.
        else -> splitByAsciiPunctuation(normalise(text))
    }

    // ------------------------------------------------------------------ German

    /**
     * German splitting, structure only.
     *
     * No fragment merging: a bare interjection like `Pfui!` is now its own translation unit,
     * and ML Kit will translate it poorly in isolation because the referent lives in the next
     * sentence. That is the accepted price of dropping the heuristic, and it cuts the other
     * way as well: every unit is now exactly what the punctuation says, so a sentence the
     * reader sees is a sentence the splitter meant.
     */
    fun splitGerman(raw: String): List<Sentence> = splitByAsciiPunctuation(normalise(raw))

    private fun splitByAsciiPunctuation(text: String): List<Sentence> {
        val out = ArrayList<Sentence>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] !in TERMINATORS) {
                i++
                continue
            }
            // A run like "?!" or "..." is one terminator.
            var j = i
            while (j + 1 < text.length && text[j + 1] in TERMINATORS) j++
            // Absorb the closing quote/bracket: »Komm her!« ends at «, not at !.
            var k = j
            while (k + 1 < text.length && text[k + 1] in CLOSERS) k++
            if (endsSentence(text, i, j, k)) {
                out.add(Sentence(text.substring(start, k + 1).trim(), start, k + 1))
                start = k + 1
            }
            i = k + 1
        }
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) out.add(Sentence(tail, start, text.length))
        }
        return out
    }

    /**
     * Is the terminator run [termStart]..[termEnd], closed at [closeEnd], a sentence end?
     *
     * Ordered from the most reliable signals to the weakest, so the cheap guards run first.
     */
    private fun endsSentence(text: String, termStart: Int, termEnd: Int, closeEnd: Int): Boolean {
        val after = closeEnd + 1
        if (after >= text.length) return true

        // A comma or a lowercase word after the terminator means the sentence continues -
        // which is also how speech attributions work: »Komm her!«, rief er.
        val next = text[after]
        if (next == ',' || next == ';' || next == ':') return false
        val nextWord = wordAt(text, after)
        if (nextWord != null && nextWord.first().isLowerCase()) return false

        // Nothing but punctuation follows the terminator: "H.P." or "z.B." - not an end.
        if (termEnd == closeEnd && !next.isWhitespace()) return false

        if (text[termStart] == '.') {
            if (termStart == 0) return false
            // Ordinals and thousands separators: 564. Auflage, 1. Januar, 13.000.
            if (text[termStart - 1].isDigit()) return false
            // Initial chains: H.P. Yarroll. The dot follows a capital whose own predecessor
            // is another dot - which is what separates it from an acronym at the end of a
            // sentence ("... von der CIA."), where the predecessor is a letter.
            if (termStart >= 2 && text[termStart - 1].isUpperCase() && text[termStart - 2] == '.') {
                return false
            }
            val word = wordBefore(text, termStart) ?: return true
            // A single capital letter is an initial: H. Hoffmann.
            if (word.length == 1 && word.first().isUpperCase()) return false
            // Nothing else: there is no abbreviation list, so `Dr.` / `Mrs.` end the sentence
            // here when a capitalised word follows. See the note at the top of this file.
        }
        return true
    }

    private fun wordBefore(text: String, at: Int): String? {
        var i = at - 1
        while (i >= 0 && (text[i].isLetter() || text[i] == '.' || text[i] == '-')) i--
        return if (i + 1 == at) null else text.substring(i + 1, at)
    }

    private fun wordAt(text: String, at: Int): String? {
        var i = at
        while (i < text.length && !text[i].isLetter()) i++
        if (i >= text.length) return null
        var j = i
        while (j < text.length && text[j].isLetter()) j++
        return text.substring(i, j)
    }
}
