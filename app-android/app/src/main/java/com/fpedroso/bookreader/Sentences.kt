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
 * splitter breaks `Dr. Egon Muster` and `564. Auflage` - both of which appear in the prose
 * these rules are tested against.
 *
 * There is deliberately NO fragment merging: this splitter relies on structure alone - the case of
 * the following token, ordinals, initial chains, the comma/colon rule and closing punctuation.
 *
 * There IS now an explicit abbreviation list, and it is a SETTING rather than a hard-coded
 * heuristic. The note above used to reject both, but it also named the one reliable signal: "only
 * knowing which words are honourifics can separate them". A list is that knowledge, and the reader
 * owns it - `Mr.`/`Mrs.` matter for an English novel read in German, `Dr.`/`z.B.` for German prose,
 * and a shape-based guess ("a short capitalised word before the dot") was rejected for good reason:
 * it suppresses the boundary in `Er traf Hans. Dann ging er.`, which is worse than the false split
 * it would fix. The list is matched case-insensitively, with a trailing dot on either side ignored,
 * so `Mr`, `Mr.` and `z.B.` all work as typed. Empty list = the old structure-only behaviour, which
 * the unit tests still pin.
 *
 * Everything here is pure and works on whitespace-collapsed text.
 */
object Sentences {

    data class Sentence(val text: String, val start: Int, val end: Int)

    /**
     * What the reader gets before touching anything: honourifics and reference abbreviations, which
     * are the ones that are reliably mid-sentence.
     *
     * Two deliberate exclusions, both learned from the tests:
     *
     *  - Dotted forms (`z.B.`, `u.a.`, `d.h.`) need no entry: the splitter's initial-chain rule
     *    already keeps them together, because the dot follows a capital whose own predecessor is a dot.
     *  - Words that also END sentences (`usw.`, `etc.`, `bzw.`, `ca.`) are NOT defaults. After `usw.`
     *    a capitalised word is exactly what a new sentence looks like, so listing it merges two real
     *    sentences into one translation unit. A reader who prefers that can add it; the tests pin both
     *    sides of that trade-off.
     */
    const val DEFAULT_ABBREVIATIONS = "Mr, Mrs, Ms, Dr, Prof, St, Nr, Bd, Abb"

    /**
     * Parse the setting: comma, semicolon, newline or plain space separated, case-insensitive, with
     * or without its dot. Returns lowercase keys with any trailing dot removed, which is the form the
     * splitter compares against.
     *
     * Whitespace counts as a separator because that is how people type a list of short words by hand
     * ("Mr Mrs Dr"); nothing that matters here contains a space.
     */
    fun abbreviationSet(raw: String): Set<String> = raw
        .split(',', ';', '\n', ' ', '\t')
        .map { it.trim().removeSuffix(".").lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** Trailing punctuation that belongs to the sentence it closes. */
    private val CLOSERS = setOf('»', '«', '“', '”', '\"', '\'', '’', '‘', ')', ']', '}')

    private val TERMINATORS = setOf('.', '!', '?', '…')

    /** Collapse every run of whitespace, so verse line breaks become plain spaces. */
    private fun normalise(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    fun split(text: String, lang: String, abbreviations: Set<String> = emptySet()): List<Sentence> =
        when {
            lang.startsWith("de") -> splitGerman(text, abbreviations)
            // Latin-script default; good enough for the other European languages.
            else -> splitByAsciiPunctuation(text, abbreviations)
        }

    /**
     * The sentence containing [offset], or null when nothing there is sentence-shaped.
     *
     * Offsets are the CALLER's own - the same offsets it would use to find a word - so this works on
     * the raw block text the reader renders. That is the whole reason it exists: the reader no longer
     * re-lays a paragraph out sentence by sentence, so a tap reports an offset into the paragraph as
     * the EPUB wrote it, and the sentence that "Translate more" grows to has to be found at that
     * offset rather than counted off a line.
     */
    fun enclosing(
        text: String,
        offset: Int,
        lang: String,
        abbreviations: Set<String> = emptySet(),
    ): Sentence? = split(text, lang, abbreviations).firstOrNull { offset in it.start until it.end }

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
    fun splitGerman(raw: String, abbreviations: Set<String> = emptySet()): List<Sentence> =
        splitByAsciiPunctuation(raw, abbreviations)

    private fun splitByAsciiPunctuation(text: String, abbreviations: Set<String>): List<Sentence> {
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
            if (endsSentence(text, i, j, k, abbreviations)) {
                // The OFFSETS stay the caller's (they index [text] as handed in); only the display
                // text is whitespace-normalised, so a raw paragraph's double spaces do not show up in
                // the modal - and, because the app keys translations on this text, normalised here is
                // also what keeps existing cache entries resolving.
                out.add(Sentence(normalise(text.substring(start, k + 1)), start, k + 1))
                start = k + 1
            }
            i = k + 1
        }
        if (start < text.length) {
            val tail = normalise(text.substring(start))
            if (tail.isNotEmpty()) out.add(Sentence(tail, start, text.length))
        }
        return out
    }

    /**
     * Is the terminator run [termStart]..[termEnd], closed at [closeEnd], a sentence end?
     *
     * Ordered from the most reliable signals to the weakest, so the cheap guards run first.
     */
    private fun endsSentence(
        text: String,
        termStart: Int,
        termEnd: Int,
        closeEnd: Int,
        abbreviations: Set<String>,
    ): Boolean {
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
            // A single capital letter is an initial: H. Muster.
            if (word.length == 1 && word.first().isUpperCase()) return false
            // An explicit honourific or abbreviation does not end the sentence. This is the case
            // structure cannot decide: `Mr. Smith` and `Er ging. Beispiel kam.` are lexically
            // identical, so only knowing the word can tell them apart.
            if (word.lowercase() in abbreviations) return false
            // Nothing else: without the word in the list, `Dr.` / `Mrs.` end the sentence here when
            // a capitalised word follows. See the note at the top of this file.
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
