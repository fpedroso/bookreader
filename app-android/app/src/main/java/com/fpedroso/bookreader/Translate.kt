package com.fpedroso.bookreader

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Translations cached per book, keyed by block hash exactly like the pinyin sidecar - so a
 * paragraph that moves, or a book re-imported, still hits its translation. Persisted as
 * `translations.json` beside the book.
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
 * Paragraph, not sentence: EPUB blocks already *are* paragraphs, so a translation maps 1:1
 * onto the hash-keyed block store the pinyin work established. One call per paragraph
 * instead of N, more context for the model, and for verse it matters a lot - a stanza is a
 * single block whose lines are mid-sentence breaks, so translating lines individually would
 * feed the model fragments.
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

    /**
     * Play Services Task -> coroutine. Written here rather than adding
     * kotlinx-coroutines-play-services, whose version would then need to stay aligned with
     * the coroutines version Compose already pulls in.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }
}
