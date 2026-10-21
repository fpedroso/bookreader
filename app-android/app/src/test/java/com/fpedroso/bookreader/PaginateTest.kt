package com.fpedroso.bookreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packing rules, with fake heights. If these hold, a wrong-looking page is a measurement
 * problem (see the TextMeasurer constraints trap) and not a pagination one.
 */
class PaginateTest {

    private fun line(
        block: Int,
        start: Int,
        end: Int,
        height: Float = 10f,
        gapAbove: Float = 0f,
        breakBefore: Boolean = false,
    ) = MeasuredLine(block, start, end, height, gapAbove, breakBefore)

    /** Three 10px lines of block 0 with a 5px paragraph gap before the first. */
    private fun para(block: Int, from: Int = 0, lines: Int = 3, gap: Float = 5f): List<MeasuredLine> {
        var start = from
        return (0 until lines).map { i ->
            val end = start + 10
            val l = line(block, start, end, 10f, if (i == 0) gap else 0f)
            start = end
            l
        }
    }

    @Test
    fun `no lines, no pages`() {
        assertEquals(emptyList<Page>(), Paginate.pages(emptyList(), 100f))
        assertEquals(emptyList<Page>(), Paginate.pages(para(0), 0f))
    }

    @Test
    fun `a page holds what fits and no more`() {
        // 5 + 10 + 10 + 10 = 35px in one paragraph; pages of 25 take two lines, then the third.
        val pages = Paginate.pages(para(0), 25f)
        assertEquals(2, pages.size)
        assertEquals(2, pages[0].single().lines)
        assertEquals(1, pages[1].single().lines)
    }

    @Test
    fun `a page break carries no leading gap`() {
        // Two paragraphs of three lines each, 5px gap, pages of 25px (two lines).
        val lines = para(0).take(3) + para(1, from = 30)
        val pages = Paginate.pages(lines, 25f)
        // Whatever page a slice starts on, its gap is 0; gaps only ever appear mid-page.
        for (page in pages) {
            assertEquals(0f, page.first().gapAbove, 0.001f)
        }
        assertTrue("a later slice mid-page keeps its paragraph gap",
            pages.any { page -> page.size > 1 && page.drop(1).any { it.gapAbove == 5f } })
    }

    @Test
    fun `consecutive lines of one block share a slice, a new block starts one`() {
        val lines = para(0, lines = 2) + para(1, from = 20, lines = 2)
        // Tall enough for everything: 5+10+10 + 5+10+10 = 50.
        val page = Paginate.pages(lines, 500f).single()
        assertEquals(2, page.size)
        val first = page[0]
        assertEquals(0, first.block)
        assertEquals(0, first.start)
        assertEquals(20, first.end)
        assertEquals(2, first.lines)
        val second = page[1]
        assertEquals(1, second.block)
        assertEquals(20, second.start)
        assertEquals(40, second.end)
    }

    @Test
    fun `a block split across a page continues at the right offset`() {
        val lines = para(0, lines = 4) // 5+10+10+10+10 = 45
        val pages = Paginate.pages(lines, 25f)
        assertEquals(2, pages.size)
        val a = pages[0].single()
        val b = pages[1].single()
        assertEquals(0, a.block)
        assertEquals(2, a.lines)
        assertEquals(20, a.end)
        // The continuation starts exactly where the first slice stopped: one character, no gap,
        // and no line drawn twice.
        assertEquals(0, b.block)
        assertEquals(a.end, b.start)
        assertEquals(2, b.lines)
        assertEquals(40, b.end)
    }

    @Test
    fun `every page gets at least one line even when a line is taller than the page`() {
        val huge = MeasuredLine(0, 0, 10, 400f, 0f)
        val normal = MeasuredLine(1, 0, 10, 10f, 5f)
        val pages = Paginate.pages(listOf(huge, normal), 100f)
        assertEquals(2, pages.size)
        assertEquals(0, pages[0].single().block)
        assertEquals(1, pages[0].single().lines)
        assertEquals(1, pages[1].single().block)
    }

    @Test
    fun `pageOf finds the page holding a position`() {
        val pages = Paginate.pages(para(0, lines = 4) + para(1, from = 40, lines = 4), 25f)
        // Block 0, offset 25 -> second slice of block 0's lines, on the second page.
        val page = Paginate.pageOf(pages, 0, 25)
        assertTrue(page in pages.indices)
        assertTrue(pages[page].any { it.block == 0 && 25 >= it.start && 25 < it.end })
    }

    @Test
    fun `pageOf falls back to the nearest slice when the exact offset is gone`() {
        val pages = Paginate.pages(para(0, lines = 4), 25f)
        // An offset past the end of the text (book re-imported shorter) still lands somewhere.
        val page = Paginate.pageOf(pages, 0, 9999)
        assertEquals(pages.size - 1, page)
        // A block that no longer exists keeps us at the first page rather than crashing.
        assertEquals(0, Paginate.pageOf(pages, 7, 0))
    }

    @Test
    fun `the layout signature changes when the layout does`() {
        val base = LayoutSignature(1080, 1920, 44f, 66f, 20f, 40f, 10f, 56f)
        assertEquals(base.id(), base.copy().id())
        assertTrue(base.id() != base.copy(bodyPx = 48f).id())
        assertTrue(base.id() != base.copy(widthPx = 720).id())
        assertTrue(base.id() != base.copy(heightPx = 1080).id())
        assertTrue(base.id() != base.copy(paraGapPx = 28f).id())
    }

    @Test
    fun `a chapter break closes the page early`() {
        val lines = listOf(
            line(0, 0, 10, 10f, 5f),
            line(0, 10, 20, 10f),
            // The third line opens a chapter, and there is room for all three.
            line(0, 20, 30, 10f, breakBefore = true),
        )
        val pages = Paginate.pages(lines, 1000f)
        assertEquals(2, pages.size)
        assertEquals(2, pages[0].single().lines)
        assertEquals(1, pages[1].single().lines)
        // A chapter starting a page takes no leading gap, exactly like any other break.
        assertEquals(0f, pages[1].first().gapAbove, 0.001f)
        // And the text is not lost or repeated at the seam.
        assertEquals(20, pages[0].single().end)
        assertEquals(20, pages[1].single().start)
    }

    @Test
    fun `a chapter break on an empty page does not leave it blank`() {
        val lines = listOf(
            line(0, 0, 10, 10f, breakBefore = true),
            line(0, 10, 20, 10f),
        )
        val pages = Paginate.pages(lines, 1000f)
        assertEquals(1, pages.size)
        assertEquals(2, pages.single().single().lines)
    }

    @Test
    fun `the cache id carries the pagination rule version`() {
        val sig = LayoutSignature(1080, 1920, 44f, 66f, 20f, 40f, 10f, 56f)
        // Bump LayoutSignature.VERSION when the rules change, or a stale page map keeps serving the
        // old breaks and the change looks like it did not work.
        assertTrue(sig.id().startsWith("${LayoutSignature.VERSION}-"))
    }

    @Test
    fun `a tap turns the page in the outer thirds and does nothing in the middle`() {
        assertEquals(false, zoneTurn(10f, 300f))
        assertEquals(true, zoneTurn(290f, 300f))
        assertNull(zoneTurn(150f, 300f))
        // The boundary belongs to the middle: exactly a third is not yet a turn.
        assertNull(zoneTurn(100f, 300f))
        assertEquals(true, zoneTurn(200.1f, 300f))
        // A page with no width cannot be turned by a tap at all, rather than every tap going back.
        assertNull(zoneTurn(50f, 0f))
    }

    @Test
    fun `a word found in a slice maps back to the whole block`() {
        val block = "Er ging gewöhnlich nach Hause."
        // A slice as the paginator cuts them: the block from a line break onwards.
        val slice = PageSlice(block = 3, start = 8, end = block.length, lines = 1, gapAbove = 0f)
        val sliceText = block.substring(slice.start, slice.end)
        val local = Words.at(sliceText, 0)!!
        assertEquals("gewöhnlich", local.text)
        val mapped = blockWord(local, slice)
        // The same characters, addressed in the BLOCK: everything downstream - the sentence around
        // the word, the cache key, the reading anchor - is computed from these offsets.
        assertEquals("gewöhnlich", block.substring(mapped.start, mapped.end))
        assertEquals(8, mapped.start)
        // The press wash maps the other way, and must land on the same characters.
        assertEquals(0, mapped.start - slice.start)
        assertEquals(local.end, mapped.end - slice.start)
    }
}
