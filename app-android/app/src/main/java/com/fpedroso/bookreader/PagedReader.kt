package com.fpedroso.bookreader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Alpha of the wash behind a block whose translation modal is open. Faint: it is a pointer, not a highlight. */
private const val SELECTED_ALPHA = 0.10f

/**
 * The paged reading view.
 *
 * Deliberately a SEPARATE screen from [ReaderScreen] rather than a mode inside it. Pagination
 * decides what text is on screen; word taps, frequency shading and translation only ever react to
 * words within whatever is on screen. Keeping them in separate files is what makes that true in
 * practice rather than only in intent: either one can be deleted without touching the other.
 *
 * What pagination changes about the gestures, at Felipe's request: a TAP now turns the page (left
 * third back, right third forward, the middle rests), and a word is asked about with a LONG PRESS -
 * which washes the word under the finger so you can see what you are about to ask about. The page
 * counter at the foot is the book's own page count, which is why the whole book is measured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagedReader(
    title: String,
    bookId: String,
    rows: List<RowItem>,
    /** Where the reader left off: the block, and the offset inside it. */
    anchorBlock: Int,
    anchorOffset: Int,
    selectedKey: String? = null,
    frequency: WordFrequency? = null,
    onWordTap: (RowItem, Words.Word) -> Unit,
    onBlockTap: (RowItem) -> Unit,
    /** The position at the top of the page now shown, for the caller to remember. */
    onPosition: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { pad ->
        // The page is whatever is left: the text column is the width minus the inset on both sides,
        // and the counter's bar comes off the height. Both go into the signature, so a rotation or a
        // font change measures again instead of reusing pages that no longer fit.
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val metrics = rememberReaderMetrics()
            val textWidth = (constraints.maxWidth - 2 * metrics.insetPx).toInt().coerceAtLeast(1)
            val pageHeight = (constraints.maxHeight - metrics.counterPx).coerceAtLeast(1f)
            val signature = metrics.signature(textWidth, pageHeight.toInt()).id()
            val context = LocalContext.current
            val measurer = rememberTextMeasurer()

            var pages by remember(bookId, signature) { mutableStateOf<List<Page>?>(null) }

            LaunchedEffect(bookId, signature, rows) {
                if (pages != null) return@LaunchedEffect
                // A book re-opened at the same size and type is instant: the page map is on disk.
                val cached = withContext(Dispatchers.IO) {
                    Pages.load(context, bookId, signature, rows.size)
                }
                if (cached != null) {
                    pages = cached
                    return@LaunchedEffect
                }
                val measured = Pages.measure(measurer, rows, metrics, textWidth, pageHeight)
                withContext(Dispatchers.IO) {
                    Pages.save(context, bookId, signature, rows.size, measured)
                }
                pages = measured
            }

            val ready = pages
            if (ready == null || ready.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Paginating…") }
            } else {
                PageTurner(
                    rows = rows,
                    pages = ready,
                    metrics = metrics,
                    initialPage = Paginate.pageOf(ready, anchorBlock, anchorOffset),
                    pageWidthPx = constraints.maxWidth.toFloat(),
                    selectedKey = selectedKey,
                    frequency = frequency,
                    onWordTap = onWordTap,
                    onBlockTap = onBlockTap,
                    onPosition = onPosition,
                )
            }
        }
    }
}

/**
 * One page at a time, with the counter beneath it.
 *
 * The tap zones are thirds of the PAGE, not of a block: a tap on a word, on a gap or on empty space
 * under the last line all turn the page, because that is what a tap means now. Taps are only ever
 * not a page turn when they land on a word AND the press becomes a long press.
 */
@Composable
private fun PageTurner(
    rows: List<RowItem>,
    pages: List<Page>,
    metrics: ReaderMetrics,
    initialPage: Int,
    pageWidthPx: Float,
    /**
     * The block whose translation modal is open, when that block is a unit in its own right (a
     * heading or a stanza). Marked on the page with a wash rather than the outline the scrolling
     * reader draws: a border would change the slice's layout, and the page was measured without one.
     */
    selectedKey: String?,
    frequency: WordFrequency?,
    onWordTap: (RowItem, Words.Word) -> Unit,
    onBlockTap: (RowItem) -> Unit,
    onPosition: (Int, Int) -> Unit,
) {
    var page by remember(pages) { mutableStateOf(initialPage.coerceIn(0, pages.size - 1)) }
    /** The word under the finger while it is down, in BLOCK coordinates. */
    var pressed by remember { mutableStateOf<Pair<Int, Words.Word>?>(null) }

    // The position is the FIRST slice of the page: the top of what is being read. Reported from an
    // effect rather than during composition, and the caller decides when to write it.
    LaunchedEffect(page, pages) {
        pages.getOrNull(page)?.firstOrNull()?.let { onPosition(it.block, it.start) }
    }

    fun zone(x: Float): Boolean? = zoneTurn(x, pageWidthPx)

    fun turn(next: Boolean) {
        val target = if (next) page + 1 else page - 1
        if (target in pages.indices) {
            pressed = null
            page = target
        }
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Taps that miss every word - the gaps between blocks, the space under the last
                // line - still turn the page. The Texts inside consume their own taps, so this only
                // ever sees what they left.
                .pointerInput(page, pages) {
                    detectTapGestures { pos -> zone(pos.x)?.let { turn(it) } }
                },
        ) {
            val density = LocalDensity.current
            Column(Modifier.fillMaxWidth()) {
                for (slice in pages[page]) {
                    val row = rows.getOrNull(slice.block) ?: continue
                    if (slice.gapAbove > 0f) {
                        Spacer(Modifier.height(with(density) { slice.gapAbove.toDp() }))
                    }
                    PageSliceText(
                        row = row,
                        slice = slice,
                        // The slice's own text: a page draws a block in pieces, and the pieces are
                        // cut at line breaks so no word is ever split across two of them.
                        text = row.text.substring(slice.start, slice.end),
                        maxLines = slice.lines,
                        metrics = metrics,
                        pageWidthPx = pageWidthPx,
                        // A heading is not shaded, and neither is a block whose words were never
                        // counted - see the same rule in ReaderScreen. An English block in a German
                        // book has no bands of its own, so shading it would mark every word "rare".
                        frequency = if (row.heading || !row.translatable) null else frequency,
                        selected = row.blockUnit && row.hash != null && row.hash == selectedKey,
                        pressed = pressed?.takeIf { it.first == slice.block }?.second,
                        onPressed = { word -> pressed = word?.let { slice.block to it } },
                        onWord = { word -> onWordTap(row, word) },
                        onBlock = if (row.blockUnit) ({ onBlockTap(row) }) else null,
                        onTurn = ::turn,
                    )
                }
            }
        }
        PageCounter(page + 1, pages.size)
    }
}

/** "3 of 39" - the page within the BOOK, which is why the whole book is measured up front. */
@Composable
private fun PageCounter(page: Int, total: Int) {
    Box(
        Modifier.fillMaxWidth().height(ReaderType.COUNTER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$page of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One slice of one block, drawn as its own `Text`.
 *
 * Everything the word machinery needs is unchanged from the scrolling reader - the tap resolves
 * through the rendered layout, and a miss resolves to the nearest word - except that the tap turns
 * the page and the whole-word action moved to a long press.
 */
@Composable
private fun PageSliceText(
    row: RowItem,
    slice: PageSlice,
    text: String,
    maxLines: Int,
    metrics: ReaderMetrics,
    pageWidthPx: Float,
    frequency: WordFrequency?,
    /** A block unit whose modal is open: washed so it is clear which block the translation is of. */
    selected: Boolean,
    pressed: Words.Word?,
    onPressed: (Words.Word?) -> Unit,
    onWord: (Words.Word) -> Unit,
    onBlock: (() -> Unit)?,
    onTurn: (Boolean) -> Unit,
) {
    // Read at gesture time, never during composition, so writing it does not recompose the slice.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val dark = isDarkTheme()
    val wash = MaterialTheme.colorScheme.primary.copy(alpha = PRESSED_ALPHA)
    val mark = MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_ALPHA)

    val annotated = remember(text, frequency, dark, pressed, wash, mark, selected) {
        val tints = Band.entries.associateWith { tintFor(it, dark) }
        buildAnnotatedString {
            if (frequency == null) {
                append(text)
            } else {
                // The same span builder the scrolling reader uses, on the slice's own text: shading
                // is a property of the words, not of how the page happens to be cut.
                var at = 0
                for (span in shadedSpans(text, frequency::bandOf)) {
                    append(text.substring(at, span.start))
                    val colour = tints[span.band]
                    val word = text.substring(span.start, span.end)
                    if (colour == null) append(word) else withStyle(SpanStyle(color = colour)) { append(word) }
                    at = span.end
                }
                append(text.substring(at))
            }
            // The block a block-unit translation is being shown for. A background, like the press
            // wash, so it cannot change the layout the page was measured with.
            if (selected && text.isNotEmpty()) {
                addStyle(SpanStyle(background = mark), 0, text.length)
            }
            // The word under the finger, as a BACKGROUND: it layers under the frequency tint rather
            // than replacing it, so both still read at once.
            pressed?.let { word ->
                val from = (word.start - slice.start).coerceIn(0, text.length)
                val to = (word.end - slice.start).coerceIn(from, text.length)
                if (to > from) addStyle(SpanStyle(background = wash), from, to)
            }
        }
    }

    /** The word at a page coordinate, in BLOCK coordinates - the offsets the rest of the app uses. */
    fun wordAt(pos: androidx.compose.ui.geometry.Offset): Words.Word? {
        val l = layout.value ?: return null
        val offset = l.getOffsetForPosition(pos)
        val local = Words.at(text, offset) ?: Words.atNearest(text, offset) ?: return null
        return blockWord(local, slice)
    }

    val gestures = if (row.translatable) {
        Modifier.pointerInput(text, slice, maxLines) {
            detectTapGestures(
                // Down: wash the word being touched, so it is visible what a long press is about to
                // open. Up (however it ends): take the wash away.
                onPress = { pos ->
                    onPressed(wordAt(pos))
                    tryAwaitRelease()
                    onPressed(null)
                },
                onLongPress = { pos ->
                    val word = wordAt(pos)
                    // A heading or a stanza has no sentences to fall back on, so a long press
                    // anywhere in it asks for the whole block.
                    if (word != null) onWord(word) else onBlock?.invoke()
                },
                // A tap is a page turn, never a translation.
                onTap = { pos -> zoneTurn(pos.x, pageWidthPx)?.let(onTurn) },
            )
        }
    } else {
        Modifier
    }

    Text(
        text = annotated,
        // The SAME style object the block was measured with - see [ReaderMetrics]. Two matching sets
        // of numbers would drift; one shared object cannot.
        style = metrics.style(row.heading),
        // Clipped to exactly the lines the page map allotted, so a measurement that came out short
        // cannot push text onto the next page.
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout.value = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ReaderType.TEXT_INSET)
            .then(gestures),
    )
}
