package com.fpedroso.bookreader

import android.content.Context

/**
 * One dictionary hit, shaped for display.
 *
 * Chinese and German answer different questions, so the fields are optional rather
 * than pretending they share a schema:
 *  - Chinese: [pinyin] is the reading, [lemma] is null.
 *  - German:  [lemma] is the resolved head word when the tapped token was inflected
 *             (ging -> gehen), [pos] is the part of speech, [pinyin] is null.
 */
data class Lookup(
    val headword: String,
    val lemma: String? = null,
    val pos: String? = null,
    val pinyin: String? = null,
    val glosses: List<String> = emptyList(),
) {
    /** True when the tapped token was an inflected form of [lemma]. */
    val wasInflected: Boolean
        get() = lemma != null && !lemma.equals(headword, ignoreCase = true)
}

interface Dictionary {
    fun lookup(word: String): List<Lookup>
    /** Force the backing asset open (first call may copy a large db off assets). */
    fun warmUp() {}
}

/** Chinese -> English via CC-CEDICT. Readings (pinyin) come from the same table. */
class ChineseDict(private val context: Context) : Dictionary {

    override fun lookup(word: String): List<Lookup> {
        val direct = Cedict.lookup(context, word)
        if (direct.isNotEmpty()) {
            return direct.map {
                Lookup(
                    headword = word,
                    pinyin = it.pinyin.ifBlank { null },
                    glosses = it.definitions,
                )
            }
        }
        // Same fallback the annotator uses: a multi-character token may not be a
        // headword itself (兩個, 不敢) even though its characters are.
        val chars = word.filter { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        if (chars.length < 2) return emptyList()
        return chars.mapNotNull { ch ->
            Cedict.lookup(context, ch.toString()).firstOrNull()?.let {
                Lookup(
                    headword = ch.toString(),
                    pinyin = it.pinyin.ifBlank { null },
                    glosses = it.definitions,
                )
            }
        }
    }

    override fun warmUp() {
        Cedict.open(context)
    }
}

/** Splits German prose into tappable words plus separate punctuation runs. */
object GermanTokenizer {
    // A word is a run of letters, optionally joined by hyphen or apostrophe; digits
    // and punctuation runs come out as their own tokens so they stay un-tappable.
    private val TOKEN = Regex(
        "[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*|\\d+|[^\\p{L}\\p{M}\\d\\s]+"
    )

    fun tokenize(text: String): List<Token> =
        TOKEN.findAll(text).map { m ->
            Token(word = m.value, pinyin = "", source = "de")
        }.toList()
}
