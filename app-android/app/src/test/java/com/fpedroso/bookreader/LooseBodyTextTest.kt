package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Extraction of a real-world EPUB shape: prose as *bare text nodes* inside `<body>`, using
 * empty `<p>` tags as spacers.
 *
 * This is what ebook splitters and converters emit, and it is how a real commercial novel
 * arrived: 3,009 paragraphs of prose in 15 documents. The app extracted 16 blocks - its
 * headings - and nothing else. Both existing fixtures put their text inside `<p>` elements,
 * so nothing in the suite could reproduce it, and a fix that was *still wrong* for this
 * shape (a single collector torn down by the closing `</h2>`) passed every test.
 *
 * The OPF is deliberately written with `dc:` prefixes as real EPUBs use them, which also
 * covers the namespace bug that made `dc:language` and `dc:title` invisible.
 */
class LooseBodyTextTest {

    private fun epubWith(body: String): File {
        val f = File.createTempFile("loose", ".epub")
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
                       <dc:title>Loose Text Book</dc:title>
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

    private fun texts(body: String): List<String> =
        EpubParser.parse(epubWith(body)).chapters.flatMap { it.blocks }.map { it.text }

    @Test
    fun proseInBareBodyTextIsExtracted() {
        val out = texts(
            """<h2 class="chapter">Ein Junge Überlebt </h2> Mr. und Mrs. Dursley im
               Ligusterweg Nummer 4 waren stolz darauf. <p></p> Danach kam noch mehr Text.""",
        )
        assertTrue("heading missing: $out", out.any { it == "Ein Junge Überlebt" })
        assertTrue(
            "prose before the empty <p> was dropped: $out",
            out.any { it.startsWith("Mr. und Mrs. Dursley") },
        )
        assertTrue(
            "prose AFTER the empty <p> was dropped - this is the bug a single collector had: $out",
            out.any { it.startsWith("Danach kam") },
        )
    }

    @Test
    fun blocksStayInDocumentOrder() {
        val out = texts("erstens. <p></p> zweitens. <p></p> drittens.")
        assertEquals(listOf("erstens.", "zweitens.", "drittens."), out)
    }

    @Test
    fun normalParagraphsAreUnaffected() {
        // The semantic case must not regress: each <p> is still exactly one block.
        assertEquals(listOf("Erster Absatz.", "Zweiter Absatz."), texts("<p>Erster Absatz.</p><p>Zweiter Absatz.</p>"))
    }

    @Test
    fun emptySpacersProduceNoBlocks() {
        assertEquals(listOf("nur einmal"), texts("nur einmal<p></p><p></p><p></p>"))
    }

    @Test
    fun namespacedMetadataIsRead() {
        // dc:language and dc:title are prefixed, as in every real EPUB. Comparing raw tag
        // names meant neither was ever matched, so language detection was silently broken.
        val book = EpubParser.parse(epubWith("Text."))
        assertEquals("de", book.language)
        assertEquals("Loose Text Book", book.title)
    }
}
