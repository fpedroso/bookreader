package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sentence taps on continuous prose.
 *
 * The reader renders a paragraph as ONE Text so the prose flows naturally, which means a tap
 * no longer lands on a per-sentence composable - it lands on a character offset inside that
 * paragraph, and the offset has to be mapped back to a sentence. Two things can go wrong
 * silently, and both look like a hit-testing bug rather than a splitting bug:
 *
 * 1. `Sentences.split` normalises whitespace before splitting, so its `start`/`end` are
 *    positions in the *normalised* string. If the block text rendered on screen were not
 *    already whitespace-collapsed, every offset would be shifted and every tap would
 *    translate the wrong sentence. These tests go through the real extractor, on XHTML with
 *    the indentation and line wrapping a real book has, so the coincidence is pinned rather
 *    than assumed.
 * 2. A tap can land in the whitespace *between* two sentences. It must resolve to the
 *    nearest one; never fail.
 */
class SentenceHitTestTest {

    // ------------------------------------------------------- against the extractor

    private fun epubWith(body: String): File {
        val f = File.createTempFile("hit", ".epub")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0"
                   xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>
                   <rootfile full-path="content.opf"
                   media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put(
                "content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                   <package xmlns="http://www.idpf.org/2007/opf" version="2.0"
                            unique-identifier="id">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>Hit Test</dc:title>
                       <dc:language>de</dc:language>
                     </metadata>
                     <manifest>
                       <item id="c1" href="c1.html" media-type="application/xhtml+xml"/>
                     </manifest>
                     <spine><itemref idref="c1"/></spine>
                   </package>""",
            )
            put(
                "c1.html",
                """<?xml version="1.0" encoding="utf-8"?>
                   <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head>
                   <body class="calibre">$body</body></html>""",
            )
        }
        return f
    }

    /**
     * The real German paragraph, wrapped across source lines and indented the way a
     * converter emits it, so the extractor's whitespace collapsing is part of the test.
     */
    private val PROSE = """
        <p>Mr. und Mrs. Dursley im Ligusterweg Nummer 4 waren stolz darauf, ganz und gar
           normal zu sein, sehr stolz sogar, denn mit Leuten wie den Potters wollten sie
           nichts zu tun haben. Sie waren die Letzten, von denen man erwarten konnte, dass
           sie in etwas Merkwürdiges verwickelt würden.</p>
    """.trimIndent()

    @Test
    fun offsetsMapBackToTheSentenceTextTheExtractorProduced() {
        val book = EpubParser.parse(epubWith("<h2 class=\"chapter\">Ein Junge Überlebt</h2>$PROSE"))
        val prose = book.chapters.single().blocks.single { !it.heading }
        val ranges = sentenceUnits(prose.text, heading = false, lang = "de")
        assertNotNull(ranges)
        ranges!!
        assertEquals(2, ranges.size)

        // The rendered paragraph and the ranges refer to the same string: that is exactly the
        // property the splitter's whitespace normalising would break if they diverged.
        val sentences = Sentences.split(prose.text, "de")
        assertEquals(sentences.size, ranges.size)
        ranges.forEachIndexed { i, r ->
            assertEquals(sentences[i].text, prose.text.substring(r.first, r.last + 1).trim())
        }
        assertEquals(
            "Mr. und Mrs. Dursley",
            prose.text.substring(ranges[0].first, ranges[0].first + 20),
        )
        assertTrue(prose.text.substring(ranges[0].first, ranges[0].last + 1).startsWith("Mr. und Mrs."))
        assertTrue(prose.text.substring(ranges[1].first, ranges[1].last + 1).endsWith("verwickelt würden."))
    }

    @Test
    fun rangesAreAscendingAndCoverTheParagraphWithoutGapsThatMatter() {
        val book = EpubParser.parse(epubWith(PROSE))
        val text = book.chapters.single().blocks.single().text
        val ranges = sentenceUnits(text, heading = false, lang = "de")!!

        assertEquals(0, ranges.first().first)
        assertEquals(text.length - 1, ranges.last().last)
        ranges.zipWithNext { a, b ->
            assertTrue("ranges must ascend: $a then $b", b.first > a.first)
            // The only characters between two sentences are the space after the terminator.
            val between = text.substring(a.last + 1, b.first)
            assertTrue("unexpected text between sentences: '$between'", between.all { it == ' ' })
        }
    }

    // ------------------------------------------------------------- tap resolution

    @Test
    fun aTapInsideASentenceResolvesToThatSentence() {
        val text = "Mr. und Mrs. Dursley waren stolz darauf, normal zu sein. " +
            "Als sie an einem trüben Dienstagmorgen erwachten, war draußen kein Wölkchen am Himmel."
        val ranges = sentenceUnits(text, heading = false, lang = "de")!!
        assertEquals(2, ranges.size)

        val second = text.indexOf("trüben")
        assertEquals(1, Sentences.indexAt(ranges, second))
        val first = text.indexOf("Ligusterweg").let { if (it >= 0) it else text.indexOf("stolz") }
        assertEquals(0, Sentences.indexAt(ranges, first))
    }

    @Test
    fun aTapOnTheWhitespaceBetweenSentencesPicksTheNearestSentence() {
        // Never a failure: a finger between two sentences, on the wrapping edge, or past the
        // last character still has an answer.
        val ranges = listOf(0 until 8, 9 until 20)
        assertEquals(0, Sentences.indexAt(ranges, 8))
        assertEquals(1, Sentences.indexAt(ranges, 9))
        assertEquals(0, Sentences.indexAt(ranges, -5))
        assertEquals(1, Sentences.indexAt(ranges, 99))
        assertEquals(0, Sentences.indexAt(ranges, 4))
    }

    @Test
    fun everyOffsetInTheParagraphResolvesBackToASentenceContainingIt() {
        // Exhaustive: no offset in a real paragraph may fall through to a wrong sentence.
        val text = "Er ging nach Hause. Sie blieb hier. Und dann? Nichts mehr."
        val ranges = sentenceUnits(text, heading = false, lang = "de")!!
        for (offset in text.indices) {
            val i = Sentences.indexAt(ranges, offset)
            val r = ranges[i]
            val inIt = offset in r
            // Either the offset is inside the chosen sentence, or it sits in whitespace and
            // the chosen one is adjacent - never some unrelated sentence further along.
            val adjacent = r.first - 1 <= offset && offset <= r.last + 1
            assertTrue("offset $offset -> sentence $i $r (in=$inIt)", inIt || adjacent)
        }
    }

    // --------------------------------------------------------------- exclusions

    @Test
    fun headingsAndVerseKeepTheWholeParagraph() {
        // A heading is not translated a sentence at a time; verse must not be split, because
        // the splitter would turn its line breaks into prose again.
        assertNull(sentenceUnits("Ein Junge Überlebt", heading = true, lang = "de"))
        assertNull(sentenceUnits("Sieh einmal, hier steht er,\npfui, der Struwwelpeter!", heading = false, lang = "de"))
        assertNotNull(sentenceUnits("Er ging nach Hause.", heading = false, lang = "de"))
    }
}
