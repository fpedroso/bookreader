package com.fpedroso.bookreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud engine's parts that do not need a socket: the request shape, the response parse, the
 * error text, and what the modal says about which engine answered.
 *
 * The request is asserted by parsing it back rather than by string comparison, because the point is
 * that a real endpoint reads the fields - not that the JSON is formatted a particular way.
 */
class OpenRouterTest {

    @Test
    fun `request carries model, greedy temperature, and both messages`() {
        val body = OpenRouter.requestBody("mistralai/mistral-small-24b-instruct-2501", "Der Hund bellt.")
        val root = Json.parseToJsonElement(body).jsonObject

        assertEquals("mistralai/mistral-small-24b-instruct-2501", root["model"]!!.jsonPrimitive.content)
        assertEquals("0", root["temperature"]!!.jsonPrimitive.content)

        val messages = root["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Der Hund bellt.", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(
            "the instruction should ask for the translation alone",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("only the translation"),
        )
    }

    @Test
    fun `umlauts, quotes and newlines survive the request body`() {
        // Real German prose contains all three, and a hand-rolled JSON string would break on them.
        val text = "Er sagte: \"Grüße aus München!\"\nDann ging er."
        val body = OpenRouter.requestBody("m", text)
        val parsed = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(text, parsed)
    }

    @Test
    fun `a word tap sends the word together with its sentence`() {
        val sentence = "Einem Kind, dem man eine Bank zeichnet, hat es gar keinen Begriff."
        val body = OpenRouter.requestBody("m", "Bank", sentence)
        val user = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(user, user.contains("Bank"))
        assertTrue(user, user.contains(sentence))
        // Labelled, so the model cannot read the sentence as the thing to translate.
        assertTrue(user, user.startsWith("Word: Bank"))
    }

    @Test
    fun `a sentence tap sends the sentence alone, unlabelled`() {
        val sentence = "Der Hund bellt."
        val body = OpenRouter.requestBody("m", sentence, null)
        val user = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(sentence, user)
    }

    @Test
    fun `context identical to the text is not repeated`() {
        // The word path always has a sentence; the sentence path never should, and a context equal
        // to the text would otherwise be sent twice.
        val body = OpenRouter.requestBody("m", "Der Hund bellt.", "Der Hund bellt.")
        val user = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[1]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("Der Hund bellt.", user)
    }

    @Test
    fun `content is read out of a normal completion`() {
        val response = """
            {"id":"x","choices":[{"message":{"role":"assistant","content":"  The dog barks.  "}}]}
        """.trimIndent()
        assertEquals("The dog barks.", OpenRouter.content(response))
    }

    @Test
    fun `an empty completion is a failure, not a blank translation`() {
        // Reasoning models do this, and it must never reach the reader as a translation with no words.
        val response = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""
        val e = runCatching { OpenRouter.content(response) }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        assertTrue(
            "the reason should point at the model, not just say failure",
            e!!.message!!.contains("reasoning model"),
        )
    }

    @Test
    fun `an error reported inside a 200 body is still an error`() {
        val response = """{"error":{"code":502,"message":"provider is down"}}"""
        val e = runCatching { OpenRouter.content(response) }.exceptionOrNull()
        assertEquals("provider is down", e!!.message)
    }

    @Test
    fun `a non-JSON body fails with a readable reason`() {
        val e = runCatching { OpenRouter.content("<html>502 Bad Gateway</html>") }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        assertTrue(e!!.message!!.contains("not JSON"))
    }

    @Test
    fun `http statuses map to the reasons a reader can act on`() {
        assertTrue(OpenRouter.errorFor(401, "").contains("API key was rejected"))
        assertTrue(OpenRouter.errorFor(403, "").contains("API key was rejected"))
        assertTrue(OpenRouter.errorFor(402, "").contains("no credit"))
        assertTrue(OpenRouter.errorFor(404, "").contains("no such model"))
        assertTrue(OpenRouter.errorFor(429, "").contains("rate limited"))
        assertTrue(OpenRouter.errorFor(503, "").contains("server error"))
        assertTrue(OpenRouter.errorFor(0, "").contains("no response"))
        assertTrue(OpenRouter.errorFor(418, "").contains("HTTP 418"))
    }

    @Test
    fun `the provider's own message is kept alongside the status`() {
        val body = """{"error":{"message":"This model is not available in your region"}}"""
        val text = OpenRouter.errorFor(403, body)
        assertTrue(text.contains("403"))
        assertTrue(text.contains("not available in your region"))
    }

    @Test
    fun `an unusable error body does not break the message`() {
        val text = OpenRouter.errorFor(500, "<html>")
        assertTrue(text.contains("server error"))
    }

    @Test
    fun `the modal names the cloud model, because that id is not fixed`() {
        val settings = AppSettings(apiModel = "mistralai/mistral-small-24b-instruct-2501")
        val label = engineProvenance(Engine.OPENROUTER, Engine.OPENROUTER, settings)
        assertTrue(label, label.contains("OpenRouter"))
        assertTrue(label, label.contains("mistralai/mistral-small-24b-instruct-2501"))
    }

    @Test
    fun `an on-device engine's label is just its name`() {
        val settings = AppSettings(apiModel = "some/model")
        assertEquals("ML Kit", engineProvenance(Engine.MLKIT, Engine.MLKIT, settings))
    }

    @Test
    fun `falling back to ML Kit says which engine was wanted and why it was not used`() {
        val settings = AppSettings(engine = Engine.OPENROUTER.id, apiKey = "")
        val label = engineProvenance(Engine.MLKIT, Engine.OPENROUTER, settings)
        assertTrue(label, label.contains("no API key saved"))
    }

    @Test
    fun `the cloud engine is the only one that leaves the device`() {
        assertTrue(Engine.OPENROUTER.cloud)
        assertTrue(!Engine.MLKIT.cloud)
    }

    @Test
    fun `the default model is the one the blind judging put first`() {
        assertEquals("deepseek/deepseek-v4.1-flash", OpenRouter.DEFAULT_MODEL)
    }

    @Test
    fun `a word tap is told which unit to translate`() {
        // The single instruction left the unit ambiguous and models answered with the whole sentence,
        // a bare word with no context, or an echo of the label.
        val sentence = "Einem Kind, dem man eine Bank zeichnet, hat es gar keinen Begriff."
        val body = OpenRouter.requestBody("m", "Bank", sentence)
        val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        val system = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        val user = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(OpenRouter.WORD_SYSTEM, system)
        assertTrue(system, system.contains("only the English translation of that word"))
        assertTrue(user, user.startsWith("Word: Bank"))
    }

    @Test
    fun `a sentence tap gets the sentence instruction`() {
        val body = OpenRouter.requestBody("m", "Der Hund bellt.")
        val system = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(OpenRouter.SYSTEM, system)
    }

    @Test
    fun `word or sentence is decided by whether a distinct context came along`() {
        assertTrue(OpenRouter.isWordTap("Bank", "Einem Kind, dem man eine Bank zeichnet."))
        assertTrue(!OpenRouter.isWordTap("Der Hund bellt.", null))
        assertTrue(!OpenRouter.isWordTap("Der Hund bellt.", "Der Hund bellt."))
        assertTrue(!OpenRouter.isWordTap("Der Hund bellt.", "  "))
    }

    @Test
    fun `each engine gets its own cache namespace, the cloud one included`() {
        val keys = Engine.entries.map { unitKey(it, "Der Hund bellt.", null) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the cloud model is part of the cache key`() {
        val sentence = "Einem Kind, dem man eine Bank zeichnet, ..."
        val a = unitKey(Engine.OPENROUTER, "Bank", sentence, "vendor/model-a")
        val b = unitKey(Engine.OPENROUTER, "Bank", sentence, "vendor/model-b")
        // Otherwise retyping the model id keeps serving the old model's answers while the modal
        // names the new one - the reader would blame the model that never ran.
        assertNotEquals(a, b)
        assertEquals(a, unitKey(Engine.OPENROUTER, "Bank", sentence, "vendor/model-a"))
    }

    @Test
    fun `ML Kit's key ignores the model, because it does not have one`() {
        // The model setting is passed at every call site for convenience; it must not fork ML Kit's
        // cache, since its model is pinned to the engine.
        assertEquals(
            unitKey(Engine.MLKIT, "Bank", null),
            unitKey(Engine.MLKIT, "Bank", null, "whatever/model"),
        )
    }

    @Test
    fun `reasoning is switched off for OpenRouter`() {
        // Most of the strong models there reason by default, at high effort, and reasoning tokens are
        // billed against max_tokens - at a translation-sized budget the answer can come back empty.
        val body = OpenRouter.requestBody("deepseek/deepseek-v4.1-flash", "Der Hund bellt.")
        val reasoning = Json.parseToJsonElement(body).jsonObject["reasoning"]!!.jsonObject
        assertEquals("false", reasoning["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning is NOT sent to a non-OpenRouter endpoint`() {
        // OpenRouter ignores parameters a model does not support; a llama.cpp server on your own
        // machine is free to reject an unknown field, and it must keep working.
        val body = OpenRouter.requestBody(
            model = "local-model",
            text = "Der Hund bellt.",
            endpoint = "http://10.0.2.2:8731/v1/chat/completions",
        )
        assertTrue("reasoning should be absent", Json.parseToJsonElement(body).jsonObject["reasoning"] == null)
    }

    @Test
    fun `only openrouter hosts count as OpenRouter`() {
        assertTrue(OpenRouter.isOpenRouter("https://openrouter.ai/api/v1/chat/completions"))
        assertTrue(OpenRouter.isOpenRouter("https://eu.openrouter.ai/api/v1/chat/completions"))
        assertTrue(!OpenRouter.isOpenRouter("http://10.0.2.2:8731/v1/chat/completions"))
        assertTrue(!OpenRouter.isOpenRouter("https://openrouter.ai.evil.example/v1"))
        assertTrue(!OpenRouter.isOpenRouter("not a url"))
    }

    @Test
    fun `an empty answer reports that reasoning ate the budget`() {
        val response = """
            {"choices":[{"finish_reason":"length","message":{"content":""}}],
             "usage":{"completion_tokens":302,"completion_tokens_details":{"reasoning_tokens":301}}}
        """.trimIndent()
        val e = runCatching { OpenRouter.content(response) }.exceptionOrNull()
        assertTrue(e!!.message!!, e.message!!.contains("301"))
        assertTrue(e.message!!, e.message!!.contains("302"))
    }

    @Test
    fun `an empty answer with no usage still explains itself`() {
        val e = runCatching {
            OpenRouter.content("""{"choices":[{"message":{"content":""}}]}""")
        }.exceptionOrNull()
        assertTrue(e is java.io.IOException)
        assertTrue(e!!.message!!.contains("reasoning model"))
    }

    @Test
    fun `a truncated or filtered answer says which it was`() {
        val truncated = runCatching {
            OpenRouter.content("""{"choices":[{"finish_reason":"length","message":{"content":""}}]}""")
        }.exceptionOrNull()
        assertTrue(truncated!!.message!!.contains("output limit"))

        val filtered = runCatching {
            OpenRouter.content("""{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}""")
        }.exceptionOrNull()
        assertTrue(filtered!!.message!!.contains("content filter"))
    }
}