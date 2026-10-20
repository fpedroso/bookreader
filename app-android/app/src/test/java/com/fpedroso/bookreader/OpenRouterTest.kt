package com.fpedroso.bookreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
    fun `a local engine's label is just its name`() {
        val settings = AppSettings(apiModel = "some/model")
        assertEquals("Qwen3 1.7B", engineProvenance(Engine.QWEN_1_7B, Engine.QWEN_1_7B, settings))
    }

    @Test
    fun `falling back to ML Kit says which engine was wanted and why it was not used`() {
        val settings = AppSettings(engine = Engine.OPENROUTER.id, apiKey = "")
        val label = engineProvenance(Engine.MLKIT, Engine.OPENROUTER, settings)
        assertTrue(label, label.contains("no API key saved"))

        val noModel = engineProvenance(Engine.MLKIT, Engine.QWEN_4B, AppSettings())
        assertTrue(noModel, noModel.contains("no model on this device"))
    }

    @Test
    fun `the cloud engine is the only one that leaves the device`() {
        assertTrue(Engine.OPENROUTER.cloud)
        assertTrue(!Engine.MLKIT.cloud && !Engine.QWEN_1_7B.cloud && !Engine.QWEN_4B.cloud)
    }

    @Test
    fun `each engine gets its own cache namespace, the cloud one included`() {
        val keys = Engine.entries.map { unitKey(it, "Der Hund bellt.", null) }
        assertEquals(keys.size, keys.toSet().size)
    }
}