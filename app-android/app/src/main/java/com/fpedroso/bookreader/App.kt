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
    var parsed by remember { mutableStateOf<EpubParser.Book?>(null) }
    var loading by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("") }
    // Sentence translation. Tapping a sentence opens a modal with its English translation,
    // produced on-device by ML Kit.
    var tapKey by remember { mutableStateOf<String?>(null) }
    var tapSource by remember { mutableStateOf("") }
    var tapLang by remember { mutableStateOf<String?>(null) }
    var translation by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
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
    // Activity. It also closes the translation modal first, since that sits on top.
    BackHandler(enabled = openBook != null || tapKey != null) {
        if (tapKey != null) tapKey = null else closeBook()
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
    if (current == null) {
        LibraryScreen(
            books = library.books,
            onImport = { picker.launch(arrayOf("application/epub+zip", "*/*")) },
            onOpen = { openBook = it },
            onDelete = {
                Store.remove(ctx, it.id)
                library = Store.load(ctx)
            },
        )
    } else if (loading || parsed == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
    } else {
        val book = parsed!!
        val rows = remember(book, lang) { buildRows(book, lang) }

        // Tap a unit -> its translation, cached by key so re-tapping is free. The unit is
        // one sentence, keyed "<hash>#<index>", or a whole paragraph for headings and verse.
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
                downloading = withContext(Dispatchers.Default) { Translate.needsModelDownload(src) }
                val res = runCatching {
                    withContext(Dispatchers.Default) {
                        Translate.paragraph(ctx, src, source)
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

        val onSentenceTap: (RowItem, Int) -> Unit = { para, i ->
            // The composable the finger landed on IS the sentence, so there is nothing to
            // resolve: index i names it directly.
            openTranslation(
                key = para.hash?.let { "$it#$i" },
                source = para.sentences?.getOrNull(i) ?: para.text,
                blockLang = para.lang,
            )
        }

        ReaderScreen(
            title = current.title,
            rows = rows,
            initialIndex = current.lastBlock,
            selectedKey = tapKey,
            onParagraphTap = { para -> openTranslation(para.hash, para.text, para.lang) },
            onSentenceTap = onSentenceTap,
            onProgress = { lastIndex = it },
            onBack = closeBook,
        )
        tapKey?.let {
            TranslationDialog(
                source = tapSource,
                translation = translation,
                busy = translating,
                error = translateError,
                pair = languagePair(tapLang),
                alreadyEnglish = alreadyEnglish,
                downloading = downloading,
                onDismiss = { tapKey = null },
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
 * The sentences' own text is returned, not offsets: each one is rendered on its own line and is
 * its own tap target, so the unit is exact by construction.
 */
internal fun sentenceUnits(text: String, heading: Boolean, lang: String): List<String>? {
    if (heading || text.contains('\n')) return null
    return Sentences.split(text, lang).map { it.text }.takeIf { it.isNotEmpty() }
}

private fun buildRows(book: EpubParser.Book, lang: String): List<RowItem> {
    val out = ArrayList<RowItem>()
    // No chapter-title row: the parser has no title to give one (see EpubParser.Chapter), and
    // the reader does not need it - a chapter announces itself with the <h1>-<h6> headings the
    // body already contains, which arrive here as heading blocks.
    for (ch in book.chapters) {
        for (bl in ch.blocks) {
            // Every paragraph carries its block hash: it is the key for the sentence
            // translation cache (`"<hash>#<index>"`) and the tap target for the modal.
            out.add(
                RowItem(
                    text = bl.text,
                    heading = bl.heading,
                    hash = Annotations.key(bl.text),
                    sentences = sentenceUnits(bl.text, bl.heading, lang),
                    // The block's own resolved language, so the tap translates with the
                    // language that block is actually written in.
                    lang = bl.lang,
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
