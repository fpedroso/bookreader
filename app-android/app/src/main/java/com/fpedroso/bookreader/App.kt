package com.fpedroso.bookreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
    var sidecar by remember { mutableStateOf<Sidecar?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showPinyin by remember { mutableStateOf(true) }
    var lang by remember { mutableStateOf("zh") }
    var dict by remember { mutableStateOf<Dictionary?>(null) }
    var tapped by remember { mutableStateOf<Token?>(null) }
    var lastIndex by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) { importPicked(ctx, uris) }
            library = Store.load(ctx)
        }
    }

    // Parse on open, off the main thread: a novel's sidecar can be ~12 MB.
    LaunchedEffect(openBook) {
        val b = openBook ?: return@LaunchedEffect
        loading = true
        val result = withContext(Dispatchers.IO) {
            val p = runCatching { EpubParser.parse(File(b.epubFile)) }.getOrNull()
            val s = b.sidecarFile?.let { runCatching { Annotations.load(it) }.getOrNull() }
            // A sidecar only exists for Chinese, so its lang wins; otherwise trust the
            // EPUB's dc:language, and fall back to how the book was imported.
            val bookLang = s?.lang ?: p?.language ?: b.lang
            val d: Dictionary =
                if (bookLang.startsWith("de")) GermanDict(ctx) else ChineseDict(ctx)
            // Opening the dictionary copies a big asset out of the APK on first use
            // (de.db is >100 MB), which would ANR if it happened on the first tap.
            runCatching { d.warmUp() }
            Triple(p, s, bookLang to d)
        }
        parsed = result.first
        sidecar = result.second
        lang = result.third.first
        dict = result.third.second
        loading = false
    }

    val current = openBook
    if (current == null) {
        LibraryScreen(
            books = library.books,
            onImport = { picker.launch(arrayOf("application/epub+zip", "application/json", "*/*")) },
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
        val rows = remember(book, sidecar, lang) { buildRows(book, sidecar, lang) }
        ReaderScreen(
            title = current.title,
            rows = rows,
            showPinyin = showPinyin,
            pinyinToggleVisible = lang.startsWith("zh"),
            initialIndex = current.lastBlock,
            onTogglePinyin = { showPinyin = it },
            onWordTap = { tapped = it },
            onProgress = { lastIndex = it },
            onBack = {
                // Persist position once on exit, rather than on every scroll frame.
                Store.setProgress(ctx, current.id, lastIndex)
                library = Store.load(ctx)
                openBook = null; parsed = null; sidecar = null
            },
        )
        tapped?.let { t ->
            WordDialog(
                headword = t.word,
                results = remember(t.word, dict) { dict?.lookup(t.word).orEmpty() },
                note = if (t.source == "pypinyin") {
                    "⚠ reading from the pypinyin fallback — may miss the neutral tone"
                } else {
                    null
                },
                onDismiss = { tapped = null },
            )
        }
    }
}

private fun buildRows(book: EpubParser.Book, sidecar: Sidecar?, lang: String): List<RowItem> {
    val out = ArrayList<RowItem>()
    for (ch in book.chapters) {
        ch.title?.takeIf { it.isNotBlank() }?.let { out.add(RowItem.ChapterHead(it)) }
        for (bl in ch.blocks) {
            // Chinese tokens come from the PC-generated sidecar (segmentation plus
            // readings). German has no sidecar and needs none: the language is
            // space-separated, so tokenising here is enough and the dictionary
            // resolves whatever inflection the token happens to be.
            val tokens = Annotations.tokens(sidecar, bl.text)
                ?: if (lang.startsWith("de")) GermanTokenizer.tokenize(bl.text) else null
            out.add(RowItem.Para(text = bl.text, tokens = tokens, heading = bl.heading))
        }
    }
    return out
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

/**
 * The picker accepts several files at once, so selecting the EPUB together with
 * its .pinyin.json sidecar in one gesture pairs them. Both come from the PC tool.
 */
private fun importPicked(context: Context, uris: List<Uri>): Int {
    val staging = File(context.filesDir, "staging")
    staging.deleteRecursively()
    staging.mkdirs()

    val byBase = HashMap<String, MutableList<Pair<String, File>>>()
    uris.forEachIndexed { i, uri ->
        val name = displayName(context, uri) ?: "file_$i"
        val ext = when {
            name.endsWith(".epub", true) -> "epub"
            name.endsWith(".json", true) -> "json"
            else -> return@forEachIndexed
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(staging, "s${i}_$safe")
        runCatching { copyIn(context, uri, dest) }.getOrNull()
        if (dest.exists()) {
            byBase.getOrPut(name.substringBeforeLast('.')) { mutableListOf() }.add(ext to dest)
        }
    }

    var imported = 0
    for ((base, files) in byBase) {
        val epub = files.firstOrNull { it.first == "epub" }?.second ?: continue
        val jsonFile = files.firstOrNull { it.first == "json" }?.second

        val parsedBook = runCatching { EpubParser.parse(epub) }.getOrNull()
        val title = parsedBook?.title?.takeIf { it.isNotBlank() } ?: base
        val id = Store.idFor(title + base)

        val dir = File(Store.booksDir(context), id)
        dir.mkdirs()
        val epubDest = File(dir, "book.epub")
        epub.copyTo(epubDest, overwrite = true)
        val sidecarDest = jsonFile?.let { src ->
            File(dir, "pinyin.json").also { d -> src.copyTo(d, overwrite = true) }
        }

        Store.add(
            context,
            BookMeta(
                id = id,
                title = title,
                epubFile = epubDest.absolutePath,
                sidecarFile = sidecarDest?.absolutePath,
                // A sidecar only ever accompanies a Chinese book.
                lang = if (sidecarDest != null) "zh" else (parsedBook?.language ?: "zh"),
                addedAt = System.currentTimeMillis(),
            ),
        )
        imported++
    }
    staging.deleteRecursively()
    return imported
}
