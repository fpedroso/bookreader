package com.fpedroso.bookreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    private const val MAX_OUTPUT_TOKENS = 400
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000

    /** The endpoint OpenRouter's OpenAI-compatible API lives at. */
    const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /**
     * A cheap, non-reasoning instruct model as the default.
     *
     * Deliberately NOT a hybrid reasoning model: those can spend the whole output budget in a
     * reasoning channel and return an empty `content`, which for a translation is a blank answer. A
     * reader who wants a different model can type any id from OpenRouter's catalogue.
     */
    const val DEFAULT_MODEL = "mistralai/mistral-small-24b-instruct-2501"

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
            connection.outputStream.use { it.write(requestBody(model, text, context).toByteArray()) }
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
    internal fun requestBody(model: String, text: String, context: String? = null): String =
        JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "temperature" to JsonPrimitive(0),
                "max_tokens" to JsonPrimitive(MAX_OUTPUT_TOKENS),
                "messages" to JsonArray(
                    listOf(
                        message("system", SYSTEM),
                        message("user", userText(text, context)),
                    )
                ),
            )
        ).toString()

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
        val text = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?.trim()
        if (text.isNullOrBlank()) {
            throw IOException(
                "the model returned no text (a reasoning model can do this - try another model id)"
            )
        }
        return text
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