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
    // The engine actually used. Resolved rather than derived from the stored id: a local model has to
    // be both runnable and ON THE DEVICE, and either can change while the app runs, so this is
    // recomputed whenever Settings closes.
    val selectedEngine = Engine.fromId(settings.engine)
    var engine by remember { mutableStateOf(Engine.MLKIT) }
    LaunchedEffect(settings, showSettings) {
        if (!showSettings) {
            val next = Models.resolve(ctx, selectedEngine, settings)
            if (next != engine) {
                // Do not keep a 2.7 GB model resident after the reader has moved away from it.
                withContext(Dispatchers.IO) { Qwen.unload() }
                engine = next
            }
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
    /** A local model is being read off disk on this tap - seconds, first tap after a cold start. */
    var loadingModel by remember { mutableStateOf(false) }
    var alreadyEnglish by remember { mutableStateOf(false) }
    var translateError by remember { mutableStateOf<String?>(null) }
    var translations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lastIndex by remember { mutableStateOf(0) }
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
            Store.setProgress(ctx, b.id, lastIndex)
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
                Store.setProgress(ctx, b.id, lastIndex)
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
        val rows = remember(book, lang) { buildRows(book, lang) }

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
            loadingModel = false
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
                loadingModel = !engine.cloud && engine != Engine.MLKIT && !Qwen.isLoaded()
                val res = runCatching {
                    withContext(Dispatchers.Default) {
                        when (engine) {
                            Engine.MLKIT -> Translate.paragraph(ctx, src, source)
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
                            else -> Qwen.translate(ctx, engine, source)
                        }
                    }
                }
                loadingModel = false
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

        // A WORD: the smallest unit, translated on its own. It is still ML Kit's call, exactly as a
        // sentence is - there is no dictionary in this app any more - so what comes back is one
        // rendering of the word with no lemma, part of speech or alternative senses. The sentence
        // the word sits in is carried along as context, both to show under it and because it is
        // where "Translate sentence" goes.
        val onWordTap: (RowItem, Int, Words.Word) -> Unit = { para, i, word ->
            // For a heading or a stanza there are no sentence units, so i is -1 and the word's
            // context is the block itself: the chip at the end of that block is the block action.
            val sentence = para.sentences?.getOrNull(i)
            tapRow = para
            tapStart = i.coerceAtLeast(0)
            tapCount = 1
            tapIsWord = true
            tapContext = sentence
            openTranslation(
                // The engine is part of the key, and for an engine that reads context so is the
                // sentence: the same word in two sentences is two different translations.
                key = unitKey(engine, word.text, sentence),
                source = word.text,
                blockLang = para.lang,
            )
        }

        val onSentenceTap: (RowItem, Int) -> Unit = { para, i ->
            // The chip the finger landed on belongs to the sentence it ends, so index i names it
            // directly. The span starts as that one sentence and can be grown by "Translate more".
            val source = para.sentences?.getOrNull(i) ?: para.text
            tapRow = para
            tapStart = i
            tapCount = 1
            tapIsWord = false
            tapContext = null
            openTranslation(
                key = unitKey(engine, source),
                source = source,
                blockLang = para.lang,
            )
        }

        ReaderScreen(
            title = current.title,
            rows = rows,
            initialIndex = current.lastBlock,
            selectedKey = tapKey,
            onParagraphTap = { para ->
                // No sentence list behind a paragraph or heading, so nothing to extend.
                tapRow = null
                tapIsWord = false
                tapContext = null
                openTranslation(unitKey(engine, para.text), para.text, para.lang)
            },
            onSentenceTap = onSentenceTap,
            onWordTap = onWordTap,
            onProgress = { lastIndex = it },
            onBack = closeBook,
        )
        tapKey?.let {
            val word = tapIsWord
            // The span one sentence longer, or null at the paragraph's end - the modal hides
            // "Translate more" rather than offering an action that would do nothing. A WORD shows
            // no "Translate more" at all: it hands over to its own sentence instead, which is the
            // unit a reader actually wants next.
            val moreSpan =
                if (word) null
                else tapRow?.sentences?.let { nextSpan(it, UnitSpan(tapStart, tapCount)) }
            TranslationDialog(
                source = tapSource,
                translation = translation,
                busy = translating,
                error = translateError,
                // Three units, one dialog: a word, a sentence, or a whole passage (a heading or a
                // stanza, which never had sentences to grow into).
                unit = when {
                    word -> "word"
                    tapRow == null -> "passage"
                    else -> "sentence"
                },
                context = tapContext,
                pair = languagePair(tapLang),
                // Provenance: which engine answered, and which model when that is not fixed. If the stored
                // choice could not be used, say what was used instead and why.
                engineLabel = engineProvenance(engine, selectedEngine, settings),
                alreadyEnglish = alreadyEnglish,
                downloading = downloading,
                loadingModel = loadingModel,
                onTranslateMore = moreSpan?.let { span ->
                    {
                        val sentences = tapRow?.sentences
                        if (sentences != null) {
                            // Translate the JOINED text as one unit, under its OWN key.
                            // unitKey(joined) hashes different text than the fragment,
                            // so it is a different cache key: openTranslation cannot serve the
                            // fragment's translation here, and storing the joined result leaves
                            // the fragment's entry untouched for a later tap. Re-pressing to
                            // this same span hashes the same text, so it hits the cache.
                            val joined = unitSource(sentences, span)
                            tapStart = span.start
                            tapCount = span.count
                            openTranslation(unitKey(engine, joined), joined, tapRow?.lang)
                        }
                    }
                },
                // Only a word offers this, and only when it sits in a sentence: the way from the
                // smallest unit back up to the sentence. It translates under the SENTENCE's own key,
                // so the result is shared with the chip at the end of that same sentence.
                onTranslateSentence = if (word && tapContext != null) {
                    {
                        val sentence = tapContext
                        if (sentence != null) {
                            tapIsWord = false
                            tapContext = null
                            openTranslation(unitKey(engine, sentence), sentence, tapRow?.lang)
                        }
                    }
                } else null,
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
 * The sentence units of a prose block, or null when the block keeps the paragraph as its unit.
 *
 * Null for headings, which are not translated a sentence at a time, and for any block containing
 * a newline - that is verse. `Sentences.split` normalises whitespace, so splitting a stanza would
 * turn its `<br>` line breaks back into prose and undo the verse rendering.
 *
 * The sentences' own text is returned, not offsets: each one is rendered on its own line with the
 * sentence action at its end, so the unit is exact by construction and nothing needs resolving.
 */
internal fun sentenceUnits(text: String, heading: Boolean, lang: String): List<String>? {
    if (heading || text.contains('\n')) return null
    return Sentences.split(text, lang).map { it.text }.takeIf { it.isNotEmpty() }
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
    val why = when {
        !selected.runnable -> "not runnable in this build"
        selected.cloud -> "no API key saved"
        else -> "no model on this device"
    }
    return "$label (${selected.label}: $why)"
}

private fun buildRows(book: EpubParser.Book, lang: String): List<RowItem> {
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
                    sentences = sentenceUnits(bl.text, bl.heading, lang),
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
