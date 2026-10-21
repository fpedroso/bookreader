package com.fpedroso.bookreader

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Measuring a book into pages, and remembering the result.
 *
 * This is the only Compose-dependent half of pagination: [Paginate] does the arithmetic on numbers,
 * and this asks the text engine how tall each block comes out. Everything else in the reader -
 * word taps, frequency shading, translation - knows nothing about any of it.
 *
 * The whole BOOK is measured, not the chapter you are on, because the page counter is the book's
 * ("3 of 39"). That pass is the one real cost of pagination, so the result is cached on disk against
 * [LayoutSignature]: re-opening a book is instant, and only a font-size or screen-size change (or a
 * new book) pays for it again.
 */
object Pages {

    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(context: Context) = File(context.filesDir, "pages").apply { mkdirs() }

    private fun cacheFile(context: Context, bookId: String, signature: String) =
        File(dir(context), "$bookId-$signature.json")

    /**
     * Measure every row at [width] px and pack the whole book into pages.
     *
     * Chunked on the caller's dispatcher so a novel does not freeze the UI while it is measured; the
     * caller shows a paginating state. The same [ReaderMetrics] the page will be DRAWN with describes
     * the text here, which is what makes the two agree.
     */
    suspend fun measure(
        measurer: TextMeasurer,
        rows: List<RowItem>,
        metrics: ReaderMetrics,
        width: Int,
        pageHeight: Float,
    ): List<Page> {
        val lines = ArrayList<MeasuredLine>(rows.size * 6)
        for ((index, row) in rows.withIndex()) {
            val result = measurer.measure(
                text = AnnotatedString(row.text),
                style = metrics.style(row.heading),
                // The constraints trap: without this, measure() lays the text out at infinite width
                // and the line count is nonsense. This must be the width the page actually draws at.
                constraints = Constraints(maxWidth = width),
            )
            val count = result.lineCount
            for (line in 0 until count) {
                // A line's end is taken from the NEXT line's start (and the last from the text's
                // length) rather than from getLineEnd: that way the line ranges tile the block
                // exactly - every character in exactly one slice, no gap and no overlap - which is
                // what lets a page draw a block in slices without the text shifting underneath.
                val start = result.getLineStart(line)
                val end = if (line + 1 < count) result.getLineStart(line + 1) else row.text.length
                if (end <= start) continue
                val top = if (line == 0) 0f else result.getLineBottom(line - 1)
                lines.add(
                    MeasuredLine(
                        block = index,
                        start = start,
                        end = end,
                        height = result.getLineBottom(line) - top,
                        gapAbove = if (line == 0) metrics.gapBefore(row.heading) else 0f,
                    ),
                )
            }
            // A heading owns the space below it too, exactly as its own bottom padding does in the
            // scrolling reader - otherwise a page break after a heading would crowd it.
            if (row.heading && count > 0) {
                val lastIndex = lines.size - 1
                val last = lines[lastIndex]
                lines[lastIndex] = last.copy(height = last.height + metrics.headGapBottomPx)
            }
            if (index % 48 == 0) yield()
        }
        return Paginate.pages(lines, pageHeight)
    }

    /** The cached page map for this book and layout, or null when there is none to trust. */
    fun load(context: Context, bookId: String, signature: String, rows: Int): List<Page>? {
        val f = cacheFile(context, bookId, signature)
        if (!f.exists()) return null
        return runCatching {
            val cached = json.decodeFromString(Cached.serializer(), f.readText())
            // A page map measured against a different number of blocks is worse than none at all:
            // every slice would point at the wrong text.
            if (cached.rows != rows) null else cached.pages
        }.getOrNull()
    }

    /** Remember the page map, and drop this book's other layouts (a font change is not a history). */
    fun save(context: Context, bookId: String, signature: String, rows: Int, pages: List<Page>) {
        runCatching {
            val keep = cacheFile(context, bookId, signature)
            dir(context).listFiles()
                ?.filter { it.name.startsWith("$bookId-") && it.name != keep.name }
                ?.forEach { it.delete() }
            keep.writeText(json.encodeToString(Cached.serializer(), Cached(rows, pages)))
        }
    }

    /** Forget a book's page maps - when it is removed, or when it is imported again. */
    fun forget(context: Context, bookId: String) {
        runCatching {
            dir(context).listFiles()?.filter { it.name.startsWith("$bookId-") }?.forEach { it.delete() }
        }
    }

    @Serializable
    private data class Cached(val rows: Int, val pages: List<Page>)
}
