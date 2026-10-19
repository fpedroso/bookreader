package com.fpedroso.bookreader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private val PARA_GAP = 20.dp
private val HEAD_GAP_TOP = 28.dp

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
         * Sentence units for prose languages, each rendered as its own softly bordered block
         * and each the unit of translation - so the boundary is visible before the tap.
         * Null for headings and verse, which are translated (and outlined) as a whole
         * paragraph instead.
         */
        val sentences: List<String>? = null,
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
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    is RowItem.Para -> {
                        // Generous space between paragraphs, deliberately. The publisher CSS
                        // used `text-indent: 1.5em` with no paragraph margin - the dense
                        // print convention - which reads cramped on a phone. Space between
                        // blocks is what gives the page room to breathe.
                        Spacer(Modifier.height(if (row.heading) HEAD_GAP_TOP else PARA_GAP))
                        val shape = RoundedCornerShape(10.dp)
                        val sents = row.sentences
                        if (sents != null) {
                            // One soft-bordered block per sentence. The border IS the unit:
                            // what will be translated is visible before the tap, and each
                            // sentence owns its line flow so a wrapped line stays inside its
                            // own box. "Soft" = outlineVariant, so a page of these reads as
                            // texture rather than as twenty nested frames.
                            Column(Modifier.fillMaxWidth()) {
                                sents.forEachIndexed { i, s ->
                                    val key = row.hash?.let { "$it#$i" }
                                    val sel = key != null && key == selectedKey
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clip(shape)
                                            .border(
                                                width = if (sel) 1.5.dp else 1.dp,
                                                color = if (sel) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                shape = shape,
                                            )
                                            .clickable(enabled = row.hash != null) {
                                                onSentenceTap(row, i)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                    ) {
                                        Text(s, fontSize = 19.sp, lineHeight = 28.sp)
                                    }
                                }
                            }
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
                                Text(row.text, fontSize = 19.sp, lineHeight = 30.sp)
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
