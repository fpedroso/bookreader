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
 * SHA-1 of the text with all whitespace removed, first 16 hex chars. It is the translation
 * cache key (`"<blockhash>#<index>"`), so it must be stable across imports, scrolls and
 * re-reads. Whitespace is stripped so a block hashes the same however the extractor
 * happened to normalise it.
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
