package com.fpedroso.bookreader

import kotlinx.serialization.Serializable

/**
 * Turning measured lines into pages. Pure: no Compose, no text, no knowledge of words or
 * translation. Heights in, pages out - so the packing rules are unit-tested with fake numbers
 * rather than by looking at a screen.
 *
 * The rest of the reader knows none of this. Pagination decides WHAT TEXT is on screen; the word
 * tap, the frequency shading and the translation modal react to words within whatever is on screen.
 * Either layer can be replaced without the other noticing, which is the point.
 */

/** One line of one block, as the measurer found it. Heights are device pixels. */
data class MeasuredLine(
    /** Index into the reader's row list. */
    val block: Int,
    /** Character range of this line within the block's own text. */
    val start: Int,
    val end: Int,
    /** The vertical space this line occupies, leading included. */
    val height: Float,
    /** The paragraph or heading gap that must precede it; 0 for a wrapped continuation line. */
    val gapAbove: Float,
)

/**
 * A run of consecutive lines of ONE block that share a page.
 *
 * Character offsets are into the block's text, not into the slice - which is what lets a tap on the
 * page resolve to a word the same way it did before pagination existed, and what makes the reading
 * anchor (block + offset) independent of where the page breaks fell.
 */
@Serializable
data class PageSlice(
    val block: Int,
    val start: Int,
    val end: Int,
    /** How many lines of [block] this slice draws - the `maxLines` for rendering it. */
    val lines: Int,
    /** Space above this slice on the page. 0 for the first slice. */
    val gapAbove: Float,
)

/** One page: its slices, top to bottom. */
typealias Page = List<PageSlice>

object Paginate {

    /**
     * Pack [lines] into pages of at most [pageHeight].
     *
     * Two rules that matter and are easy to get wrong:
     *
     * - **A page break has no leading gap.** The space before a paragraph belongs between two
     *   paragraphs, not at the top of a page; CSS says the same thing about margins at a break in a
     *   multicolumn flow. So the first slice of every page takes `gapAbove = 0`.
     * - **Every page gets at least one line.** A line taller than the page (a giant heading on a
     *   small screen) would otherwise never fit, and the loop would spin forever on it.
     *
     * Lines of the same block that end up together are merged into ONE slice, so a page draws a
     * handful of Texts rather than one per line.
     */
    fun pages(lines: List<MeasuredLine>, pageHeight: Float): List<Page> {
        if (lines.isEmpty() || pageHeight <= 0f) return emptyList()
        val out = ArrayList<Page>()
        var page = ArrayList<PageSlice>()
        var used = 0f

        for (line in lines) {
            val first = page.isEmpty()
            // The leading gap is dropped at the top of a page, and only there.
            val gap = if (first) 0f else line.gapAbove
            if (!first && used + gap + line.height > pageHeight) {
                out.add(page)
                page = ArrayList()
                used = 0f
                // This same line starts the new page, with no gap above it.
                page.add(PageSlice(line.block, line.start, line.end, 1, 0f))
                used = line.height
                continue
            }
            val last = page.lastOrNull()
            if (last != null && last.block == line.block && last.end == line.start) {
                // A continuation line of the slice already open: extend it in place.
                page[page.size - 1] = last.copy(end = line.end, lines = last.lines + 1)
            } else {
                page.add(PageSlice(line.block, line.start, line.end, 1, gap))
            }
            used += gap + line.height
        }
        if (page.isNotEmpty()) out.add(page)
        return out
    }

    /**
     * The page containing a position, as a page index - used to restore the reading position after
     * a reflow, when the page NUMBER the reader was on no longer means what it did.
     *
     * A position is inside a slice when its block matches and its offset falls in the slice's range.
     * Falling back to the last slice that starts at or before it keeps a page turn sane when the
     * exact offset has moved (a book re-imported with different text).
     */
    fun pageOf(pages: List<Page>, block: Int, offset: Int): Int {
        if (pages.isEmpty()) return 0
        var best = 0
        for ((index, page) in pages.withIndex()) {
            for (slice in page) {
                if (slice.block == block && offset >= slice.start && offset < slice.end) return index
                if (slice.block == block && slice.start <= offset) best = index
            }
        }
        return best
    }
}

/**
 * Which way a tap turns the page, as the reader asked for it: thirds of the page, the left going
 * back, the right going forward, and the middle doing nothing - a tap while reading is not always a
 * request to move. Null means "no page turn".
 *
 * Pure, so which zone a tap lands in is a test rather than something to check by eye.
 */
fun zoneTurn(x: Float, width: Float): Boolean? = when {
    width <= 0f -> null
    x < width / 3f -> false
    x > width * 2f / 3f -> true
    else -> null
}

/**
 * A word whose offsets are into a SLICE, re-expressed as offsets into the whole BLOCK.
 *
 * The one place pagination and the word machinery actually touch. A page draws a block in slices
 * cut at line breaks, and a tap resolves its word against the slice that was drawn - but everything
 * downstream (the sentence around the word, the cache key, the reading anchor) thinks in blocks.
 * Getting this wrong translates the wrong word, which is why it is a named function with a test
 * rather than an addition inlined at the gesture.
 */
fun blockWord(word: Words.Word, slice: PageSlice): Words.Word =
    word.copy(start = word.start + slice.start, end = word.end + slice.start)

/**
 * What a page layout depends on. Any change to these invalidates a cached pagination, because the
 * same words then occupy a different number of lines.
 *
 * Values are pixels rather than dp/sp so the signature cannot claim two different screens are the
 * same layout.
 */
data class LayoutSignature(
    val widthPx: Int,
    val heightPx: Int,
    val bodyPx: Float,
    val linePx: Float,
    val paraGapPx: Float,
    val headGapTopPx: Float,
    val headGapBottomPx: Float,
    val headLinePx: Float,
) {
    /** A stable id for a cache file name, and for noticing that a reflow is needed. */
    fun id(): String = listOf(
        widthPx, heightPx, bodyPx, linePx, paraGapPx, headGapTopPx, headGapBottomPx, headLinePx,
    ).joinToString("-") { it.toInt().toString() }
}
