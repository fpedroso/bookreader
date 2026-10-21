package com.fpedroso.bookreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Translation over an OpenAI-compatible HTTP endpoint - OpenRouter by default.
 *
 * This is the one engine where the text LEAVES the device, which is a real change to what this app
 * promises, so the engine's own note in Settings says so and the modal names the engine that
 * answered. Everything else in the app is on-device.
 *
 * The shape is deliberately the plainest OpenAI-compatible chat completion: one request, no
 * streaming, no dependency (HttpURLConnection is in the platform), because that same shape works
 * against OpenRouter, OpenAI, DeepSeek, a local llama.cpp server, or anything else that speaks the
 * same API. That is also why the endpoint is a setting rather than a constant.
 *
 * The request body, the response parse and the error text are pure functions, so the parts that can
 * be wrong without a network are unit-tested; only [translate] itself touches the socket.
 */
object OpenRouter {

    /**
     * Same instruction the probe and the local engine use, so quality stays comparable.
     *
     * The second sentence covers the word tap: the app sends the tapped word together with the sentence
     * it sits in, because a bare word is ambiguous (`Bank` is a bench in one of these books, not a bank).
     * A prompt that only mentions sentences would be lying about half the calls it receives.
     */
    const val SYSTEM =
        "You are a translation engine. Translate the German text the user sends into natural " +
            "English. Output only the translation, with no notes and no explanations. When the user " +
            "gives a single word together with the sentence it appears in, translate that word as it is " +
            "used in that sentence."

    /**
     * The instruction for a word tap, which is a different job from translating a sentence.
     *
     * A separate instruction because the unit is different, and saying so is what makes it work. The
     * single instruction above describes *what* to translate but not *which unit*, and models resolved
     * that ambiguity badly: measured on 49 real sentences, hy-mt2-1.8b echoed the input label in 49 of
     * 49 cases, deepseek echoed 4, and mistral answered with a word that ignored the sentence context.
     * With this instruction naming the unit and the reply format, the general models answered with
     * exactly the word, every time (deepseek 49/49, zero echoes). Both strings are the ones the probe
     * tested, so what ships is what was measured.
     */
    const val WORD_SYSTEM =
        "You translate German into English. The user gives one German word and the sentence it " +
            "appears in. Reply with only the English translation of that word, as it is used in that " +
            "sentence - one word or a short phrase, with no notes, no quotes and nothing else."

    /** Whether a tap is a word tap: a word with the sentence it came from attached. */
    internal fun isWordTap(text: String, context: String?): Boolean =
        !context.isNullOrBlank() && context != text

    /** The instruction matching the unit being translated. */
    internal fun systemFor(text: String, context: String?): String =
        if (isWordTap(text, context)) WORD_SYSTEM else SYSTEM

    private const val MAX_OUTPUT_TOKENS = 400
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000

    /** The endpoint OpenRouter's OpenAI-compatible API lives at. */
    const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /**
     * Whether a request is going to OpenRouter itself.
     *
     * Only OpenRouter gets the `reasoning` field below: OpenRouter documents that a parameter a model
     * does not support is ignored, but a plain OpenAI-compatible server (a llama.cpp on your own
     * machine, say) is free to reject an unknown field outright, and breaking those endpoints to fix
     * this one would be a bad trade.
     */
    internal fun isOpenRouter(endpoint: String): Boolean =
        runCatching { URL(endpoint).host }.getOrNull()?.lowercase()?.let {
            it == "openrouter.ai" || it.endsWith(".openrouter.ai")
        } == true

    /**
     * The default model: cheap, non-reasoning, and the best of the four tested on this reader's own
     * sentences.
     *
     * Chosen on evidence, not on a hunch. 49 real German sentences from the two fixtures were sent to
     * four models in exactly the shape this app sends, and two blind judges from other families
     * (gemini-2.5-flash-lite over 49 sentences, claude-haiku-5.5 over 44) independently produced the
     * same ordering: this model first, then hy-mt2-7b, then mistral-small-24b, then hy-mt2-1.8b - 21
     * wins to 8 against the previous default. It costs about four cents a book and about a second a
     * sentence. The earlier choice was a non-reasoning model for the reason below, which still holds:
     * a hybrid reasoning model can spend the whole output budget thinking and return an empty
     * `content`, which for a translation is a blank answer.
     */
    const val DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash"
    suspend fun translate(
        endpoint: String,
        apiKey: String,
        model: String,
        text: String,
        context: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IOException("no API key - add one in Settings")
        val url = runCatching { URL(endpoint) }.getOrElse {
            throw IOException("the endpoint is not a valid URL")
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            // OpenRouter's attribution headers: optional, and it asks for them.
            setRequestProperty("X-Title", "Book Reader")
        }

        // The status is read INSIDE the request: after disconnect() it is no longer reliable.
        var status = 0
        val responseText = try {
            connection.outputStream.use { it.write(requestBody(model, text, context, endpoint).toByteArray()) }
            status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (e: IOException) {
            throw IOException("could not reach $endpoint (${e.message})")
        } finally {
            connection.disconnect()
        }

        if (status !in 200..299) throw IOException(errorFor(status, responseText))
        content(responseText)
    }

    /** The request body. Pure, so the shape can be tested without a socket. */
    internal fun requestBody(
        model: String,
        text: String,
        context: String? = null,
        endpoint: String = DEFAULT_ENDPOINT,
    ): String {
        val fields = linkedMapOf<String, JsonElement>(
            "model" to JsonPrimitive(model),
            "temperature" to JsonPrimitive(0),
            "max_tokens" to JsonPrimitive(MAX_OUTPUT_TOKENS),
            "messages" to JsonArray(
                listOf(
                    message("system", systemFor(text, context)),
                    message("user", userText(text, context)),
                )
            ),
        )
        // Reasoning off - OpenRouter only, and only because of what its own models now default to:
        // most of the strong ones reason by default (DeepSeek's whole line-up does, at "high" effort)
        // and reasoning tokens are billed against max_tokens, so at a translation-sized budget the
        // model can spend the lot thinking and return an empty answer. Translating one sentence is
        // not a task that benefits from it, and the empty answer is indistinguishable from a failure.
        if (isOpenRouter(endpoint)) {
            fields["reasoning"] = JsonObject(mapOf("enabled" to JsonPrimitive(false)))
        }
        return JsonObject(fields).toString()
    }

    /**
     * What the model is asked to translate.
     *
     * A word tap sends the word AND the sentence it was tapped in, labelled so the two cannot be
     * confused; a sentence tap sends the sentence alone. The label is plain text rather than JSON
     * structure because every OpenAI-compatible endpoint accepts a string content and models read
     * this form reliably.
     */
    private fun userText(text: String, context: String?): String = when {
        context.isNullOrBlank() || context == text -> text
        else -> "Word: $text\nIn the sentence: $context"
    }

    private fun message(role: String, content: String) = JsonObject(
        mapOf(
            "role" to JsonPrimitive(role),
            "content" to JsonPrimitive(content),
        )
    )

    /**
     * The translation out of a chat-completion response, or a thrown [IOException].
     *
     * An empty or absent `content` is the failure this app has already been bitten by once: it must
     * read as a failed call with a reason, never as a translation with no words in it.
     */
    internal fun content(responseJson: String): String {
        val root = runCatching { Json.parseToJsonElement(responseJson).jsonObject }.getOrNull()
            ?: throw IOException("the endpoint returned something that is not JSON")
        // OpenRouter reports some failures inside a 200 body.
        root["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            throw IOException(message ?: "the endpoint reported an error")
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.trim()
        if (text.isNullOrBlank()) throw IOException(emptyReason(choice, root))
        return text
    }

    /**
     * Why an empty answer came back, using the signals OpenRouter documents rather than a shrug.
     *
     * The documented case: reasoning tokens are billed against `max_tokens`, so a reasoning model can
     * spend the entire budget thinking and return `finish_reason: "length"` with nothing in `content`.
     * Telling the reader which of those it was is the difference between "try another model" and "this
     * engine is broken".
     */
    private fun emptyReason(choice: JsonObject?, root: JsonObject): String {
        val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        val usage = root["usage"]?.jsonObject
        val reasoning = usage?.get("completion_tokens_details")?.jsonObject
            ?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val total = usage?.get("completion_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        return when {
            reasoning != null && reasoning > 0 ->
                "the model returned no text: it spent $reasoning of ${total ?: "?"} output tokens " +
                    "thinking, and a translation has no room for that. Try another model id."
            finish == "length" ->
                "the model returned no text: it hit the output limit before writing anything."
            finish == "content_filter" ->
                "the model refused to translate that text (content filter)."
            else ->
                "the model returned no text (a reasoning model can do this - try another model id)"
        }
    }

    /** A readable reason for an HTTP status. Pure, and the messages are the user-facing ones. */
    internal fun errorFor(status: Int, body: String): String {
        val detail = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val base = when (status) {
            401, 403 -> "the API key was rejected ($status)"
            402 -> "the account has no credit for this model (402)"
            404 -> "no such model or endpoint (404)"
            429 -> "rate limited or out of quota (429) - try again shortly"
            in 500..599 -> "the provider had a server error ($status)"
            0 -> "no response from the endpoint"
            else -> "the request failed (HTTP $status)"
        }
        return if (detail.isNullOrBlank()) base else "$base: $detail"
    }
}