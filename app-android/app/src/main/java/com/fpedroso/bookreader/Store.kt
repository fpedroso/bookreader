package com.fpedroso.bookreader

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class BookMeta(
    val id: String,
    val title: String,
    val epubFile: String,
    /** dc:language as imported, e.g. "de", or "" when the EPUB carried none. */
    val lang: String = "",
    val addedAt: Long = 0L,
    val lastBlock: Int = 0,
)

@Serializable
data class LibraryState(val books: List<BookMeta> = emptyList())

/** Plain-file library: no database dependency for v1. */
object Store {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun booksDir(context: Context): File =
        File(context.filesDir, "books").apply { mkdirs() }

    private fun manifest(context: Context) = File(context.filesDir, "library.json")

    fun load(context: Context): LibraryState {
        val f = manifest(context)
        if (!f.exists()) return LibraryState()
        return runCatching {
            json.decodeFromString(LibraryState.serializer(), f.readText())
        }.getOrElse { LibraryState() }
    }

    fun save(context: Context, state: LibraryState) {
        manifest(context).writeText(json.encodeToString(LibraryState.serializer(), state))
    }

    fun add(context: Context, meta: BookMeta) {
        val state = load(context)
        save(context, state.copy(books = state.books + meta))
    }

    fun remove(context: Context, id: String) {
        val state = load(context)
        save(context, state.copy(books = state.books.filterNot { it.id == id }))
        File(booksDir(context), id).deleteRecursively()
    }

    fun setProgress(context: Context, id: String, lastBlock: Int) {
        val state = load(context)
        save(
            context,
            state.copy(books = state.books.map { if (it.id == id) it.copy(lastBlock = lastBlock) else it }),
        )
    }

    /** Last 12 hex chars of the SHA-1 of the title - a stable-enough id. */
    fun idFor(title: String): String =
        Annotations.key(title).takeLast(12)
}

/**
 * The block hash.
 *
 * SHA-1 of the text with all whitespace removed, first 16 hex chars, so the same text hashes
 * the same however the extractor happened to normalise it. Also the basis of the translation
 * cache key - see [translationKey].
 */
object Annotations {
    fun key(text: String): String {
        // NBSP counts as whitespace here even though Char.isWhitespace() says otherwise
        // (Java excludes non-breaking spaces).
        val stripped = text.filterNot { it.isWhitespace() || it == '\u00A0' }
        val digest = MessageDigest.getInstance("SHA-1").digest(stripped.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder()
        for (b in digest.take(8)) hex.append(String.format("%02x", b))
        return hex.toString()
    }
}

/**
 * The translation cache key for a unit of text.
 *
 * The hash of the SOURCE TEXT - deliberately not `"<blockhash>#<index>"` any more.
 *
 * The block-and-index form looked stable and was not: when the sentence splitter changed, an
 * unchanged block's index 0 became a different piece of text, so the cache handed back the
 * translation of the OLD unit and the modal showed a translation that did not match the source
 * printed above it. Tapping `Mr. und Mrs.` showed the translation of the entire sentence that
 * fragment used to be part of - which reads as a translation-quality problem, not a key problem,
 * and is exactly how it was nearly misdiagnosed.
 *
 * The text is the only thing a translation depends on, so it is the only thing the key may
 * depend on. Identical text shares one entry wherever it appears in the book; different text
 * cannot collide, whatever the splitter does next.
 */
fun translationKey(source: String): String = Annotations.key(source)
