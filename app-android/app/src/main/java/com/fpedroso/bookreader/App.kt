package com.fpedroso.bookreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
// compose-ui's own LocalLifecycleOwner rather than androidx.lifecycle.compose's: the latter
// lives in lifecycle-runtime-compose, which is not a declared dependency, and one deprecation
// warning beats adding an artifact for a single import.
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Applied before the first frame, so the window never flashes the wrong palette at startup.
        // Cheap: one small file read, the same one App does a moment later.
        Appearance.apply(Store.loadSettings(this).theme)
        setContent {
            BookReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var library by remember { mutableStateOf(Store.load(ctx)) }
    var openBook by remember { mutableStateOf<BookMeta?>(null) }
    // The reader's own choices, and whether Settings is open. Settings sits above the library and
    // below nothing: it is reachable only from the library, so it cannot be open while reading.
    var settings by remember { mutableStateOf(Store.loadSettings(ctx)) }
    var showSettings by remember { mutableStateOf(false) }
    // Settings is the only writer of the theme, and it is rendered inside this composable - below
    // the theme call in MainActivity - so push the choice out to the shared state and the whole
    // window recolours on the spot instead of after a restart.
    LaunchedEffect(settings.theme) { Appearance.apply(settings.theme) }
    // The engine actually used. Resolved rather than derived from the stored id: a local model has to
    // be both runnable and ON THE DEVICE, and either can change while the app runs, so this is
    // recomputed whenever Settings closes.
    val selectedEngine = Engine.fromId(settings.engine)
    /**
     * The honourific/abbreviation list, parsed once per edit. A change has to re-split the book, so
     * this value (not the raw string) is what the reader remembers rows by.
     */
    val abbreviations = remember(settings.abbreviations) {
        Sentences.abbreviationSet(settings.abbreviations)
    }
    var engine by remember { mutableStateOf(Engine.MLKIT) }
    LaunchedEffect(settings, showSettings) {
        if (!showSettings) {
            val next = Models.resolve(ctx, selectedEngine, settings)
            if (next != engine) engine = next
        }
    }
    var parsed by remember { mutableStateOf<EpubParser.Book?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("") }
    // Sentence translation. Tapping a sentence opens a modal with its English translation,
    // produced on-device by ML Kit.
    var tapKey by remember { mutableStateOf<String?>(null) }
    var tapSource by remember { mutableStateOf("") }
    var tapLang by remember { mutableStateOf<String?>(null) }
    // The open unit's sentence span, when the tap landed on a sentence: [tapRow].sentences is the
    // paragraph's sentence list and [tapStart]/[tapCount] name the run currently translated.
    // tapRow is null for a paragraph or heading tap - there is no following sentence to extend
    // to, so the modal shows no "Translate more" there.
    var tapRow by remember { mutableStateOf<RowItem?>(null) }
    var tapStart by remember { mutableStateOf(0) }
    var tapCount by remember { mutableStateOf(1) }
    // Whether the open unit is a WORD rather than a sentence. The two share tapRow/tapStart/tapCount
    // - a word tap sets them to the sentence it sits in - so this is what decides which extension
    // the modal offers: a word hands over to its sentence, a sentence grows by one.
    var tapIsWord by remember { mutableStateOf(false) }
    // The sentence a tapped word sits in, shown under the word in the modal. A word alone is
    // exactly the case that translates ambiguously, so the context is the answer's evidence.
    var tapContext by remember { mutableStateOf<String?>(null) }
    var translation by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var alreadyEnglish by remember { mutableStateOf(false) }
    var translateError by remember { mutableStateOf<String?>(null) }
    var translations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lastIndex by remember { mutableStateOf(0) }
    // Where in that block reading stopped, and the block's hash. Both are what make the position
    // survive a font change and a re-import: a page number survives neither.
    var lastOffset by remember { mutableStateOf(0) }
    var lastHash by remember { mutableStateOf<String?>(null) }
    var lastTransPersist by remember { mutableStateOf(0L) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { importPicked(ctx, uri) }
            library = Store.load(ctx)
        }
    }

    // Parse on open, off the main thread: a book's text is large.
    LaunchedEffect(openBook) {
        val b = openBook ?: return@LaunchedEffect
        loading = true
        translations = emptyMap()
        tapKey = null
        var loadedTranslations: Map<String, String> = emptyMap()
        val result = withContext(Dispatchers.IO) {
            val p = runCatching { EpubParser.parse(File(b.epubFile)) }.getOrNull()
            // Trust the EPUB's dc:language, and fall back to how the book was imported.
            val bookLang = p?.language ?: b.lang
            // Translations cached on an earlier read, keyed by block hash.
            val t: Map<String, String> = runCatching {
                Json.decodeFromString(
                    Translations.serializer(),
                    transPath(ctx, b.id).readText(Charsets.UTF_8),
                ).blocks
            }.getOrNull().orEmpty()
            loadedTranslations = t
            p to bookLang
        }
        parsed = result.first
        lang = result.second
        translations = loadedTranslations
        loading = false
    }

    /** Leave the book, persisting the reading position once. */
    val closeBook: () -> Unit = {
        val b = openBook
        if (b != null) {
            Store.setProgress(ctx, b.id, lastIndex, lastOffset, lastHash)
            val tr = translations
            if (tr.isNotEmpty()) {
                scope.launch { withContext(Dispatchers.IO) { persistTranslations(ctx, b.id, tr) } }
            }
        }
        library = Store.load(ctx)
        openBook = null
        parsed = null
        translations = emptyMap()
    }

    // The system back gesture must behave like the in-app "←" rather than finishing the
    // Activity. It also closes the translation modal first, since that sits on top, and Settings
    // before anything else - it is the outermost layer.
    BackHandler(enabled = openBook != null || tapKey != null || showSettings) {
        when {
            showSettings -> showSettings = false
            tapKey != null -> tapKey = null
            else -> closeBook()
        }
    }

    // Persist the reading position when the app goes to the background, not only when the book
    // is closed. Reading is the one thing here where the phone gets put down mid-page, and
    // closeBook only runs on "←" or back: swiping the app out of recents, an OS kill for memory
    // or a crash skips it entirely, and the position then silently reverts to wherever it was
    // last written. ON_STOP fires on the way out, so this covers all three.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, openBook) {
        val b = openBook
        if (b == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Written synchronously: ON_STOP gives a short window, and a coroutine on
                // another dispatcher is not guaranteed to finish before the process goes away.
                // Both calls are plain blocking IO, which is what makes this possible.
                Store.setProgress(ctx, b.id, lastIndex, lastOffset, lastHash)
                val tr = translations
                if (tr.isNotEmpty()) persistTranslations(ctx, b.id, tr)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = openBook
    if (showSettings) {
        SettingsScreen(
            // The STORED choice, not the effective engine: if a build cannot run what is stored, the
            // radio should still show what was chosen rather than silently rewrite it.
            engine = selectedEngine,
            settings = settings,
            onSelectEngine = { picked ->
                // copy(), never a fresh AppSettings: the API key, model and endpoint live in the
                // same record, and selecting an engine must not clear them.
                settings = settings.copy(engine = picked.id)
                Store.saveSettings(ctx, settings)
            },
            onSettings = { next ->
                settings = next
                Store.saveSettings(ctx, next)
            },
            onBack = { showSettings = false },
        )
    } else if (current == null) {
        LibraryScreen(
            books = library.books,
            onImport = { picker.launch(arrayOf("application/epub+zip", "*/*")) },
            onOpen = { openBook = it },
            onDelete = {
                Store.remove(ctx, it.id)
                library = Store.load(ctx)
            },
            onSettings = { showSettings = true },
        )
    } else if (loading || parsed == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
    } else {
        val book = parsed!!
        // No abbreviations here any more: sentences are split on demand at a tap, so changing that
        // setting no longer has to re-split and re-render the whole book.
        val rows = remember(book, lang) { buildRows(book, lang) }
        /**
         * How often each word recurs in this book, counted once when it opens.
         *
         * Off the main thread: a novel is ~80,000 tokens and this walks every one of them. It counts
         * only the blocks the reader would translate, so a German novel's English front matter - and
         * a graded reader's inline English glossary - cannot pollute the bands with English words.
         *
         * Null until it lands, so the first frames render unshaded. Counted from the book rather
         * than from what is on screen: shading that depended on the viewport would change as the
         * reader scrolled, and the bands are a property of the book.
         */
        var frequency by remember(book, lang) { mutableStateOf<WordFrequency?>(null) }
        LaunchedEffect(book, lang) {
            frequency = withContext(Dispatchers.Default) {
                val texts = book.chapters
                    .flatMap { ch -> ch.blocks }
                    .filter { translationOffered(lang, it.lang) }
                    .map { bl -> bl.text }
                WordFrequency.of(texts)
            }
        }

        // Tap a unit -> its translation, cached by key so re-tapping is free. The unit is
        // one sentence (extendable by "Translate more"), or a whole paragraph for headings
        // and verse.
        //
        // The source language is resolved per BLOCK, not per book:
        //   1. the block's own lang/xml:lang, or an ancestor's - resolved by the parser
        //   2. the document's <html lang> - also the parser, as the outermost ancestor
        //   3. dc:language, which the parser applies as the block's fallback
        //   4. how the book was imported (BookMeta.lang)
        //   5. only if all of those are silent: ML Kit Language Identification on the text
        // A German novel whose front matter is English is the case this exists for: at book
        // level the prose is "German", and it was being translated as German - to nothing.
        //
        // Off the main thread: steps 5 and the translation itself can each take seconds, and
        // the first translation in a language also downloads its ~30 MB model.
        fun openTranslation(key: String?, source: String, blockLang: String?) {
            // Levels 1-4, known without any work: the block's own lang/xml:lang (or an
            // ancestor's, or the document's <html lang>), then dc:language, then the
            // import-time hint. Only if all of those are silent is Language ID called.
            val declared = blockLang?.takeIf { it.isNotBlank() } ?: lang.takeIf { it.isNotBlank() }
            tapKey = key
            tapSource = source
            // Set up front, not from the coroutine: a cached translation never enters it, and
            // the modal still has to say which languages the pair is.
            tapLang = declared
            translation = key?.let { translations[it] }
            alreadyEnglish = alreadyTarget(declared)
            downloading = false
            translateError = null
            translating = false
            if (key == null || translation != null) return

            translating = true
            scope.launch {
                val src = declared ?: withContext(Dispatchers.Default) { Translate.identify(source) }
                if (src == null) {
                    // Reported rather than guessed: a made-up source language mistranslates
                    // silently, which is worse than saying we do not know.
                    translating = false
                    translateError = "the book's language could not be determined"
                    return@launch
                }
                tapLang = src
                if (alreadyTarget(src)) {
                    // English into English: the model would be downloaded to hand back the
                    // same words, so it is not called at all.
                    translating = false
                    alreadyEnglish = true
                    return@launch
                }
                // Only ML Kit pulls a model down on demand. A local model is either on the device or
                // the call fails saying it is not.
                downloading = engine == Engine.MLKIT &&
                    withContext(Dispatchers.Default) { Translate.needsModelDownload(src) }
                // Reading a local model off disk happens on the first tap after a cold start. A cloud
                // engine has nothing to load, so it must not claim to be loading one.
                val res = runCatching {
                    withContext(Dispatchers.Default) {
                        when (engine) {
                            Engine.MLKIT -> Translate.paragraph(ctx, src, source, abbreviations)
                            // The cloud engine is the one place the text leaves the device; the
                            // endpoint, key and model all come from Settings.
                            Engine.OPENROUTER -> OpenRouter.translate(
                                endpoint = settings.apiEndpoint,
                                apiKey = settings.apiKey,
                                model = settings.apiModel,
                                text = source,
                                // The sentence a word was tapped in, when there is one: it is what
                                // tells "Bank" the bench from "Bank" the bank.
                                context = tapContext,
                            )
                        }
                    }
                }
                downloading = false
                translating = false
                res.fold(
                    onSuccess = { text ->
                        translation = text
                        translations = translations + (key to text)
                        // Throttled: re-encoding a growing map on every tap costs more
                        // than the translation. closeBook always writes a final copy.
                        val now = System.currentTimeMillis()
                        if (now - lastTransPersist > 5_000) {
                            lastTransPersist = now
                            val snap = translations
                            withContext(Dispatchers.IO) {
                                persistTranslations(ctx, current.id, snap)
                            }
                        }
                    },
                    onFailure = { e ->
                        translateError = e.message ?: e.javaClass.simpleName
                    },
                )
            }
        }

        // A WORD: the only unit the text itself offers. It is still ML Kit's call, exactly as a
        // sentence was - there is no dictionary in this app any more - so what comes back is one
        // rendering of the word, with no lemma, part of speech or alternative senses. The sentence it
        // sits in is the context: folded into the cache key, and what "Translate more" grows to.
        // It is NOT shown under the word - the reader asked for one word.
        val onWordTap: (RowItem, Words.Word) -> Unit = { para, word ->
            // The row's sentences are split ON DEMAND, at the offset the tap reported. The reader no
            // longer lays a paragraph out sentence by sentence, so there is no index to carry: the
            // sentence has to be found where the word actually is. Cheap - one short paragraph - and
            // it means the splitter runs on exactly the text the reader is looking at.
            //
            // A heading or a stanza has none: it is one unit end to end, and splitting it would
            // collapse its line breaks into prose and hand back a sentence that never existed.
            val sentences =
                if (para.blockUnit) emptyList()
                else Sentences.split(para.text, lang, abbreviations)
            val index = sentences.indexOfFirst { word.start in it.start until it.end }
            val sentence = sentences.getOrNull(index)
            tapRow = para
            tapStart = index.coerceAtLeast(0)
            tapCount = 1
            tapIsWord = true
            tapContext = sentence?.text
            openTranslation(
                // The engine is part of the key, and for an engine that reads context so is the
                // sentence: the same word in two sentences is two different translations.
                key = unitKey(engine, word.text, sentence?.text, settings.apiModel),
                source = word.text,
                blockLang = para.lang,
            )
        }

        // A heading or a stanza: the block is the unit, and there are no sentences inside it to grow
        // into, so nothing is held for "Translate more". Shared by both reading modes - the unit is
        // the same thing whether the page turns or scrolls.
        val openBlock: (RowItem) -> Unit = { para ->
            tapRow = null
            tapIsWord = false
            tapContext = null
            openTranslation(unitKey(engine, para.text, model = settings.apiModel), para.text, para.lang)
        }

        // What to open on. The hash is preferred when there is one: a row index is only meaningful
        // against the exact text it was recorded from, and a book imported again can shift them all.
        val anchorBlock = current.anchorHash
            ?.let { hash -> rows.indexOfFirst { it.hash == hash } }
            ?.takeIf { it >= 0 }
            ?: current.lastBlock.coerceIn(0, (rows.size - 1).coerceAtLeast(0))

        // The counts are always taken (the word modal needs them), but the page is only shaded when
        // the reader asked for it - so the toggle costs nothing but a tint.
        val shading = if (settings.wordShading) frequency else null

        if (settings.useScroll) {
            ReaderScreen(
                title = current.title,
                rows = rows,
                initialIndex = anchorBlock,
                selectedKey = tapKey,
                onParagraphTap = openBlock,
                onWordTap = onWordTap,
                frequency = shading,
                onProgress = { index ->
                    lastIndex = index
                    // No offset in a scrolled view: the position is the top row and nothing finer.
                    lastOffset = 0
                    lastHash = rows.getOrNull(index)?.hash
                },
                onBack = closeBook,
            )
        } else {
            // Pagination. The page number is never stored, only DERIVED from the anchor on each
            // open, because it stops meaning anything the moment the text reflows.
            PagedReader(
                title = current.title,
                bookId = current.id,
                rows = rows,
                anchorBlock = anchorBlock,
                anchorOffset = current.anchorOffset,
                selectedKey = tapKey,
                frequency = shading,
                onWordTap = onWordTap,
                onBlockTap = openBlock,
                onPosition = { block, offset ->
                    lastIndex = block
                    lastOffset = offset
                    lastHash = rows.getOrNull(block)?.hash
                },
                onBack = closeBook,
            )
        }
        tapKey?.let {
            val word = tapIsWord
            // The span one sentence longer, or null at the paragraph's end - the modal hides
            // "Translate more" rather than offering an action that would do nothing. A WORD shows no
            // "Translate more" at all: it hands over to its own sentence instead, which is the unit a
            // reader actually wants next.
            //
            // The sentences are split on demand here too: the reader keeps no per-row sentence list
            // any more, and this needs the span AFTER the open one. Only the texts are wanted - the
            // offsets were the tap's business and are long gone. A block unit has none, for the same
            // reason onWordTap gives it none: splitting a stanza invents sentences that never existed.
            val openSentences = if (tapRow?.blockUnit == true) {
                emptyList()
            } else {
                tapRow?.let { row -> Sentences.split(row.text, lang, abbreviations).map { it.text } }
                    ?: emptyList()
            }
            val moreSpan =
                if (word) null
                else nextSpan(openSentences, UnitSpan(tapStart, tapCount))
            // ONE "more" action for the modal, which no longer distinguishes a word from a sentence.
            // From a WORD: the whole block for a heading or a stanza - they have no sentences - and
            // otherwise the sentence the word sits in. From a SENTENCE: that sentence plus the next.
            // Either way the action is the same (translate that text under ITS own key) and the modal
            // stops being about a single word, so the button can say the same thing every time.
            val wordMore: String? = if (word) {
                val para = tapRow
                if (para?.blockUnit == true) para.text else tapContext
            } else null
            val onMore: (() -> Unit)? = when {
                word -> wordMore?.let { target ->
                    {
                        tapIsWord = false
                        tapContext = null
                        openTranslation(unitKey(engine, target, model = settings.apiModel), target, tapRow?.lang)
                    }
                }
                else -> moreSpan?.let { span ->
                    {
                        if (openSentences.isNotEmpty()) {
                            // Translate the JOINED text as one unit, under its OWN key.
                            // unitKey(joined) hashes different text than the fragment,
                            // so it is a different cache key: openTranslation cannot serve the
                            // fragment's translation here, and storing the joined result leaves
                            // the fragment's entry untouched for a later tap. Re-pressing to
                            // this same span hashes the same text, so it hits the cache.
                            val joined = unitSource(openSentences, span)
                            tapStart = span.start
                            tapCount = span.count
                            openTranslation(unitKey(engine, joined, model = settings.apiModel), joined, tapRow?.lang)
                        }
                    }
                }
            }
            TranslationDialog(
                source = tapSource,
                translation = translation,
                busy = translating,
                error = translateError,
                // The modal is the same for every unit; this is the only thing it needs to know - that
                // a word's own sentence is not shown beneath it.
                wordUnit = word,
                context = tapContext,
                pair = languagePair(tapLang),
                // Only a word carries its frequency: on a sentence the equivalent line would have to
                // describe a whole span of words at once, which is a summary, not a fact.
                frequencyNote = if (word) frequency?.describe(tapSource) else null,
                // Provenance only when it is NOT what the reader chose: if OpenRouter was selected and
                // ML Kit answered instead, that is worth knowing. When the chosen engine answered,
                // naming it and its model is just noise on top of the translation - the reader said so,
                // and for a one-word answer the noise was worse than the line.
                engineLabel = if (engine == selectedEngine) null
                else engineProvenance(engine, selectedEngine, settings),
                alreadyEnglish = alreadyEnglish,
                downloading = downloading,
                onMore = onMore,
                onDismiss = {
                    tapKey = null
                    tapRow = null
                    tapIsWord = false
                    tapContext = null
                },
            )
        }
    }
}

/**
 * What the translation modal says above the answer: the engine that produced it, and the model id
 * when the engine does not pin one.
 *
 * When the stored choice could not be used (no model downloaded, no key saved), the line names what
 * answered instead AND why the choice was not honoured - otherwise a reader who picked a 4B model and
 * got ML Kit's output would blame the model.
 */
internal fun engineProvenance(active: Engine, selected: Engine, settings: AppSettings): String {
    val label = if (active.cloud) "${active.label} · ${settings.apiModel}" else active.label
    if (active == selected) return label
    /**
     * Why the chosen engine could not be used, for the modal's provenance line.
     *
     * Derived rather than assumed: today only the cloud engine can be chosen and unusable (ML Kit is
     * always ready), but writing it this way means adding an engine later cannot mislabel the reason.
     */
    val why = if (selected.cloud) "no API key saved" else "not runnable in this build"
    return "$label (${selected.label}: $why)"
}

private fun buildRows(
    book: EpubParser.Book,
    lang: String,
): List<RowItem> {
    val out = ArrayList<RowItem>()
    // No chapter-title row: the parser has no title to give one (see EpubParser.Chapter), and
    // the reader does not need it - a chapter announces itself with the <h1>-<h6> headings the
    // body already contains, which arrive here as heading blocks.
    for (ch in book.chapters) {
        for (bl in ch.blocks) {
            // Every paragraph carries its block hash: it identifies the block and is the tap
            // target for the modal. (Translation keys are the hash of the UNIT's own text,
            // not this one - see translationKey.)
            out.add(
                RowItem(
                    text = bl.text,
                    heading = bl.heading,
                    hash = Annotations.key(bl.text),
                    // The block's own resolved language, so the tap translates with the
                    // language that block is actually written in.
                    lang = bl.lang,
                    // Whether the row offers translation at all. Resolved here rather than in the
                    // reader so the whole book is decided in one place: German books only, and
                    // never for a block that is already English.
                    translatable = translationOffered(lang, bl.lang),
                )
            )
        }
    }
    return out
}

/** Where sentence translations are cached for a book. */
private fun transPath(context: Context, id: String): File =
    File(File(Store.booksDir(context), id), "translations.json")

/**
 * Persist translations alongside the book, keyed by block hash, so a sentence keeps its
 * translation even if the book is re-imported. Written via a temp file: a same-filesystem
 * rename is atomic, so a crash mid-write cannot leave a half-parsed cache behind.
 */
private fun persistTranslations(context: Context, id: String, blocks: Map<String, String>) {
    runCatching {
        val target = transPath(context, id)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(
            Json.encodeToString(Translations.serializer(), Translations(blocks = blocks)),
            Charsets.UTF_8,
        )
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

// ------------------------------------------------------------------- import
private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

private fun copyIn(context: Context, uri: Uri, dest: File) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { input.copyTo(it) }
    }
}

/** Import one EPUB: copy it in, read its metadata, and record it in the library. */
private fun importPicked(context: Context, uri: Uri): Int {
    val name = displayName(context, uri) ?: "book.epub"
    val staging = File(context.filesDir, "staging")
    staging.deleteRecursively()
    staging.mkdirs()

    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val staged = File(staging, "s0_$safe")
    runCatching { copyIn(context, uri, staged) }.getOrNull()
    if (!staged.exists()) {
        staging.deleteRecursively()
        return 0
    }

    val parsedBook = runCatching { EpubParser.parse(staged) }.getOrNull()
    val base = name.substringBeforeLast('.')
    val title = parsedBook?.title?.takeIf { it.isNotBlank() } ?: base
    val id = Store.idFor(title + base)

    val dir = File(Store.booksDir(context), id)
    dir.mkdirs()
    val epubDest = File(dir, "book.epub")
    staged.copyTo(epubDest, overwrite = true)

    Store.add(
        context,
        BookMeta(
            id = id,
            title = title,
            epubFile = epubDest.absolutePath,
            // Unknown is a real answer. Defaulting to "zh" turned a metadata failure into
            // "this book is Chinese", which is how a German novel got Chinese handling.
            lang = parsedBook?.language ?: "",
            addedAt = System.currentTimeMillis(),
        ),
    )
    staging.deleteRecursively()
    return 1
}
