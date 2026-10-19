package com.fpedroso.bookreader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Pinyin sidecar produced on the PC by tools/annotate.py.
 *
 * Keyed by PARAGRAPH HASH rather than position, so the Python extractor and the
 * Kotlin extractor cannot drift out of sync: a block either resolves to its
 * annotation or renders plain. The hash removes all whitespace, so differences
 * in whitespace normalisation between the two extractors are irrelevant.
 */
@Serializable
data class Sidecar(
    val v: Int = 1,
    val lang: String = "zh",
    val source: String = "",
    val blocks: Map<String, List<List<String>>> = emptyMap(),
)

/** One annotated token. [source] is "cedict", "cedict-chars" or "pypinyin". */
data class Token(val word: String, val pinyin: String, val source: String) {
    val hasPinyin: Boolean get() = pinyin.isNotBlank()
    /** CJK ideographs only - punctuation and Latin have no reading row. */
    val isHan: Boolean get() = word.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
    /** Tappable if it contains a letter of any script, so German words are too. */
    val isWord: Boolean get() = word.any { it.isLetter() }
}

object Annotations {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(path: String): Sidecar =
        json.decodeFromString(Sidecar.serializer(), java.io.File(path).readText(Charsets.UTF_8))

    /** SHA-1 of the whitespace-stripped text, first 16 hex chars. Must match annotate.py. */
    fun key(text: String): String {
        val stripped = text.filterNot { it.isWhitespace() }
        val digest = MessageDigest.getInstance("SHA-1").digest(stripped.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder()
        for (b in digest.take(8)) hex.append(String.format("%02x", b))
        return hex.toString()
    }

    fun tokens(sidecar: Sidecar?, text: String): List<Token>? {
        val raw = sidecar?.blocks?.get(key(text)) ?: return null
        return raw.mapNotNull { row ->
            if (row.size < 2 || row[0].isEmpty()) null
            else Token(row[0], row[1], row.getOrElse(2) { "" })
        }.ifEmpty { null }
    }

    /** Render "[word[pinyin]]" for a ruby-capable text composable. */
    fun toRubyFormat(tokens: List<Token>): String = buildString {
        for (t in tokens) {
            if (t.hasPinyin) append('[').append(t.word).append('[').append(t.pinyin).append("]]")
            else append(t.word)
        }
    }
}
