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
 * test book. The literature on German sentence boundary detection is consistent that a
 * rule-based approach with an abbreviation list is the right tool (Proisl & Uhrig;
 * Sugisaki et al.), and that the case of the following token is the strongest signal: an
 * uppercase next token suggests a sentence end, lowercase suggests an abbreviation.
 *
 * Everything here is pure and works on whitespace-collapsed text.
 */
object Sentences {

    data class Sentence(val text: String, val start: Int, val end: Int)

    /**
     * Fragments shorter than this are joined to the following sentence. ML Kit has no
     * context and translates a bare `Pfui!` badly, because the referent lives in the next
     * sentence - but the fragment still has to be *shown* on the right line, which is why
     * this happens at the splitter level rather than at display time.
     *
     * Kept deliberately small: German is full of short *complete* sentences ("Er ging.",
     * "Und dann?"), and merging those would change the unit a reader sees. This should
     * only catch true interjections and fragments.
     */
    private const val MIN_UNIT = 8

    /** Trailing punctuation that belongs to the sentence it closes. */
    private val CLOSERS = setOf('»', '«', '“', '”', '\"', '\'', '’', '‘', ')', ']', '}')

    /** Lowercased, dot-stripped forms. German prose leans on these heavily. */
    private val ABBREV = setOf(
        "dr", "prof", "hr", "fr", "nr", "bd", "bde", "s", "abb", "abs", "art", "aufl",
        "bspw", "bzw", "ca", "d.h", "dh", "evtl", "ggf", "inkl", "insb", "jh", "kap",
        "mio", "mrd", "o.ä", "s.o", "s.u", "sog", "u.a", "u.a.m", "usw", "vgl", "z.b",
        "z.t", "zzgl", "etc", "jan", "feb", "mrz", "apr", "jun", "jul", "aug", "sept",
        // Titles and English spawn, which a German novel still carries: without "mrs" the
        // first paragraph of a real book opened "Mr. und Mrs." as a unit of its own and cut
        // the sentence in half. Visible immediately once sentences got borders.
        "mr", "mrs", "ms", "dr", "prof", "jr", "sr",
        "okt", "nov", "dez", "hrs", "geb", "gest", "verh", "gesch", "max", "min",
        "e.v", "z.h", "i.d.r", "m.e", "u.u", "o.g", "bspw", "resp", "v.a", "zzt",
    )

    private val TERMINATORS = setOf('.', '!', '?', '…')

    /**
     * The sentence a character [offset] belongs to, as an index into [ranges].
     *
     * The reader renders one paragraph as a single Text and turns a finger position into a
     * character offset, so this is what recovers sentence-level translation from a tap on
     * continuous prose. A tap on the whitespace between two sentences - or on the leading
     * space of a sentence, which `split` keeps inside the range - resolves to the nearest
     * sentence rather than failing: there is no position in the paragraph, including past
     * its end, that has no answer.
     */
    fun indexAt(ranges: List<IntRange>, offset: Int): Int {
        ranges.forEachIndexed { i, r -> if (offset in r) return i }
        var best = 0
        var bestDistance = Int.MAX_VALUE
        ranges.forEachIndexed { i, r ->
            val distance = if (offset < r.first) r.first - offset else offset - r.last
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /** Collapse every run of whitespace, so verse line breaks become plain spaces. */
    private fun normalise(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    fun split(text: String, lang: String): List<Sentence> = when {
        lang.startsWith("de") -> splitGerman(text)
        // Latin-script default; good enough for the other European languages.
        else -> splitByAsciiPunctuation(normalise(text))
    }

    // ------------------------------------------------------------------ German

    fun splitGerman(raw: String): List<Sentence> =
        mergeShort(splitByAsciiPunctuation(normalise(raw)), MIN_UNIT)

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
            if (word.trimEnd('.').lowercase() in ABBREV) return false
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

    /** Join context-free fragments onto the following sentence. */
    private fun mergeShort(parts: List<Sentence>, min: Int): List<Sentence> {
        if (parts.isEmpty()) return parts
        val out = ArrayList<Sentence>(parts.size)
        var pending: Sentence? = null
        for (p in parts) {
            pending = if (pending == null) p else Sentence(
                text = pending.text + " " + p.text,
                start = pending.start,
                end = p.end,
            )
            if (pending.text.length >= min) {
                out.add(pending)
                pending = null
            }
        }
        pending?.let { out.add(it) }
        return out
    }
}
