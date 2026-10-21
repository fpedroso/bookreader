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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
// The numbers themselves live in [ReaderType], because the paged reader MEASURES with them: a page
// break depends on exactly these heights, so the scroll view and the measurer must not be able to
// disagree about them.
private val PARA_GAP = ReaderType.PARA_GAP
private val HEAD_GAP_TOP = ReaderType.HEAD_GAP_TOP
private val HEAD_GAP_BOTTOM = ReaderType.HEAD_GAP_BOTTOM

// The page's text inset, on every block - heading and body alike - so the whole book keeps ONE left
// edge. It used to be the padding INSIDE the sentence band; the band is gone at the reader's request
// but the margin stayed, because without it the prose would slide under the headings' left edge and
// the column would read as ragged.
private val TEXT_INSET = ReaderType.TEXT_INSET

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
     * as plain, inert text with no word tap. That is the ordinary case for a book in any language
     * but German, and for an English block inside a German one.
     */
    val translatable: Boolean = false,
) {
    /**
     * A heading or a stanza: a block that is its own translation unit because it has no sentences to
     * grow into. A stanza's line breaks are part of what it means, so splitting it would turn verse
     * back into prose - the same rule the splitter follows.
     *
     * Prose is NOT a block unit: its translation unit is a sentence, reached from a word's modal.
     */
    val blockUnit: Boolean get() = heading || text.contains('\n')
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    title: String,
    rows: List<RowItem>,
    initialIndex: Int,
    /**
     * Cache key of the unit whose translation modal is open, outlined behind it. Only a BLOCK unit
     * (a heading or a stanza) is outlined: its translation can be opened without touching a
     * particular word, so the outline is the only sign of which block is up. A word or a sentence
     * needs none - the modal names the word it is about.
     */
    selectedKey: String? = null,
    /**
     * The open book's word counts, or null when shading is off. Not computed here: the counts are a
     * property of the whole book, so they are taken once when it opens.
     */
    frequency: WordFrequency? = null,
    onParagraphTap: (RowItem) -> Unit,
    /**
     * A word was tapped. The word carries its own span in the row's text, which is what the caller
     * needs to find the sentence around it - there is no sentence index any more, because the reader
     * no longer lays a paragraph out sentence by sentence.
     */
    onWordTap: (RowItem, Words.Word) -> Unit,
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
                // ONE composable per block, laid out the way the EPUB wrote it. The reader used to
                // split a paragraph into one sentence per line with a 12dp gap between them, because
                // the sentence was the tap unit and the EN chip at the end of each line was how you
                // asked for it. Both are gone at Felipe's request: the text is a WORD tap, the
                // sentence is reached from that word's modal, and a paragraph now flows as the book
                // intends - no ragged right edge, no invented gaps, no chip appended to the text.
                val selected = row.blockUnit && row.hash != null && row.hash == selectedKey
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
                    WordTapText(
                        text = row.text,
                        translatable = row.translatable,
                        onWord = { onWordTap(row, it) },
                        // A heading or a stanza is a unit in its own right, so a tap that misses every
                        // word asks for the whole block - which is the only way to translate one, now
                        // that the chip is gone. Prose passes null: there the nearest word answers,
                        // because translating a whole paragraph is far more than the finger asked for.
                        onBlock = if (row.blockUnit) ({ onParagraphTap(row) }) else null,
                        // A title is set apart by size and weight only - larger and heavier than the
                        // 19sp body, with space below so the first paragraph does not crowd it. The
                        // horizontal inset matches every other block's, so the page keeps one left
                        // edge.
                        fontSize = if (row.heading) MaterialTheme.typography.titleLarge.fontSize else 19.sp,
                        lineHeight = if (row.heading) MaterialTheme.typography.titleLarge.lineHeight else 30.sp,
                        weight = if (row.heading) FontWeight.SemiBold else null,
                        // Not shaded on a heading, or on a block whose words were never counted: a
                        // faded word inside a title reads as a rendering fault, and an English block
                        // inside a German book has no band of its own - its words would all come
                        // back "rare" against counts that were taken from the German text.
                        frequency = if (row.heading || !row.translatable) null else frequency,
                        modifier = Modifier.padding(
                            start = TEXT_INSET,
                            end = TEXT_INSET,
                            bottom = if (row.heading) HEAD_GAP_BOTTOM else 0.dp,
                        ),
                    )
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
 * layout instead.
 *
 * A tap in whitespace no longer asks for a unit, because there is no sentence-shaped unit at the
 * text level any more: where the block is a unit of its own ([onBlock], a heading or a stanza) the
 * block answers, and in prose the nearest word does. See [Words.atNearest] for why that beat the
 * alternatives.
 */
@Composable
private fun WordTapText(
    text: String,
    translatable: Boolean,
    onWord: (Words.Word) -> Unit,
    /**
     * What a tap that is not on a word means, for a block that is its own translation unit.
     *
     * Non-null only for a heading or a stanza. Null for prose, where a tap between words resolves to
     * the nearest word instead - tapping a paragraph to translate the whole thing is far more than
     * the finger asked for.
     */
    onBlock: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 19.sp,
    lineHeight: TextUnit = 30.sp,
    weight: FontWeight? = null,
    /**
     * The book's word counts, when shading is on. Null leaves the prose in one colour.
     *
     * Passed as the whole [WordFrequency] rather than a band lookup so the `remember` key below is
     * a stable object: a lambda would be a new instance on every recomposition and rebuild every
     * visible block's spans for nothing.
     */
    frequency: WordFrequency? = null,
) {
    if (!translatable) {
        // No translation offered for this row, so the text is inert: no tap handler at all.
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

    // Resolved here, outside buildAnnotatedString: reading the theme is a composable call and the
    // annotated-string builder is not a composable scope. Built from the enum rather than from a
    // hand-written map of three bands - the hand-written version silently omitted UNCOMMON when the
    // fifth band was added, and those words fell back to plain text.
    val dark = isDarkTheme()

    val annotated = remember(text, frequency, dark) {
        val tints = Band.entries.associateWith { tintFor(it, dark) }
        buildAnnotatedString {
            if (frequency == null) {
                append(text)
            } else {
                // The words are appended in their own styled runs and every gap - space,
                // punctuation, an em dash - is appended plain, so the string built here is
                // character-for-character the string that was handed in. That matters: the tap
                // handler resolves an offset against this text, and shading must not move one.
                var at = 0
                for (span in shadedSpans(text, frequency::bandOf)) {
                    append(text.substring(at, span.start))
                    val colour = tints[span.band]
                    val word = text.substring(span.start, span.end)
                    if (colour == null) append(word)
                    else withStyle(SpanStyle(color = colour)) { append(word) }
                    at = span.end
                }
                append(text.substring(at))
            }
        }
    }

    Text(
        text = annotated,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = weight,
        onTextLayout = { layout.value = it },
        modifier = modifier.pointerInput(text) {
            detectTapGestures { pos ->
                val l = layout.value ?: return@detectTapGestures
                val offset = l.getOffsetForPosition(pos)
                val exact = Words.at(text, offset)
                when {
                    exact != null -> onWord(exact)
                    // Not on a word. Where the block is itself a unit - a heading, a stanza - that is
                    // what was meant, and it is the only way to translate one now that there is no
                    // chip. In prose the NEAREST word answers instead: a paragraph tap would
                    // translate far more than the finger asked for, and a tap that does nothing at
                    // all reads as a broken app.
                    onBlock != null -> onBlock()
                    else -> Words.atNearest(text, offset)?.let(onWord)
                }
            }
        },
    )
}

// ------------------------------------------------------- sentence translation

/**
 * The translation modal - and there is only one.
 *
 * It shows whatever was asked for: one word, one sentence, or a whole block. The chrome does not
 * change with the unit - one title ("Translation"), one extension ("Translate more", which App
 * interprets for whatever is open) - because the reader asked for a translation, not for a label
 * saying which kind of thing they tapped. What is being translated is the first line of the body,
 * which is where they are looking anyway.
 *
 * Deliberately the same shape the word dialog had: what is being translated stays visible behind the
 * scrim and the translation appears underneath it. On-device, so this is a "what does this say"
 * answer, not a grammar explanation.
 */
@Composable
fun TranslationDialog(
    source: String,
    translation: String?,
    busy: Boolean,
    error: String?,
    /**
     * The open unit is a WORD, so the sentence it sits in is NOT shown beneath it: the reader asked
     * for one word, and the context line is noise beside an answer that is a single word long.
     *
     * It is the only thing the modal knows about what it is showing. The title is always
     * "Translation" - the reader asked for a translation, not for a classification - and the button
     * that grows the unit always reads "Translate more". A block unit (a heading, a stanza) passes
     * false and brings no context of its own, so nothing appears for it either.
     */
    wordUnit: Boolean = false,
    /**
     * A warning, not provenance: set only when the engine that answered is NOT the one chosen, e.g.
     * OpenRouter was selected and ML Kit answered because no key is saved. Null in the ordinary case,
     * where naming the engine and its model on top of the translation is noise.
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
     * "appears 23× in this book" - how often the tapped word occurs in the book being read. Shown
     * for a WORD only.
     *
     * The raw count rather than a band name, deliberately: a count is a fact the reader can check
     * against the page, a band name is jargon. It is also what makes the shading mean something on
     * a single word, since the page can only ever show four tones.
     */
    frequencyNote: String? = null,
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
     * Grow the open unit by one step: from a word, that step is the sentence it sits in; from a
     * sentence, it is that sentence plus the next one. Null when there is nothing to grow into.
     *
     * ONE callback and ONE label on purpose. The modal no longer says whether it is showing a word or
     * a sentence, so a reader who presses "Translate more" is asking for more of the same thing
     * either way - and App decides what that means for the unit that is open.
     */
    onMore: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // "Translate more" rides next to "Close": it acts on the modal and does not dismiss -
            // the reader reads the longer translation in place.
            Row {
                onMore?.let { more -> TextButton(onClick = more) { Text("Translate more") } }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        // One title for every unit. What is being translated is on the first line of the body, which
        // is where the reader looks anyway; a title that said WORD or SENTENCE classified the
        // request instead of answering it.
        title = { Text("Translation", fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(source, style = MaterialTheme.typography.bodySmall)
                pair?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // How often this word recurs in the book, for a word tap only. It sits with the
                // word rather than under the translation because it describes the word, not the
                // answer.
                frequencyNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The sentence a tapped word came from is context for weighing prose; for one word the
                // reader asked for the word alone, so it is not shown there.
                if (!wordUnit) {
                    context?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // A warning is not decoration: if the engine that answered is not the one chosen, it
                // shows whatever the unit is. In the ordinary case App passes null and nothing appears.
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
    settings: AppSettings,
    onSelectEngine: (Engine) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs = remember { Models.packs() }
    var statuses by remember { mutableStateOf<Map<String, PackStatus>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    /**
     * Draft copies of the three text fields.
     *
     * Deliberately NOT keyed on the stored values: keying a remembered editable state on the value it
     * writes recreates the state object on every keystroke, which fights the IME's composition and
     * duplicating or dropping characters. The stored value is the initial value; after that the field
     * owns its text and every edit is written through, so nothing is lost by leaving the screen.
     */
    var key by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.apiModel) }
    var endpoint by remember { mutableStateOf(settings.apiEndpoint) }
    var abbrevDraft by remember { mutableStateOf(settings.abbreviations) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    /**
     * The outcome of the Test button, kept beside the button rather than in the shared list message:
     * feedback for a button has to appear where the button is, not at the end of a long screen.
     */
    var testResult by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }

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
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Light or dark. Applies as soon as it is picked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ThemeMode.entries.toList()) { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSettings(settings.copy(theme = m.id)) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = ThemeMode.fromId(settings.theme) == m,
                        onClick = { onSettings(settings.copy(theme = m.id)) },
                    )
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(m.label, fontWeight = FontWeight.Medium)
                        Text(
                            m.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Reading", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Shading counts the words of THIS book - no word list, nothing sent anywhere, " +
                            "and no word excluded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    ) {
                        Text("Shade word frequency", fontWeight = FontWeight.Medium)
                        Text(
                            "Words this book keeps repeating stay solid; words it barely uses fade. " +
                                "A faint word is one not worth learning yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.wordShading,
                        onCheckedChange = { onSettings(settings.copy(wordShading = it)) },
                    )
                }
                HorizontalDivider()
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    ) {
                        Text("Use scroll instead of pages", fontWeight = FontWeight.Medium)
                        Text(
                            "Off, the book is paginated: a tap on the right third goes forward, the left " +
                                "third back, and the page count sits under the text. A word is asked about " +
                                "with a LONG press. Turn this on to scroll instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.useScroll,
                        onCheckedChange = { onSettings(settings.copy(useScroll = it)) },
                    )
                }
                HorizontalDivider()
            }
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
                // The same readiness rule the reader path uses, so the picker cannot offer an
                // engine that a tap would refuse.
                val ready = Models.ready(ctx, e, settings)
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
                                "Not runnable in this build yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            !ready -> Text(
                                "Add an API key below to switch to it.",
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
                    Text("OpenRouter (cloud)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This is the one engine where the text leaves the device: every word or " +
                            "sentence you tap is sent to the endpoint below, and nothing else in the " +
                            "app leaves the phone. The key is kept in this app's private files and is " +
                            "excluded from cloud backup. Any OpenAI-compatible endpoint works here, " +
                            "including a server running on your own machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onSettings(settings.copy(apiKey = it.trim()))
                        },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Placed directly under the key field on purpose: after typing a key the keyboard
                    // covers the lower half of the screen, and the button that checks that key must
                    // not be the thing hidden behind it.
                    TextButton(
                        onClick = {
                            scope.launch {
                                testing = true
                                testResult = null
                                val res = runCatching {
                                    OpenRouter.translate(
                                        endpoint = settings.apiEndpoint,
                                        apiKey = settings.apiKey,
                                        model = settings.apiModel,
                                        text = "Der Arzt kam am Morgen.",
                                    )
                                }
                                testFailed = res.isFailure
                                testResult = res.fold(
                                    { "The key works. It translated a test sentence as: \"$it\"" },
                                    { it.message ?: "the call failed" },
                                )
                                testing = false
                            }
                        },
                        enabled = !testing && key.isNotBlank(),
                    ) { Text(if (testing) "Testing…" else "Test the key") }
                    testResult?.let { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            onSettings(settings.copy(apiModel = it.trim()))
                        },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The ids we actually tested, so nobody has to guess or paste a typo.
                    Text(
                        "Tested: deepseek/deepseek-v4.1-flash (best on your sentences), " +
                            "mistralai/mistral-small-24b-instruct-2501 (cheapest), " +
                            "tencent/hy-mt2-7b (purpose-built translation). " +
                            "Any OpenAI-compatible model id works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = {
                            endpoint = it
                            onSettings(settings.copy(apiEndpoint = it.trim()))
                        },
                        label = { Text("Endpoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Sentences", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Words after which a dot does not end a sentence — so `Mr. Smith` stays one " +
                            "sentence instead of being cut in two. Separate with commas or new lines; " +
                            "the dot is optional (`Mr.` and `Mr` both work). Clear the list to split " +
                            "on punctuation alone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = abbrevDraft,
                        onValueChange = { text ->
                            abbrevDraft = text
                            onSettings(settings.copy(abbreviations = text))
                        },
                        label = { Text("Does not end a sentence") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Offline language packs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ML Kit translates on the device using these ~30 MB packs, and needs both the " +
                            "source and the target language. ML Kit reports no download progress, so " +
                            "these rows show a spinner rather than a percentage. The cloud engine " +
                            "needs no pack at all.",
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
