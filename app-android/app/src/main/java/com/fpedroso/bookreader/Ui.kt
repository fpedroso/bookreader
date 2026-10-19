package com.fpedroso.bookreader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
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
        // The version rides on the title. It only exists to identify the installed build, so it
        // needs no line of its own, and there is nothing to explain below the import button -
        // "Import EPUB" says what it does.
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Book Reader v${BuildConfig.VERSION_CODE}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TextButton(onClick = onImport, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("+  Import EPUB")
            }
            HorizontalDivider()
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books yet", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn {
                    items(books, key = { it.id }) { b ->
                        // One line per book: title, language, remove. The title takes the slack
                        // and ellipsises, so a long title cannot push the ✕ off the row.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(b) }
                                .padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                b.title,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            flagFor(b.lang)?.let {
                                Text(it, modifier = Modifier.padding(horizontal = 10.dp))
                            }
                            TextButton(onClick = { onDelete(b) }) { Text("✕") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * "de" becomes the German flag.
 *
 * Derived from the code rather than read from a table: each ASCII letter maps to its regional
 * indicator symbol, so any two-letter code works - including one for a language this app has no
 * other handling for. Null when the language is unknown, so the row shows no symbol at all
 * rather than a plausible wrong one.
 */
private fun flagFor(lang: String): String? {
    val code = lang.trim().take(2).lowercase()
    if (code.length != 2 || code.any { it !in 'a'..'z' }) return null
    return code.map { String(Character.toChars(it - 'a' + 0x1F1E6)) }.joinToString("")
}

// Paragraph rhythm. Space *between* blocks rather than a first-line indent: the publisher
// CSS used `text-indent: 1.5em` with no paragraph margin, which is the dense print
// convention and reads cramped on a phone.
//
// A heading gets a wider gap than a paragraph, plus a little space below, so the title is
// set off on both sides. Size and weight alone separate it from the prose; nothing here is
// boxed or tinted, because the reading view is deliberately frame-free.
private val PARA_GAP = 20.dp
private val HEAD_GAP_TOP = 40.dp
private val HEAD_GAP_BOTTOM = 10.dp

// ------------------------------------------------------------------- reader

sealed interface RowItem {
    data class ChapterHead(val title: String) : RowItem
    data class Para(
        val text: String,
        val heading: Boolean,
        /**
         * Block hash for [Annotations.key] of [text]. It is the translation cache key and the
         * tap target. Null never happens for parsed prose, but a row without it is simply
         * not tappable.
         */
        val hash: String? = null,
        /**
         * Character ranges of the sentences in [text], for prose languages: each is the unit
         * of translation and the rectangle the tap hit-test resolves against. The paragraph
         * itself is rendered as ONE continuous [text] so the prose flows naturally; the
         * ranges only exist to turn a tap position into a sentence.
         *
         * Null for headings and verse, which are translated as a whole paragraph instead.
         */
        val sentences: List<IntRange>? = null,
    ) : RowItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    title: String,
    rows: List<RowItem>,
    initialIndex: Int,
    /**
     * Cache key of the unit whose translation modal is open, outlined behind it. A paragraph
     * key is its block hash; a sentence key is `"<hash>#<index>"`.
     */
    selectedKey: String? = null,
    onParagraphTap: (RowItem.Para) -> Unit,
    onSentenceTap: (RowItem.Para, Int) -> Unit,
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
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
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
                        // A chapter title is the only structure the reader gets besides the
                        // prose rhythm, so it announces itself with size and weight. See the
                        // heading branch below - the two must match.
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = HEAD_GAP_TOP, bottom = HEAD_GAP_BOTTOM),
                    )
                    is RowItem.Para -> {
                        // Generous space between paragraphs, deliberately. The publisher CSS
                        // used `text-indent: 1.5em` with no paragraph margin - the dense
                        // print convention - which reads cramped on a phone. Space between
                        // blocks is what gives the page room to breathe.
                        Spacer(Modifier.height(if (row.heading) HEAD_GAP_TOP else PARA_GAP))
                        val shape = RoundedCornerShape(10.dp)
                        val ranges = row.sentences
                        if (ranges != null) {
                            // One continuous paragraph, exactly the prose flow a non-sentence
                            // row gets: sentence boundaries are NOT marked in any way. The
                            // split only survives as character ranges, so the whole unit is a
                            // single Text and the tap position is hit-tested against the
                            // layout to find which sentence the finger landed on.
                            var layout by remember(row.text) {
                                mutableStateOf<TextLayoutResult?>(null)
                            }
                            Text(
                                row.text,
                                fontSize = 19.sp,
                                lineHeight = 30.sp,
                                onTextLayout = { layout = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(row.hash, ranges) {
                                        if (row.hash == null) return@pointerInput
                                        detectTapGestures { pos ->
                                            val l = layout ?: return@detectTapGestures
                                            onSentenceTap(
                                                row,
                                                Sentences.indexAt(
                                                    ranges,
                                                    l.getOffsetForPosition(pos),
                                                ),
                                            )
                                        }
                                    },
                            )
                        } else {
                            // The whole paragraph is the tap target (headings, verse):
                            // outlined while its translation modal is open, so you can see
                            // which one it is.
                            val selected = row.hash != null && row.hash == selectedKey
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
                                if (row.heading) {
                                    // A title has no box to mark it any more, so it is set
                                    // apart by size and weight only - larger and heavier than
                                    // the 19sp body, plus space below so the first paragraph
                                    // does not crowd it. Left-aligned, onBackground colour:
                                    // no tint, no border.
                                    Text(
                                        row.text,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(bottom = HEAD_GAP_BOTTOM),
                                    )
                                } else {
                                    Text(row.text, fontSize = 19.sp, lineHeight = 30.sp)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// ------------------------------------------------------- sentence translation

/**
 * The translation modal.
 *
 * Deliberately the same shape as the word dialog it replaced, so tapping a sentence feels
 * like the word lookup did: the sentence stays visible behind the scrim (and is outlined)
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
