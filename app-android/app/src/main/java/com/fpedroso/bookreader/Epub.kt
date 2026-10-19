package com.fpedroso.bookreader

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Minimal EPUB (3.x) reader: OPF spine order + block-level text extraction.
 *
 * Hand-rolled on java.util.zip + android's built-in XmlPullParser rather than
 * Readium, whose releases past 3.1.2 demand compileSdk 36/37 plus core library
 * desugaring. Our fixtures are plain EPUB3, so spec coverage was not worth the
 * toolchain risk.
 *
 * The extraction rule here MUST match tools/annotate.py, because the pinyin
 * sidecar is keyed by a hash of each block's non-whitespace characters.
 * Rule: innermost block element wins (a block that contains another block
 * does not itself emit text).
 */
object EpubParser {

    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "dd", "dt", "td", "pre"
    )
    private val SKIP_TAGS = setOf("script", "style", "head", "title", "rt", "rp")

    data class Block(val text: String, val heading: Boolean)
    data class Chapter(val title: String?, val blocks: List<Block>)
    data class Book(
        val title: String?,
        val chapters: List<Chapter>,
        /** Normalised 2-letter code from dc:language, e.g. "de". Drives dictionary choice. */
        val language: String?,
    ) {
        val blockCount: Int get() = chapters.sumOf { it.blocks.size }
    }

    fun parse(epub: File): Book = ZipFile(epub).use { zip ->
        val opfPath = zip.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.endsWith(".opf", true) }
            ?: error("No OPF found - is this an EPUB?")
        val base = opfPath.substringBeforeLast('/', "")

        var bookTitle: String? = null
        var rawLang: String? = null
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        val opf = zip.getInputStream(zip.getEntry(opfPath)).use { it.readBytes() }
        eachElement(opf) { name, attrs, depth, text ->
            when (name) {
                "item" -> attrs["id"]?.let { id -> attrs["href"]?.let { h -> manifest[id] = h } }
                "itemref" -> {
                    // Respect linear="no" (cover, colophon) the way readers do.
                    if (attrs["linear"] != "no") attrs["idref"]?.let { spine.add(it) }
                }
                // dc:title / dc:language sit under package > metadata, i.e. depth 3.
                "title" -> if (depth <= 4 && bookTitle == null && !text.isNullOrBlank()) {
                    bookTitle = text.trim()
                }
                "language" -> if (depth <= 4 && rawLang == null && !text.isNullOrBlank()) {
                    rawLang = text.trim()
                }
            }
        }
        // "de-DE" / "ger" / "zh-Hans" -> "de" / "de" / "zh"
        val language = rawLang?.take(2)?.lowercase()

        val chapters = ArrayList<Chapter>()
        for (idref in spine) {
            val href = manifest[idref] ?: continue
            val path = normalize(base, href)
            val entry = zip.getEntry(path) ?: zip.entries().asSequence()
                .firstOrNull { it.name.equals(path, true) } ?: continue
            if (!Regex("\\.x?html?$", RegexOption.IGNORE_CASE).containsMatchIn(entry.name)) continue
            val bytes = zip.getInputStream(entry).use(InputStream::readBytes)
            val (chapterTitle, blocks) = parseChapter(bytes)
            if (blocks.isNotEmpty()) chapters.add(Chapter(chapterTitle, blocks))
        }
        Book(bookTitle, chapters, language)
    }

    /**
     * Uses XmlPullParserFactory rather than android.util.Xml so the exact same
     * parser runs under JVM unit tests (with kxml2 on the test classpath), which
     * is how BlockHashAgreementTest proves this and annotate.py agree.
     */
    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser()
    }

    /** Pull-parser pass over one XHTML resource. */
    private fun parseChapter(bytes: ByteArray): Pair<String?, List<Block>> {
        val parser = newParser()
        parser.setInput(bytes.inputStream(), null)

        val blocks = ArrayList<Block>()
        var chapterTitle: String? = null
        var depth = 0
        var skipDepth = -1            // >0 while inside script/style/head/title/rt/rp
        var collectDepth = -1         // depth of the block currently emitting text
        var sb: StringBuilder? = null
        var headingNow = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = parser.name.lowercase()
                    if (skipDepth < 0 && name in SKIP_TAGS) skipDepth = depth
                    // A <br> is a real line break *inside* one block: Struwwelpeter keeps a
                    // whole stanza in a single <p> with <br/> between the lines, so 52 of
                    // its paragraphs are verse that would otherwise flatten into prose.
                    if (skipDepth < 0 && name == "br") sb?.append(BR)
                    if (skipDepth < 0 && name in BLOCK_TAGS) {
                        // Innermost block wins: a nested block abandons the outer one.
                        collectDepth = depth
                        sb = StringBuilder()
                        headingNow = name.startsWith("h") && name.length == 2
                    }
                }
                XmlPullParser.TEXT -> {
                    if (collectDepth > 0 && skipDepth < 0) sb?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (skipDepth == depth) skipDepth = -1
                    if (collectDepth == depth && name in BLOCK_TAGS) {
                        val t = sb?.toString()
                            // Collapse every whitespace run, including the newlines and
                            // indentation that Gutenberg's HTML formatting leaves inside a
                            // paragraph, so only genuine <br> breaks survive.
                            ?.replace(WS, " ")
                            ?.replace(BR, "\n")
                            ?.replace(BREAK_PAD, "\n")
                            ?.replace(BREAK_RUN, "\n")
                            ?.trim()
                        if (!t.isNullOrEmpty()) blocks.add(Block(t, headingNow))
                        collectDepth = -1
                        sb = null
                    }
                    depth--
                }
            }
            event = parser.next()
        }
        return chapterTitle to blocks
    }

    // ------------------------------------------------------------ opf helpers

    private fun eachElement(
        bytes: ByteArray,
        onTag: (name: String, attrs: Map<String, String>, depth: Int, text: String?) -> Unit,
    ) {
        val parser = newParser()
        parser.setInput(bytes.inputStream(), null)
        var depth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val attrs = HashMap<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        attrs[parser.getAttributeName(i).lowercase()] = parser.getAttributeValue(i)
                    }
                    onTag(parser.name.lowercase(), attrs, depth, null)
                }
                XmlPullParser.TEXT -> onTag("", emptyMap(), depth, parser.text)
                XmlPullParser.END_TAG -> depth--
            }
            event = parser.next()
        }
    }

    private fun normalize(base: String, href: String): String {
        val raw = if (base.isEmpty()) href else "$base/$href"
        val stack = ArrayList<String>()
        for (part in raw.replace('\\', '/').split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    /**
     * Sentinel for a real line break, swapped for "\n" only after whitespace is collapsed.
     *
     * It has to be non-whitespace so the collapsing pass cannot eat it, and it has to be
     * *gone* from the final text, because the block hash strips whitespace - a surviving
     * newline is invisible to the hash, which is exactly what keeps these line breaks
     * compatible with sidecars built on the PC, where no <br> handling exists.
     */
    private const val BR = "\u241E"
    private val BREAK_PAD = Regex(" *\\n *")
    private val BREAK_RUN = Regex("\\n{2,}")
    /** NBSP is whitespace for our purposes but not for [Char.isWhitespace]. */
    private val WS = Regex("[ \\t\\r\\n\\f\\u000B\\u00A0]+")
}
