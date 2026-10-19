package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Per-block language resolution: which language each block is in, from the declarations the
 * EPUB format itself provides.
 *
 * The bug this pins: the language was taken from the OPF's `dc:language` alone, so a German
 * novel whose front matter is English sent that front matter to the German->English model.
 * Every block came back untouched, and nothing said why.
 *
 * Precedence, and therefore what these tests assert:
 *   1. the nearest ancestor `lang`/`xml:lang` (including the block's own attribute)
 *   2. the document's `<html lang>` - the outermost ancestor, same mechanism
 *   3. the book's `dc:language`
 *   (4. the import-time hint and 5. ML Kit Language Identification live in the app, not here)
 *
 * The OPF is written with `dc:` prefixes, as real EPUBs do, because a parser that reads raw
 * tag names cannot see `dc:language` at all.
 */
class BlockLanguageTest {

    /**
     * One EPUB whose spine is `docs`, in order. Each document is written verbatim so a test
     * can control exactly where the language is declared.
     */
    private fun epubWith(vararg docs: String): File {
        val f = File.createTempFile("langs", ".epub")
        f.deleteOnExit()
        val manifest = docs.indices.joinToString("") {
            """<item id="c$it" href="c$it.html" media-type="application/xhtml+xml"/>"""
        }
        val spine = docs.indices.joinToString("") { """<itemref idref="c$it"/>""" }
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
                       <dc:title>Two Languages</dc:title>
                       <dc:language>de</dc:language>
                     </metadata>
                     <manifest>$manifest</manifest>
                     <spine>$spine</spine>
                   </package>""",
            )
            docs.forEachIndexed { i, body ->
                put(
                    "c$i.html",
                    """<?xml version="1.0" encoding="utf-8"?>
                       <html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head>
                       <body>$body</body></html>""",
                )
            }
        }
        return f
    }

    /** Blocks of one document, as (text, language). */
    private fun blocks(doc: String): List<Pair<String, String?>> =
        EpubParser.parse(epubWith(doc)).chapters.flatMap { it.blocks }.map { it.text to it.lang }

    private fun langOf(doc: String, text: String): String? =
        blocks(doc).first { it.first == text }.second

    @Test
    fun bookLanguageIsTheFallbackWhenTheDocumentSaysNothing() {
        // Level 3: no lang attribute anywhere in the document, so dc:language ("de") stands.
        assertEquals("de", langOf("<p>Ganz normal.</p>", "Ganz normal."))
    }

    @Test
    fun htmlLangIsUsedBeforeTheBookLanguage() {
        // Level 2. This is the shape that matters: Project Gutenberg's German books put their
        // English "this eBook is for the use of anyone anywhere" boilerplate in a document
        // whose <html lang="en">, while dc:language says de.
        val doc = """<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
                     <head><title>x</title></head>
                     <body><p>This eBook is for the use of anyone anywhere.</p></body></html>"""
        // Nested <html> is not well-formed, so build the document by hand instead of via the
        // helper's wrapper: the helper's <html> carries no lang, which is what we want here
        // except for the attribute under test.
        val f = File.createTempFile("langs", ".epub")
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
                   <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                     <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                       <dc:title>Two Languages</dc:title>
                       <dc:language>de</dc:language>
                     </metadata>
                     <manifest><item id="c0" href="c0.html" media-type="application/xhtml+xml"/></manifest>
                     <spine><itemref idref="c0"/></spine>
                   </package>""",
            )
            put("c0.html", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$doc")
        }
        val parsed = EpubParser.parse(f)
        assertEquals("de", parsed.language)
        assertEquals(
            listOf("This eBook is for the use of anyone anywhere." to "en"),
            parsed.chapters.flatMap { it.blocks }.map { it.text to it.lang },
        )
    }

    @Test
    fun elementLangWinsOverTheBookLanguage() {
        // Level 1, and the case that actually broke: an element inside a German book that is
        // English. Without it the block went to the German model and came back unchanged.
        assertEquals("en", langOf("""<p lang="en">All rights reserved.</p>""", "All rights reserved."))
    }

    @Test
    fun nearestAncestorWinsOverAnOuterDeclaration() {
        // Innermost declaration wins, and the German attribute must apply to the block that
        // follows the English one - a stack that never pops would leak "en" onto it.
        val doc = """<div lang="en"><p>Dedicated to.</p><p lang="de">Für Jessica.</p></div><p>Danach.</p>"""
        assertEquals("en", langOf(doc, "Dedicated to."))
        assertEquals("de", langOf(doc, "Für Jessica."))
        assertEquals("de", langOf(doc, "Danach."))
    }

    @Test
    fun regionSubtagsAreReducedToTwoLetters() {
        // "en-US" has to arrive as "en": everything downstream works in two-letter codes.
        assertEquals("en", langOf("""<p lang="en-US">Color.</p>""", "Color."))
    }

    @Test
    fun undeterminedIsNotAnAnswer() {
        // `und` is ISO 639's own "I do not know". Pinning it on the block would put an
        // unusable language in front of the model instead of falling through to dc:language.
        assertEquals("de", langOf("""<p lang="und">Text.</p>""", "Text."))
    }
}
