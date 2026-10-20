package com.fpedroso.bookreader

import android.content.Context
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Translations cached per book, keyed by block hash - so a sentence that moves, or a book
 * re-imported, still hits its translation. Persisted as `translations.json` beside the book.
 */
@Serializable
data class Translations(
    val v: Int = 1,
    val target: String = "en",
    val blocks: Map<String, String> = emptyMap(),
)

/**
 * On-device paragraph translation with ML Kit.
 *
 * One call per unit, never per fragment: the unit is a sentence in prose, and a whole
 * paragraph for headings and verse. A stanza is a single block whose lines are mid-sentence
 * breaks, so translating verse line by line would feed the model fragments.
 *
 * Two things this buys that a network LLM would not: it is free and it runs offline, so
 * translation can be lazy and automatic without a cost decision per paragraph. The price is
 * that ML Kit translates rather than explains - it will not tell you why a separable verb
 * split, which was the original argument for an LLM.
 *
 * The model degrades on long input, so a paragraph over [MAX_CHARS] is split at *sentence*
 * boundaries (that is what Sentences exists for), translated in pieces and rejoined. The
 * seams are internal and never shown.
 */
object Translate {

    /**
     * English is the target because ML Kit's models are trained to and from English - a
     * non-English pair is pivoted through English internally anyway.
     */
    const val TARGET = "en"

    /** Above this, chunk at sentence boundaries. Comfortably inside the model's range. */
    private const val MAX_CHARS = 400

    private val clients = HashMap<String, Translator>()

    /**
     * ML Kit Language Identification, used only as the last resort. Created lazily: the client
     * exists for the life of the process and owns a native detector, so making one would be
     * waste on the common path where the EPUB already says what the block is.
     */
    private val languageId: LanguageIdentifier by lazy { LanguageIdentification.getClient() }

    /**
     * ML Kit's language constants are plain BCP-47 tags ("de", "zh"), so our code is passed
     * straight through and any of the 50+ languages works without an enum to keep in sync.
     */
    private fun tag(code: String): String = code.lowercase().take(2)

    private fun client(from: String, to: String): Translator = synchronized(clients) {
        clients.getOrPut("$from>$to") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(tag(from))
                    .setTargetLanguage(tag(to))
                    .build()
            )
        }
    }

    // ------------------------------------------------------------------ chunking

    /**
     * Paragraph text flattened for the model, split into model-sized pieces at sentence
     * boundaries when it is long. Pure, so it is unit-tested without a device.
     */
    fun chunks(text: String, lang: String): List<String> {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= MAX_CHARS) return listOf(flat)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (s in Sentences.split(flat, lang)) {
            if (sb.isNotEmpty() && sb.length + s.text.length + 1 > MAX_CHARS) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s.text)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.ifEmpty { listOf(flat) }
    }

    // ------------------------------------------------------------------ translate

    /**
     * Translate one paragraph. Downloading the language model is part of the call - it is a
     * no-op once present - so a first run can take a while and the caller should be showing
     * a progress state rather than blocking on a still screen.
     *
     * Call off the main thread.
     */
    suspend fun paragraph(context: Context, from: String, text: String): String {
        val parts = chunks(text, from)
        val translated = parts.map { client(from, TARGET).awaitTranslate(it) }
        return translated.joinToString(" ")
    }

    private suspend fun Translator.awaitTranslate(text: String): String {
        // downloadModelIfNeeded first: translate() fails while the model is missing, and
        // there is no way to tell "not downloaded" from "broken" otherwise.
        downloadModelIfNeeded().await()
        return translate(text).await()
    }

    // -------------------------------------------------------- language

    /**
     * Whether the offline model for this pair is still missing, so the dialog can say what the
     * wait is instead of looking hung: the first translation in a language downloads ~30 MB
     * and can take a minute and a half with nothing on screen.
     *
     * Translator exposes no download progress at all - only `downloadModelIfNeeded` - but the
     * model manager does answer "is the model here", which is the part the UI needs. False on
     * any failure: a hint we cannot stand behind is worse than the plain "Translating…".
     */
    suspend fun needsModelDownload(from: String): Boolean = runCatching {
        val manager = RemoteModelManager.getInstance()
        val sourceReady = manager.isModelDownloaded(TranslateRemoteModel.Builder(tag(from)).build()).await()
        val targetReady = manager.isModelDownloaded(TranslateRemoteModel.Builder(TARGET).build()).await()
        sourceReady != true || targetReady != true
    }.getOrDefault(false)

    /**
     * Language Identification on the text itself - the LAST resort, after the block's own
     * lang/xml:lang, the document, dc:language and the import hint have all said nothing.
     *
     * It is not a substitute for a declared language: a confident-sounding wrong answer here
     * would send prose to the wrong model silently, which is the failure this path exists to
     * prevent. Null means "no answer", and the caller reports that rather than guessing.
     *
     * Call off the main thread.
     */
    suspend fun identify(text: String): String? {
        val detected = runCatching { languageId.identifyLanguage(text).await() }.getOrNull()
        // "und" is ML Kit's own "I cannot tell" - a non-answer, not a language.
        if (detected.isNullOrBlank() || detected == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) return null
        val code = detected.lowercase().take(2)
        // Only what the Translator itself can use: an unsupported tag would come back as an
        // obscure model error instead of our own honest "could not be determined".
        return code.takeIf { TranslateLanguage.fromLanguageTag(it) != null }
    }

    /**
     * Play Services Task -> coroutine. The bridge itself lives in `Models.kt` as an internal
     * top-level extension so the model manager and this file share one implementation.
     */
}

// ------------------------------------------------------------- language naming

/**
 * True when there is nothing to translate: the source already IS the target language.
 *
 * A German novel's front matter (title page, copyright, dedication) is commonly English, and
 * asking the model to turn English into English downloads a model to hand back the same words.
 */
internal fun alreadyTarget(source: String?): Boolean =
    source != null && source.trim().lowercase().take(2) == Translate.TARGET

/**
 * Whether the reader offers to translate a block at all.
 *
 * **German books only, deliberately** - an explicit, temporary narrowing of a capability that
 * works for any of ML Kit's 50+ languages. The point is not that other languages fail; it is that
 * a word-level tap has only been judged on German prose, and the tokenizer behind it is a
 * space-and-punctuation splitter with no notion of scripts that do not use spaces. Widening this
 * is one line, once the tap has been read on more than one language.
 *
 * The block's OWN language is checked too, and `false` is returned for a block that is already
 * English: a German novel's front matter is routinely English (title page, copyright), and an
 * affordance offering to translate it would only ever answer "this is already in English".
 * A null block language is the undeclared case, which falls back to the book's - so it is offered.
 */
internal fun translationOffered(bookLang: String?, blockLang: String?): Boolean {
    if (bookLang?.trim()?.lowercase()?.take(2) != "de") return false
    return !alreadyTarget(blockLang)
}

/**
 * "German → English" for the modal, derived from the resolved source language for this
 * sentence; null when there is nothing to translate (the source is the target) or no source
 * language was resolved.
 */
internal fun languagePair(source: String?): String? {
    if (source.isNullOrBlank() || alreadyTarget(source)) return null
    return "${languageName(source)} → ${languageName(Translate.TARGET)}"
}

/**
 * The English name of an ISO code, e.g. "de" -> "German".
 *
 * From the platform's own CLDR data via [Locale] rather than a hand-kept table: any code the
 * device knows a name for works, including ones this app has no other handling for. An
 * unknown code comes back as the code itself, which is still better than a wrong name.
 */
internal fun languageName(code: String): String =
    Locale(code.trim().lowercase().take(2))
        .getDisplayLanguage(Locale.ENGLISH)
        .replaceFirstChar { it.uppercase() }
