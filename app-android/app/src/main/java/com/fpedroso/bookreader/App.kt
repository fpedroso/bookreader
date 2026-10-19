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
    var translation by remember { mutableStateOf<String?>(null) }
    var translating by remember { mutableStateOf(false) }
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
        // Off the main thread: the first call in a language also downloads its ~30 MB model.
        fun openTranslation(key: String?, source: String) {
            tapKey = key
            tapSource = source
            val cached = key?.let { translations[it] }
            translation = cached
            translateError = null
            translating = cached == null && key != null
            when {
                // No dc:language and no import hint: say so rather than guessing a source
                // language for ML Kit, which would silently mistranslate.
                lang.isBlank() -> {
                    translating = false
                    translateError = "the book's language could not be determined"
                }
                cached == null && key != null -> scope.launch {
                    val res = runCatching {
                        withContext(Dispatchers.Default) {
                            Translate.paragraph(ctx, lang, source)
                        }
                    }
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
        }

        val onSentenceTap: (RowItem.Para, Int) -> Unit = { para, i ->
            // The range is the source text: the reader renders row.text as one paragraph, so
            // the substring is the sentence the finger landed on. Trimmed because a range
            // starts at the space that follows the previous sentence.
            val range = para.sentences?.getOrNull(i)?.takeIf { it.first < para.text.length }
            openTranslation(
                key = para.hash?.let { "$it#$i" },
                source = range?.let { para.text.substring(it.first, it.last + 1).trim() } ?: para.text,
            )
        }

        ReaderScreen(
            title = current.title,
            rows = rows,
            initialIndex = current.lastBlock,
            selectedKey = tapKey,
            onParagraphTap = { para -> openTranslation(para.hash, para.text) },
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
                onDismiss = { tapKey = null },
            )
        }
    }
}

/**
 * The sentence character ranges of a prose block, or null when the block keeps the paragraph
 * as its unit.
 *
 * Null for headings, which are not translated a sentence at a time, and for any block containing
 * a newline - that is verse. `Sentences.split` normalises whitespace, so splitting a stanza would
 * turn its `<br>` line breaks back into prose and undo the verse rendering.
 *
 * The ranges index into the very [text] that is rendered, not into the splitter's normalised
 * copy. That holds because the extractor already collapses every whitespace run inside a block
 * to a single space, so for the newline-free blocks that reach here `normalise` is the identity
 * (pinned by SentenceHitTestTest).
 */
internal fun sentenceUnits(text: String, heading: Boolean, lang: String): List<IntRange>? {
    if (heading || text.contains('\n')) return null
    return Sentences.split(text, lang).map { it.start until it.end }.takeIf { it.isNotEmpty() }
}

private fun buildRows(book: EpubParser.Book, lang: String): List<RowItem> {
    val out = ArrayList<RowItem>()
    for (ch in book.chapters) {
        ch.title?.takeIf { it.isNotBlank() }?.let { out.add(RowItem.ChapterHead(it)) }
        for (bl in ch.blocks) {
            // Every paragraph carries its block hash: it is the key for the sentence
            // translation cache (`"<hash>#<index>"`) and the tap target for the modal.
            out.add(
                RowItem.Para(
                    text = bl.text,
                    heading = bl.heading,
                    hash = Annotations.key(bl.text),
                    sentences = sentenceUnits(bl.text, bl.heading, lang),
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
