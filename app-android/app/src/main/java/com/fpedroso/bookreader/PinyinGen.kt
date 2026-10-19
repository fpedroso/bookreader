package com.fpedroso.bookreader

import android.content.Context

/**
 * In-app pinyin generation, so a Chinese EPUB can be imported and read without a PC pass.
 *
 * The PC pipeline (`tools/annotate.py`) segments with jieba and reads from CC-CEDICT.
 * There is no jieba on device: `jieba-kmp` 1.0.0 is built with Kotlin 2.3.0 metadata and
 * this project is pinned to Kotlin 2.0.21 by AGP 8.6.1 / compileSdk 35, so the compiler
 * rejects it outright ("The binary version of its metadata is 2.3.0, expected version is
 * 2.0.0") and closing that gap means moving Kotlin -> AGP -> Compose -> compileSdk.
 *
 * So segmentation here is maximal matching over the CC-CEDICT already bundled: longest
 * headword at each position, single character otherwise. jieba resolves ambiguity better,
 * but the *readings* barely differ, because `annotate.py` already falls back to
 * per-character readings whenever jieba groups a phrase that is not itself a headword, and
 * CC-CEDICT covers the fixtures completely. A book that has a PC sidecar keeps using it;
 * this path only fills in books that have none.
 *
 * [segment] and [annotate] are pure - the dictionary is injected as a lambda - so the
 * whole chain runs under JVM unit tests with no Android and no SQLite.
 */
object PinyinGen {

    /**
     * Longest headword to try. CC-CEDICT's longer entries are 成语 and names; going past
     * this buys little because a missed long word still resolves as smaller pieces plus
     * the per-character fallback.
     */
    private const val MAX_WORD = 7

    fun isHan(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    private fun String.hasHan(): Boolean = any { isHan(it) }

    /**
     * Split [text] into tokens: Han words by longest match, non-Han runs kept intact.
     *
     * Punctuation is kept on purpose. Emitting only Han words silently deletes every 、，。
     * from the rendered book, which also makes the words either side of a sentence
     * boundary look run together. Whitespace is dropped, as `annotate.py` does.
     */
    fun segment(text: String, isHeadword: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val n = text.length
        var i = 0
        while (i < n) {
            if (isHan(text[i])) {
                var len = minOf(MAX_WORD, n - i)
                var hit = 0
                // Longest first, so 研究生 wins over 研究 + 生.
                while (len >= 2) {
                    if (isHeadword(text.substring(i, i + len))) {
                        hit = len
                        break
                    }
                    len--
                }
                if (hit > 0) {
                    out.add(text.substring(i, i + hit))
                    i += hit
                } else {
                    out.add(text[i].toString())
                    i++
                }
            } else {
                if (text[i].isWhitespace()) {
                    i++
                    continue
                }
                // Keep letters/digits together and punctuation together, so "Hello!" is
                // two tokens rather than one, and "……" is one rather than two.
                val start = i
                val wordish = text[i].isLetterOrDigit()
                while (i < n && !isHan(text[i]) && !text[i].isWhitespace() &&
                    text[i].isLetterOrDigit() == wordish
                ) {
                    i++
                }
                out.add(text.substring(start, i))
            }
        }
        return out
    }

    /**
     * Annotate one block. Returns null when the block has no Han at all, matching
     * `annotate.py`, which stores nothing for pure-Latin blocks.
     *
     * [lookup] returns the tone-marked reading for a CC-CEDICT headword, or null when the
     * word is not one.
     */
    fun annotate(
        text: String,
        /**
         * Injectable purely so the per-character fallback below stays testable: maximal
         * matching only ever emits headwords or single characters, so it cannot produce
         * the grouped non-headword that fallback exists for. `annotate.py`'s jieba does
         * produce those (不敢, 一座), so the branch is kept for a future segmenter.
         */
        segmenter: (String, (String) -> Boolean) -> List<String> = ::segment,
        // Declared last so callers can pass the dictionary as a trailing lambda.
        lookup: (String) -> String?,
    ): List<Token>? {
        val words = segmenter(text) { lookup(it) != null }
        if (words.none { it.hasHan() }) return null

        val out = ArrayList<Token>(words.size)
        for (w in words) {
            if (!w.hasHan()) {
                // Punctuation or a Latin run: no reading row.
                out.add(Token(w, "", "none"))
                continue
            }
            val py = lookup(w)
            if (py != null) {
                out.add(Token(w, py, "cedict"))
                continue
            }
            // A grouped run that is not itself a headword (不敢, 一座): its characters
            // usually are, and CC-CEDICT readings beat a pinyin library's because
            // CC-CEDICT encodes the neutral tone 轻声 that pypinyin drops.
            val perChar = w.filter { isHan(it) }.map { lookup(it.toString()) }
            if (perChar.isNotEmpty() && perChar.all { it != null }) {
                out.add(Token(w, perChar.joinToString(" ") { it!! }, "cedict-chars"))
            } else {
                // No pypinyin on device, so rather than invent a reading, leave it blank:
                // the character still renders and is still tappable for its meaning.
                out.add(Token(w, "", "unresolved"))
            }
        }
        return out.ifEmpty { null }
    }

    /** Real entry point. Call off the main thread; the first call may open the asset. */
    fun annotate(context: Context, text: String): List<Token>? =
        annotate(text, lookup = { Cedict.pinyin(context, it) })
}
