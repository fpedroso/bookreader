package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers in-app pinyin generation, which is what lets a Chinese EPUB be imported and read
 * without the PC pass.
 *
 * Both functions take the dictionary as a lambda, so the entire chain runs here with a
 * fake - no Android, no SQLite, no assets, no device.
 */
class PinyinGenTest {

    /** A miniature CC-CEDICT: enough to exercise longest-match and the fallback chain. */
    private val db = mapOf(
        "我" to "wǒ",
        "爱" to "ài",
        "中国" to "Zhōng guó",
        "人" to "rén",
        "研究" to "yán jiū",
        "研究生" to "yán jiū shēng",
        "生" to "shēng",
        "不" to "bù",
        "敢" to "gǎn",
        "爸爸" to "bà ba",
    )

    private fun lookup(w: String): String? = db[w]

    private fun headword(w: String): Boolean = lookup(w) != null

    @Test
    fun prefersTheLongestHeadword() {
        // 研究生 must win over 研究 + 生 - reading selection depends on this.
        assertEquals(listOf("研究生"), PinyinGen.segment("研究生") { headword(it) })
        assertEquals(listOf("我", "爱", "中国"), PinyinGen.segment("我爱中国") { headword(it) })
    }

    @Test
    fun keepsPunctuationAndLatinRunsAsTokens() {
        // Dropping punctuation deletes every 、，。 from the rendered book and makes the
        // words either side of a sentence look run together.
        assertEquals(
            listOf("我", "爱", "中国", "。", "Hello", "!"),
            PinyinGen.segment("我爱中国。Hello!") { headword(it) },
        )
    }

    @Test
    fun whitespaceIsDropped() {
        assertEquals(listOf("我", "爱"), PinyinGen.segment("我 爱") { headword(it) })
    }

    @Test
    fun wordReadingsComeFromTheDictionary() {
        val toks = PinyinGen.annotate("我爱中国") { lookup(it) }!!
        assertEquals(listOf("我", "爱", "中国"), toks.map { it.word })
        assertEquals(listOf("cedict", "cedict", "cedict"), toks.map { it.source })
        assertEquals("Zhōng guó", toks[2].pinyin)
    }

    @Test
    fun neutralToneSurvives() {
        // The whole reason CC-CEDICT is used instead of a pinyin library: 爸爸 is "bà ba",
        // not pypinyin's "bà bà".
        val toks = PinyinGen.annotate("爸爸") { lookup(it) }!!
        assertEquals(listOf("爸爸"), toks.map { it.word })
        assertEquals("bà ba", toks[0].pinyin)
    }

    @Test
    fun groupedNonHeadwordFallsBackToPerCharacterReadings() {
        // Maximal matching never produces this shape, so a grouping segmenter is injected
        // to exercise the branch that mirrors annotate.py's jieba fallback.
        val toks = PinyinGen.annotate(
            "不敢",
            lookup = { lookup(it) },
            segmenter = { _, _ -> listOf("不敢") },
        )!!
        assertEquals(listOf("不敢"), toks.map { it.word })
        assertEquals("cedict-chars", toks[0].source)
        assertEquals("bù gǎn", toks[0].pinyin)
    }

    @Test
    fun unknownCharacterIsLeftUnreadRatherThanInvented() {
        // There is no pypinyin on device. A character with no reading gets none, but the
        // token is still emitted, so it renders and stays tappable for its meaning.
        val toks = PinyinGen.annotate("龙猫") { lookup(it) }!!
        assertTrue(toks.all { it.source == "unresolved" })
        assertTrue(toks.none { it.hasPinyin })
        assertEquals(listOf("龙", "猫"), toks.map { it.word })
    }

    @Test
    fun pureLatinBlockIsNotAnnotatedAtAll() {
        // annotate.py stores nothing for blocks with no Han; neither do we, so a Latin
        // block is not silently turned into a token list.
        assertNull(PinyinGen.annotate("Hello world!") { lookup(it) })
    }

    @Test
    fun hanDetectionCoversExtensionsAndRejectsLatin() {
        assertTrue(PinyinGen.isHan('中'))
        assertTrue(PinyinGen.isHan('\u3400'))
        assertTrue(!PinyinGen.isHan('A'))
        assertTrue(!PinyinGen.isHan('。'))
    }
}
