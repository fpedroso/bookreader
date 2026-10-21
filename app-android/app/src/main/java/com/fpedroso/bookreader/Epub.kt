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
 * Each block's text is hashed by Annotations.key (whitespace stripped) to key the
 * translation cache, so what counts as one block matters - changing this rule would
 * orphan cached translations.
 * Rule: innermost block element wins (a block that contains another block
 * does not itself emit text).
 */
object EpubParser {

    private val BLOCK_TAGS = setOf(
        // `body` and `div` are here because a container's OWN text is a block. Converter and
        // ebook-splitter output dumps prose as bare text nodes inside <body> with empty <p>
        // tags as spacers; one of the books this reader shipped for is exactly that shape, and
        // treating only leaf block elements as blocks extracted its headings and dropped
        // 500 KB of prose.
        "body", "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "dd", "dt", "td", "pre"
    )
    private val SKIP_TAGS = setOf("script", "style", "head", "title", "rt", "rp")

    /**
     * One block of text, with the language that block is written in.
     *
     * [lang] is the language the document itself declares for this block: the nearest
     * ancestor `lang`/`xml:lang`, then the document's `<html lang>`, then the book's
     * `dc:language`. Null only when the document carried no declaration at all, which leaves
     * the caller two more levels (the import-time hint, then Language Identification).
     *
     * Per block rather than per book because a book's declared language is often wrong for
     * part of it: a German novel's front matter is routinely English, and sending that prose
     * to the German model translates nothing and says nothing.
     */
    data class Block(
        val text: String,
        val heading: Boolean,
        val lang: String? = null,
        /**
         * 1-6 for an `<h1>`-`<h6>`, 0 for prose.
         *
         * Kept alongside [heading] rather than replacing it because the two answer different
         * questions: `heading` means "set this apart as a title" at any level, while the LEVEL is
         * what says whether a chapter starts here. A book's chapter title is an `<h1>` or `<h2>`;
         * an `<h3>` inside a story is a section, and starting a page for each of those would shred
         * the prose it belongs to.
         */
        val level: Int = 0,
    ) {
        /**
         * Whether a chapter opens at this block, as far as the BODY can tell.
         *
         * Not the whole rule: a new spine document opens a chapter too, and some books signal
         * chapters that way alone (see `buildRows`, where the two are combined).
         */
        val chapterLevel: Boolean get() = heading && level in 1..2
    }

    /**
     * The blocks of one document in the spine.
     *
     * No title field: the parser never had a source for one. A chapter title would come from the
     * EPUB's NCX/nav TOC, which this parser does not read, and the natural fallback - the
     * document's `<title>` - is deliberately skipped (see SKIP_TAGS), so it is never body text.
     * Headings inside the body are how a chapter announces itself here.
     */
    data class Chapter(val blocks: List<Block>)
    data class Book(
        val title: String?,
        val chapters: List<Chapter>,
        /** Normalised 2-letter code from dc:language, e.g. "de". Drives sentence splitting. */
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
            val blocks = parseChapter(bytes)
            // dc:language is the third level of the per-block precedence, applied here so a
            // block that carries no language of its own still arrives with one.
            val blocksWithLang = blocks.map { if (it.lang == null) it.copy(lang = language) else it }
            if (blocksWithLang.isNotEmpty()) chapters.add(Chapter(blocksWithLang))
        }
        Book(bookTitle, chapters, language)
    }

    /**
     * Uses XmlPullParserFactory rather than android.util.Xml so the exact same
     * parser runs under JVM unit tests (with kxml2 on the test classpath).
     */
    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser()
    }

    /** Pull-parser pass over one XHTML resource. */
    private fun parseChapter(bytes: ByteArray): List<Block> {
        val parser = newParser()
        parser.setInput(bytes.inputStream(), null)

        val blocks = ArrayList<Block>()
        var depth = 0
        var skipDepth = -1            // >0 while inside script/style/head/title/rt/rp

        // One frame per open container.
        //
        // A container's OWN text is a block in its own right, so this has to be a STACK
        // rather than a single buffer: when a nested block starts, the parent's text so far
        // is emitted and the parent frame stays open to keep collecting whatever follows the
        // child. With a single buffer, `</h2>` tore down the body's collector and the prose
        // after it was lost - which is why a real EPUB whose prose is bare text inside
        // <body> still showed only its headings, even after the extractor was "fixed".
        class Frame(val depth: Int, val heading: Boolean, val lang: String?, val level: Int) {
            val sb = StringBuilder()
        }
        val stack = ArrayList<Frame>()

        // Element languages, innermost wins, kept with the depth so a closed element stops
        // applying to what follows it. This is the format's own mechanism: `<html lang>`,
        // an ancestor `<div xml:lang>`, or the block element's own attribute. Without it a
        // German book's English front matter is invisible - the book-level dc:language is
        // the only thing consulted and it is wrong for exactly that part of the book.
        val langs = ArrayList<Pair<Int, String>>()

        /** Emit the top frame's text as a block and reset that frame's buffer. */
        fun flushTop() {
            val f = stack.lastOrNull() ?: return
            val t = f.sb.toString()
                .replace(WS, " ")
                .replace(BR, "\n")
                .replace(BREAK_PAD, "\n")
                .replace(BREAK_RUN, "\n")
                .trim()
            if (t.isNotEmpty()) blocks.add(Block(t, f.heading, f.lang, f.level))
            f.sb.setLength(0)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    // Strip any namespace prefix: `dc:language` must read as `language`, or
                    // metadata silently never matches. Android's XmlPullParser reports the
                    // qualified name.
                    val name = parser.name.substringAfterLast(':').lowercase()
                    // Pushed before the frame below, so a block's OWN lang attribute counts
                    // as the innermost declaration, and an ancestor's stays in scope.
                    elementLang(parser)?.let { langs.add(depth to it) }
                    if (skipDepth < 0 && name in SKIP_TAGS) skipDepth = depth
                    // A <br> is a real line break *inside* one block: a verse stanza lives in
                    // a single <p> with <br/> between its lines.
                    if (skipDepth < 0 && name == "br") stack.lastOrNull()?.sb?.append(BR)
                    if (skipDepth < 0 && name in BLOCK_TAGS) {
                        // Flush the parent's own text before the child takes over, so
                        // `<body> A <p></p> B </body>` yields "A" then "B".
                        flushTop()
                        // The LEVEL, not merely "is a heading": an h1/h2 opens a chapter and the
                        // paged reader starts a page there, while an h3 inside a story is a section.
                        // Both are still drawn as titles.
                        val level = if (name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') {
                            name[1] - '0'
                        } else {
                            0
                        }
                        stack.add(Frame(depth, level > 0, langs.lastOrNull()?.second, level))
                    }
                }
                XmlPullParser.TEXT -> {
                    if (skipDepth < 0) stack.lastOrNull()?.sb?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfterLast(':').lowercase()
                    if (skipDepth == depth) skipDepth = -1
                    // Same depth guard as the frame stack: a malformed document must not pop
                    // a language that is still in scope for its siblings.
                    if (langs.isNotEmpty() && langs.last().first == depth) langs.removeAt(langs.size - 1)
                    // Only pop when the depth matches, so malformed markup cannot unbalance
                    // the stack and mis-assign text to the wrong block.
                    if (name in BLOCK_TAGS && stack.isNotEmpty() && stack.last().depth == depth) {
                        flushTop()
                        stack.removeAt(stack.size - 1)
                    }
                    depth--
                }
            }
            event = parser.next()
        }
        return blocks
    }

    // ------------------------------------------------------------ opf helpers

    /**
     * The language this element declares, from `lang` or `xml:lang`, or null.
     *
     * The parser is namespace-unaware - it is the same XmlPullParser under JVM tests - so a
     * prefixed attribute arrives as the qualified name `xml:lang`, while XHTML also allows
     * the bare `lang`. Both mean the same thing, hence the suffix match; a genuinely
     * namespaced foreign attribute ending in `:lang` is not a thing in practice, and the
     * spec's own mechanism only ever means these two.
     *
     * `und` is ISO 639's "undetermined" and is treated as no answer, so the block falls
     * through to the next level of precedence rather than pinning "und" on it.
     */
    private fun elementLang(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(i).lowercase()
            if (name != "lang" && !name.endsWith(":lang")) continue
            val raw = parser.getAttributeValue(i)?.trim()?.lowercase() ?: continue
            // Checked BEFORE truncating: "und" is three characters, so take(2) would have made
            // it "un" and quietly labelled the block "Un" - which no model can translate.
            if (raw.isEmpty() || raw == "und") continue
            // "en-US" -> "en", the same normalisation dc:language gets, because everything
            // downstream (sentence splitting, ML Kit) works in two-letter codes.
            return raw.take(2)
        }
        return null
    }

    private fun eachElement(
        bytes: ByteArray,
        onTag: (name: String, attrs: Map<String, String>, depth: Int, text: String?) -> Unit,
    ) {
        val parser = newParser()
        parser.setInput(bytes.inputStream(), null)
        var depth = 0
        // TEXT events must carry the name of the element they belong to. Reporting an empty
        // name meant `<dc:language>de</dc:language>` arrived as ("", ..., "de"), so a
        // `when (name) { "language" -> }` handler could never fire and no element's text was
        // ever readable - which is why dc:title and dc:language were both always null.
        var current = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val attrs = HashMap<String, String>()
                    for (i in 0 until parser.attributeCount) {
                        attrs[parser.getAttributeName(i).lowercase()] = parser.getAttributeValue(i)
                    }
                    current = parser.name.substringAfterLast(':').lowercase()
                    onTag(current, attrs, depth, null)
                }
                XmlPullParser.TEXT -> onTag(current, emptyMap(), depth, parser.text)
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
     * *gone* from the final text, because the block hash strips whitespace anyway - a
     * surviving newline would be invisible to the hash, so <br> line breaks cannot
     * perturb a block's cache key.
     */
    private const val BR = "\u241E"
    private val BREAK_PAD = Regex(" *\\n *")
    private val BREAK_RUN = Regex("\\n{2,}")
    /** NBSP is whitespace for our purposes but not for [Char.isWhitespace]. */
    private val WS = Regex("[ \\t\\r\\n\\f\\u000B\\u00A0]+")
}
