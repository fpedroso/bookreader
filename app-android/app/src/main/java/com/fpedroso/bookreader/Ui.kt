package com.fpedroso.bookreader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<BookMeta>,
    onImport: () -> Unit,
    onOpen: (BookMeta) -> Unit,
    onDelete: (BookMeta) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Book Reader") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TextButton(onClick = onImport, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("+  Import EPUB (and .pinyin.json)")
            }
            Text(
                "Select the EPUB. If you also select its .pinyin.json sidecar in the " +
                    "same picker, tap-to-read pinyin is enabled.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider()
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books yet", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    items(books, key = { it.id }) { b ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(b) }
                                .padding(16.dp),
                        ) {
                            Text(b.title, fontWeight = FontWeight.Medium)
                            Text(
                                buildString {
                                    append(if (b.sidecarFile != null) "pinyin sidecar ✓" else "no pinyin sidecar")
                                    append("  ·  ")
                                    append(b.lang)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            onClick = { onDelete(b) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Remove") }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Reserved reading row for tokens with no pinyin, so every item is the same height. */
private const val BLANK = "\u00A0"

// Paragraph rhythm. Space *between* blocks rather than a first-line indent: the publisher
// CSS used `text-indent: 1.5em` with no paragraph margin, which is the dense print
// convention and reads cramped on a phone.
private val PARA_GAP = 20.dp
private val HEAD_GAP_TOP = 28.dp

// ------------------------------------------------------------------- reader

sealed interface RowItem {
    data class ChapterHead(val title: String) : RowItem
    data class Para(
        val text: String,
        val tokens: List<Token>?,
        val heading: Boolean,
        /**
         * Set only for space-separated languages (German), where the paragraph renders as
         * continuous prose and taps are resolved by text offset. Null for Chinese, which
         * uses the per-word box flow so readings can sit above the characters.
         */
        val tokenRanges: List<IntRange>? = null,
        /**
         * Block hash for [Annotations.key] of [text], set for Chinese blocks that have no
         * PC sidecar. The reader looks readings up in the generated map by this key, so
         * rows are built once and re-render in place as annotation arrives.
         */
        val hash: String? = null,
    ) : RowItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    title: String,
    rows: List<RowItem>,
    showPinyin: Boolean,
    /** False for books with no readings (German), where the switch would do nothing. */
    pinyinToggleVisible: Boolean,
    initialIndex: Int,
    /**
     * In-app generated readings by block hash. Empty when the book has a PC sidecar or no
     * handler for its language. Passed as state so a row picks up its readings as soon as
     * generation finishes, without rebuilding the row list.
     */
    generated: Map<String, List<Token>> = emptyMap(),
    /** Block hash of the paragraph whose translation modal is open, outlined behind it. */
    selectedHash: String? = null,
    onTogglePinyin: (Boolean) -> Unit,
    onParagraphTap: (RowItem.Para) -> Unit,
    onProgress: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    // Report reading position as the user scrolls (caller decides when to persist).
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex }.collect { onProgress(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // No "拼音" text label beside the switch: with a Chinese title the
                // label pushed the title into an ellipsis on a 1080px screen.
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    if (pinyinToggleVisible) {
                        Switch(
                            checked = showPinyin,
                            onCheckedChange = onTogglePinyin,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            state = state,
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
        ) {
            items(rows) { row ->
                when (row) {
                    is RowItem.ChapterHead -> Text(
                        row.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    is RowItem.Para -> {
                        // Generous space between paragraphs, deliberately. The publisher CSS
                        // used `text-indent: 1.5em` with no paragraph margin - the dense
                        // print convention - which reads cramped on a phone. Space between
                        // blocks is what gives the page room to breathe.
                        Spacer(Modifier.height(if (row.heading) HEAD_GAP_TOP else PARA_GAP))
                        // A PC sidecar wins; otherwise in-app generation fills this in
                        // lazily, so the row re-renders with readings once they exist.
                        val tokens = row.tokens ?: row.hash?.let { generated[it] }
                        // The whole paragraph is the tap target now: the word dialog is gone
                        // and a paragraph is the unit we translate. Outlined while its
                        // translation modal is open, so you can see which one it refers to.
                        val selected = row.hash != null && row.hash == selectedHash
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .then(
                                    if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                                    else Modifier
                                )
                                .clickable(enabled = row.hash != null) { onParagraphTap(row) }
                                .padding(if (selected) 10.dp else 0.dp),
                        ) {
                            when {
                                tokens == null ->
                                    // Not annotated yet, or a language with no handler:
                                    // readable straight away, becomes tappable when ready.
                                    Text(row.text, fontSize = 19.sp, lineHeight = 30.sp)
                                row.tokenRanges != null ->
                                    ProseText(row.text, tokens, row.tokenRanges)
                                else ->
                                    WordFlow(tokens, showPinyin)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/**
 * A paragraph of a space-separated language, rendered as continuous prose.
 *
 * The text is drawn exactly as authored - real spaces, punctuation in the right place, and
 * `<br>` line breaks as real newlines. The alternative (one box per word, as Chinese uses)
 * has no way to keep inter-word whitespace, which produced "Title:Der" and "3—6Jahren".
 *
 * Word-level hit testing used to live here, resolving a tap through
 * `getOffsetForPosition` against per-token ranges. It is gone with the word dialog, so
 * `tokens`/`ranges` are currently unused and kept only so restoring it stays a small
 * change; paragraph taps are handled by the row's own clickable.
 */
@Composable
fun ProseText(
    text: String,
    @Suppress("UNUSED_PARAMETER") tokens: List<Token>,
    @Suppress("UNUSED_PARAMETER") ranges: List<IntRange>,
    fontSize: TextUnit = 19.sp,
) {
    Text(
        text = text,
        fontSize = fontSize,
        lineHeight = fontSize * 1.5f,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One paragraph as a flow of per-word boxes: pinyin above, the word itself
 * tappable below.
 *
 * Deliberately NOT using a ruby-text library: Furiganable documents
 * `onTextLayout` as unsupported (it swaps in an AndroidTextView in some paths),
 * so word hit-testing by text offset is unavailable. Rendering each word as its
 * own box gives exact tap targets with no coordinate maths, and makes pinyin
 * alignment correct by construction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordFlow(tokens: List<Token>, showPinyin: Boolean) {
    // The reading row is reserved for EVERY token only when some token actually has a
    // reading. FlowRow aligns items to the TOP, so mixed-height items misalign: with a
    // reading row on the Han words alone, bare punctuation (one row) floated above the
    // characters instead of sitting on their baseline. German has no readings at all,
    // so the row is skipped entirely and every token stays one row tall.
    val readingRow = showPinyin && tokens.any { it.hasPinyin }
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        for (t in tokens) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = if (t.isWord) 3.dp else 0.dp, bottom = 3.dp),
            ) {
                if (readingRow) {
                    Text(
                        if (t.hasPinyin) t.pinyin else BLANK,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(
                    t.word,
                    fontSize = if (t.isHan) 21.sp else 19.sp,
                )
            }
        }
    }
}

// ------------------------------------------------------- paragraph translation

/**
 * The paragraph translation modal.
 *
 * Deliberately the same shape as the word dialog it replaced, so tapping a paragraph feels
 * like the word lookup did: the paragraph stays visible behind the scrim (and is outlined)
 * and the translation appears underneath it. On-device, so this is a "what does this say"
 * answer, not a grammar explanation.
 */
@Composable
fun TranslationDialog(
    source: String,
    translation: String?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Translation", fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(source, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(14.dp))
                when {
                    // The first call also downloads the ~30MB language model, so this can
                    // sit here for a while on a cold device.
                    busy -> Text("Translating…", style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(
                        "Translation unavailable — $error",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    translation != null -> Text(
                        translation,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}

// --------------------------------------------------------------- word popup

@Composable
fun WordDialog(
    headword: String,
    results: List<Lookup>,
    note: String?,
    onDismiss: () -> Unit,
) {
    val first = results.firstOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text(headword, fontSize = 30.sp)
                // Chinese: the reading of the tapped word.
                first?.pinyin?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                // German: the tapped token was inflected. Showing the lemma is the
                // whole point, otherwise "ging" looks like it means nothing.
                first?.takeIf { it.wasInflected }?.lemma?.let { lemma ->
                    val pos = first.pos?.takeIf { it.isNotBlank() }
                    Text(
                        "→ $lemma" + (if (pos != null) "  ·  $pos" else ""),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (results.isEmpty()) {
                    Text(
                        "No dictionary entry for “$headword”.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                results.forEachIndexed { i, r ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Card {
                        Column(Modifier.padding(10.dp).fillMaxWidth()) {
                            // Label each block when the result is ambiguous.
                            if (results.size > 1) {
                                Text(
                                    listOfNotNull(
                                        r.lemma ?: r.headword,
                                        r.pos?.takeIf { it.isNotBlank() },
                                    ).joinToString("  ·  "),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                r.glosses.joinToString("\n").ifBlank { "—" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                note?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
