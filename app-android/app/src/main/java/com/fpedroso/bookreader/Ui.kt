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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    books: List<BookMeta>,
    onImport: () -> Unit,
    onOpen: (BookMeta) -> Unit,
    onDelete: (BookMeta) -> Unit,
    onSettings: () -> Unit,
) {
    // The book whose ✕ was tapped, held until the confirmation is answered. Deleting is
    // destructive and irreversible - the whole per-book directory goes, cached translations
    // included - so the ✕ opens this first and nothing is removed until "Remove".
    var pendingRemoval by remember { mutableStateOf<BookMeta?>(null) }

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
                // Spelled out rather than a gear glyph: the emulator has no emoji font and a
                // symbol that renders as a box is worse than a word.
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
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
                            TextButton(onClick = { pendingRemoval = b }) { Text("✕") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Confirm before deleting. Store.remove drops the book's whole directory - the imported
    // copy and its stored data, translations.json included - so what it costs the reader is
    // the translations already looked up, which have to be produced again sentence by
    // sentence. Cancel and an outside tap both dismiss; only "Remove" deletes anything.
    pendingRemoval?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        onDelete(book)
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
            title = { Text("Remove ${book.title}?") },
            text = {
                Text(
                    "The imported copy is deleted from app storage with this book's stored " +
                        "data, including the translations you have already looked up. This " +
                        "cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
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

// Space between sentences inside one paragraph. Sized for a reader who wants to take one sentence
// at a time: after living with 4dp he asked for more, because the gap is what makes him stop and
// finish the sentence he is on before moving to the next. Still smaller than PARA_GAP (20dp) so a
// paragraph boundary stays visibly bigger than a sentence one. One constant, easy to tune.
// The sentence gap carries more weight than it used to: with the tint gone, separating one
// sentence from the next is ALL that it does, so it is load-bearing rather than decorative.
private val SENTENCE_GAP = 12.dp

// The page's text inset, on every block - heading, verse and each sentence alike - so the whole
// book keeps ONE left edge. It used to be the padding INSIDE the sentence band; the band is gone at
// the reader's request but the margin stayed, because without it the prose would slide under the
// headings' left edge and the column would read as ragged.
private val TEXT_INSET = 6.dp

// The inline sentence action is an "EN" chip rather than a Material glyph, for two reasons:
// material-icons-extended is a ~5 MB dependency this app has no other use for, and the core icon
// set carries no translate symbol. Spelling out the target says what the tap will produce rather
// than merely that something language-shaped will happen.
private const val SENTENCE_ACTION = "sentence-action"

// TextUnit, not Dp: `Placeholder` sizes itself in sp so the chip scales with the reader's font
// size, which is what keeps it legible once the text is enlarged.
private val SENTENCE_ACTION_SLOT = 34.sp
private val SENTENCE_ACTION_HEIGHT = 20.sp

// ------------------------------------------------------------------- reader

/**
 * One row of the reading view. There is a single row type: prose, verse, or a heading - all
 * rendered as text, and tappable wherever the row offers translation.
 */
data class RowItem(
    val text: String,
    val heading: Boolean,
    /**
     * Block hash for [Annotations.key] of [text]. It is the translation cache key and the
     * outline target. Null never happens for parsed prose.
     */
    val hash: String? = null,
    /**
     * The sentence texts of [text], for prose languages. Each is rendered on its own line and
     * carries the sentence action at its end.
     *
     * Null for headings and verse, which are translated as a whole paragraph: a heading is not
     * translated a sentence at a time, and splitting a stanza would turn its line breaks into
     * prose again.
     */
    val sentences: List<String>? = null,
    /**
     * The language THIS block is written in, resolved by the parser from the document's
     * own lang/xml:lang attributes with the book's dc:language as the fallback. Null only
     * when the book declared nothing either.
     *
     * Per block because one book can hold two languages: a German novel's front matter is
     * routinely English, and translating it as German silently produces nothing useful.
     */
    val lang: String? = null,
    /**
     * Whether this row offers translation at all - see [translationOffered]. False renders the row
     * as plain, inert text with no word tap and no sentence action. That is the ordinary case for
     * a book in any language but German, and for an English block inside a German one.
     */
    val translatable: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    title: String,
    rows: List<RowItem>,
    initialIndex: Int,
    /**
     * Cache key of the unit whose translation modal is open, outlined behind it. A paragraph
     * key is the hash of its text; a sentence has no outline, because the EN chip at its end
     * already says which unit is open.
     */
    selectedKey: String? = null,
    onParagraphTap: (RowItem) -> Unit,
    onSentenceTap: (RowItem, Int) -> Unit,
    /**
     * A word was tapped. The [Int] is the sentence index inside the row, or -1 when the row has no
     * sentence units (a heading or a stanza), where the word's own context is the block itself.
     */
    onWordTap: (RowItem, Int, Words.Word) -> Unit,
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
                // Generous space between paragraphs, deliberately. The publisher CSS
                // used `text-indent: 1.5em` with no paragraph margin - the dense
                // print convention - which reads cramped on a phone. Space between
                // blocks is what gives the page room to breathe.
                Spacer(Modifier.height(if (row.heading) HEAD_GAP_TOP else PARA_GAP))
                val shape = RoundedCornerShape(10.dp)
                val sentences = row.sentences
                if (sentences != null) {
                    // One line per sentence, a 12dp gap between them, and nothing drawn behind the
                    // text: the band is gone at the reader's request.
                    //
                    // The tint used to be what marked a unit as translatable, so with it gone the
                    // LAYOUT does the marking - one sentence per composable, separated by the gap -
                    // and the EN chip at the end of each line is what says "this is translatable,
                    // tap here". The gap is therefore load-bearing, not decoration.
                    //
                    // Division of labour, and the reason the chip is not optional: the text
                    // belongs to the WORD tap, the chip owns the sentence. With the text
                    // word-tappable there is no longer any other way to ask for the whole
                    // sentence, and a ragged right edge and taller paragraphs are the accepted
                    // price of that, exactly as they were for the band.
                    Column(Modifier.fillMaxWidth()) {
                        sentences.forEachIndexed { i, sentence ->
                            WordTapText(
                                text = sentence,
                                translatable = row.translatable,
                                onWord = { onWordTap(row, i, it) },
                                onUnit = { onSentenceTap(row, i) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = SENTENCE_GAP)
                                    .padding(horizontal = TEXT_INSET),
                            )
                        }
                    }
                } else {
                    // No sentence units, so the chip at the end of the block IS the whole-block
                    // action (headings and verse), and the block is outlined while its translation
                    // is open so you can see which one it is.
                    val selected = row.hash != null && row.hash == selectedKey
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                                else Modifier
                            )
                            .padding(if (selected) 10.dp else 0.dp),
                    ) {
                        if (row.heading) {
                            // A title has no box to mark it any more, so it is set
                            // apart by size and weight only - larger and heavier than
                            // the 19sp body, plus space below so the first paragraph
                            // does not crowd it. Left-aligned, no tint, no border. The
                            // horizontal inset matches every other block's so the page keeps
                            // one left edge.
                            WordTapText(
                                text = row.text,
                                translatable = row.translatable,
                                onWord = { onWordTap(row, -1, it) },
                                onUnit = { onParagraphTap(row) },
                                fontSize = MaterialTheme.typography.titleLarge.fontSize,
                                lineHeight = MaterialTheme.typography.titleLarge.lineHeight,
                                weight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    start = TEXT_INSET,
                                    end = TEXT_INSET,
                                    bottom = HEAD_GAP_BOTTOM,
                                ),
                            )
                        } else {
                            WordTapText(
                                text = row.text,
                                translatable = row.translatable,
                                onWord = { onWordTap(row, -1, it) },
                                onUnit = { onParagraphTap(row) },
                                modifier = Modifier.padding(horizontal = TEXT_INSET),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

// -------------------------------------------------------------- word tapping

/**
 * A sentence - or a whole block, for a heading or a stanza - tappable word by word, with the unit
 * action sitting INLINE at the very end of the text.
 *
 * Why the offset machinery is back. A sentence used to BE its own tap target, so the unit was exact
 * by construction and `getOffsetForPosition` was deleted on purpose. A word cannot be: one
 * composable per word throws away the real inter-word spacing, which is how `Title:Der` and
 * `3—6Jahren` once shipped. So prose stays one flowing `Text` and the tap is resolved through the
 * layout instead. That is an approximation only inside whitespace, and a tap that resolves to
 * whitespace asks for the unit rather than doing nothing - so it is never a dead tap.
 *
 * The chip is inline content, not a sibling composable, and that is what puts it at the end of the
 * LAST LINE of the sentence rather than out at the row's right edge. A sibling does not work: a
 * sentence filling the width measures as a full-width item, so the chip would wrap onto a line of
 * its own and read as a stray bullet.
 *
 * The chip carries no `clickable`, deliberately: the text already has a tap handler, and a nested
 * clickable inside inline content competes with it for the same gesture. The chip is found by
 * geometry instead - the tap's offset, or the bounding box of its placeholder.
 */
@Composable
private fun WordTapText(
    text: String,
    translatable: Boolean,
    onWord: (Words.Word) -> Unit,
    onUnit: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 19.sp,
    lineHeight: TextUnit = 30.sp,
    weight: FontWeight? = null,
) {
    if (!translatable) {
        // No translation offered for this row, so the text is inert: no chip, no tap handler.
        Text(
            text,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = weight,
            modifier = modifier,
        )
        return
    }

    // Read at gesture time, never during composition, so writing it does not recompose the row.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }

    val annotated = remember(text) {
        buildAnnotatedString {
            append(text)
            appendInlineContent(SENTENCE_ACTION, "EN")
        }
    }
    // The chip is appended, so it occupies exactly the one offset after the end of the text.
    val chipOffset = text.length

    Text(
        text = annotated,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = weight,
        inlineContent = mapOf(
            SENTENCE_ACTION to InlineTextContent(
                // Wider than the chip itself, with the chip held to its end: that slack IS the gap
                // between the last letter and the chip, which cannot be a space in the string
                // because a space would push the chip's offset past the end of the text.
                Placeholder(
                    width = SENTENCE_ACTION_SLOT,
                    height = SENTENCE_ACTION_HEIGHT,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) { SentenceAction() }
        ),
        onTextLayout = { layout.value = it },
        modifier = modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val l = layout.value ?: return@detectTapGestures
                val offset = l.getOffsetForPosition(pos)
                val onChip = offset == chipOffset || runCatching {
                    val box = l.getBoundingBox(chipOffset)
                    pos.x >= box.left && pos.x <= box.right &&
                        pos.y >= box.top && pos.y <= box.bottom
                }.getOrDefault(false)
                when {
                    onChip -> onUnit()
                    // A tap that resolves to something which is not a word at all - punctuation, a
                    // digit, past the end of the line - asks for the unit instead of doing nothing,
                    // because a tap that silently does nothing reads as a broken app.
                    else -> Words.at(text, offset)?.let(onWord) ?: onUnit()
                }
            }
        },
    )
}

/** The inline chip that opens a unit's translation. See the SENTENCE_ACTION constants above. */
@Composable
private fun SentenceAction() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                "EN",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

// ------------------------------------------------------- sentence translation

/**
 * The unit translation modal.
 *
 * One dialog for all three units, because they differ only in what they are called and in which
 * extension they offer: a WORD - whose context sentence is shown beneath it, and which can hand
 * over to that sentence - a SENTENCE, extendable with "Translate more", and a whole PASSAGE (a
 * heading or a stanza), which has no sentences to grow into.
 *
 * Deliberately the same shape the word dialog had: the unit stays visible behind the scrim and the
 * translation appears underneath it. On-device, so this is a "what does this say" answer, not a
 * grammar explanation.
 */
@Composable
fun TranslationDialog(
    source: String,
    translation: String?,
    busy: Boolean,
    error: String?,
    /** "word", "sentence" or "passage" - which unit is being shown. Only a label. */
    unit: String = "sentence",
    /**
     * Which engine produced this translation. Shown because the answer's reliability depends on it -
     * ML Kit and a 1.7B model fail differently - so the reader can weigh what they are reading.
     */
    engineLabel: String? = null,
    /**
     * The sentence a tapped word sits in, shown for context: one word out of context is precisely
     * the thing that translates ambiguously. Null for the other two units.
     */
    context: String? = null,
    /**
     * "German → English" for the resolved source language of this unit, or null when there is
     * nothing to translate (the source is already English) or its language is not known yet.
     */
    pair: String? = null,
    /**
     * The unit is already in the target language, so no model was called. This is the ordinary
     * outcome on a German book's English front matter.
     */
    alreadyEnglish: Boolean = false,
    /**
     * ML Kit is fetching the ~30 MB offline model for this pair. Distinct from ordinary
     * "Translating…" because it is a large, one-off download; it never shows once the model is
     * on the device.
     */
    downloading: Boolean = false,
    /**
     * Extend the open unit by one more sentence and translate the joined text. Non-null only
     * when the unit is a sentence with a following sentence in the same paragraph, so a
     * paragraph or heading tap - and the last sentence of a paragraph - shows nothing here.
     */
    onTranslateMore: (() -> Unit)? = null,
    /**
     * Translate the sentence the open word sits in - the way back to the sentence unit after a word
     * tap. Null unless a word was tapped inside a sentence.
     */
    onTranslateSentence: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // The extending actions ride next to "Close": each acts on the modal and none of them
            // dismisses - the reader reads the longer translation in place.
            Row {
                onTranslateSentence?.let { go -> TextButton(onClick = go) { Text("Translate sentence") } }
                onTranslateMore?.let { more -> TextButton(onClick = more) { Text("Translate more") } }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        title = { Text(unit.replaceFirstChar { it.uppercase() }, fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(source, style = MaterialTheme.typography.bodySmall)
                context?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                pair?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                engineLabel?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                when {
                    // Whether the model is downloading is known as soon as the language is, so
                    // it outranks the generic busy state: it is the answer to "why is this
                    // taking 90 seconds".
                    downloading -> Text(
                        "Downloading the offline model (~30 MB) — first time only…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    busy -> Text("Translating…", style = MaterialTheme.typography.bodySmall)
                    alreadyEnglish -> Text(
                        "This is already in English.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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

// ------------------------------------------------------------------ settings

/**
 * Settings: which engine translates, and the models each engine needs.
 *
 * The two halves are deliberately on one screen. An engine is not usable without its model, so
 * choosing an engine and fetching what it needs are the same decision, and separating them would
 * mean a reader could pick an engine that silently cannot run.
 *
 * What can be selected is therefore gated: an engine whose model is absent, or which this build
 * cannot run yet, shows why instead of being chosen and failing on the first tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    engine: Engine,
    onSelectEngine: (Engine) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs = remember { Models.packs() }
    var statuses by remember { mutableStateOf<Map<String, PackStatus>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        statuses = packs.associate { it.id to it.status(ctx) }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Translation engine", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Which engine translates a tapped word or sentence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(Engine.entries.toList()) { e ->
                val ready = e == Engine.MLKIT ||
                    packs.any { it.engine == e && statuses[it.id]?.state == PackState.READY }
                val canSelect = e.runnable && ready
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canSelect) { onSelectEngine(e) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = e == engine,
                        onClick = { if (canSelect) onSelectEngine(e) },
                        enabled = canSelect,
                    )
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(e.label, fontWeight = FontWeight.Medium)
                        Text(
                            e.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            !e.runnable -> Text(
                                "Not runnable in this build yet — the model can still be downloaded " +
                                    "and removed below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            !ready -> Text(
                                "Download its model below to switch to it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Models", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The Qwen models are large downloads (1.0 GB and 2.5 GB) and are used entirely " +
                            "on the device afterwards. ML Kit reports no download progress, so its " +
                            "packs show a spinner rather than a percentage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(packs) { pack ->
                val st = statuses[pack.id]
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.label, fontWeight = FontWeight.Medium)
                            Text(
                                packSizeLine(pack, st),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (st?.state) {
                            PackState.READY -> TextButton(
                                onClick = {
                                    scope.launch {
                                        busy = pack.id
                                        message = null
                                        runCatching { pack.delete(ctx) }
                                            .onFailure { message = "${pack.label}: ${it.message}" }
                                        busy = null
                                        refresh()
                                    }
                                },
                                enabled = busy == null,
                            ) { Text("Delete") }

                            PackState.DOWNLOADING -> {}

                            else -> TextButton(
                                onClick = {
                                    scope.launch {
                                        busy = pack.id
                                        message = null
                                        runCatching {
                                            pack.download(ctx) { p ->
                                                statuses = statuses + (
                                                    pack.id to PackStatus(PackState.DOWNLOADING, progress = p)
                                                    )
                                            }
                                        }.onFailure { message = "${pack.label}: ${it.message}" }
                                        busy = null
                                        refresh()
                                    }
                                },
                                enabled = busy == null,
                            ) { Text("Download") }
                        }
                    }
                    if (busy == pack.id) {
                        Spacer(Modifier.height(6.dp))
                        val progress = st?.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    st?.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider()
            }
            message?.let { msg ->
                item {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** One line of size information for a pack row: nominal, in progress, or what it occupies now. */
private fun packSizeLine(pack: ModelPack, status: PackStatus?): String {
    val nominal = formatSize(pack.approxBytes)
    return when (status?.state) {
        PackState.READY ->
            if (status.bytes != null) "On device: ${formatSize(status.bytes)}" else "On device"
        PackState.DOWNLOADING -> "Downloading…"
        else -> "Not downloaded · $nominal"
    }
}

/** A size in the unit a person would say it in: MB below a gigabyte, GB above it. */
internal fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    } else {
        "${bytes / 1_000_000} MB"
    }
