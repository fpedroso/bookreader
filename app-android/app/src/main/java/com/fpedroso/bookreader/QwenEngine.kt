package com.fpedroso.bookreader

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
// Aliased on purpose: this app has its own `Engine` enum (the translation engines in Settings), and
// both are in scope here. Unaliased, the runtime type would shadow the app's own.
import com.google.ai.edge.litertlm.Engine as LiteRtEngine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The Qwen3 engines, on-device, through LiteRT-LM.
 *
 * Two things learned the hard way, both encoded here:
 *
 * 1. **Qwen3 is a hybrid reasoning model.** Left alone it spends its whole output on the reasoning
 *    channel and returns no translation at all. Thinking is therefore switched OFF explicitly
 *    (`ThinkingConfig(enableThinking = false)`) rather than asked for politely in the prompt.
 * 2. **An empty answer is a failed call, not a translation.** The probe produced exactly that
 *    failure mode through a different runtime, and a blank modal would read as a broken app, so a
 *    blank result throws and the modal says so.
 *
 * The model is loaded once and kept: it is 1.0 GB or 2.7 GB, and re-reading it per tap would put
 * seconds in front of every translation. A Conversation is created per CALL and closed again, because
 * a Conversation carries its history - and translation needs no history, only a shorter KV cache.
 */
object Qwen {

    /** Same instruction the probe used, so quality stays comparable to what was measured. */
    private const val SYSTEM =
        "You are a translation engine. Translate the German sentence the user sends into natural " +
            "English. Output only the translation, with no notes and no explanations."

    private const val MAX_NUM_TOKENS = 2048
    private const val MAX_OUTPUT_TOKENS = 400

    private val lock = Any()
    private var loaded: Loaded? = null

    private class Loaded(val engine: LiteRtEngine, val path: String) {
        fun close() = runCatching { engine.close() }
    }

    /**
     * Load the model for [engine], reusing the one already in memory.
     *
     * GPU first, CPU as the fallback: the GPU delegate needs `libOpenCL.so`, which the emulator may
     * not expose even with the manifest entry. Falling back is better than failing - it is slower,
     * not wrong.
     */
    private fun ensureLoaded(context: Context, engine: Engine): Loaded = synchronized(lock) {
        val file = Models.modelFile(context, engine)
            ?: throw IOException("the model is not downloaded - open Settings and download it")
        loaded?.let { if (it.path == file.absolutePath) return it else it.close() }

        val loadedEngine = runCatching { start(file, Backend.GPU(), context) }
            .recoverCatching { start(file, Backend.CPU(), context) }
            .getOrElse { throw IOException("the model could not be loaded", it) }
        return Loaded(loadedEngine, file.absolutePath).also { loaded = it }
    }

    private fun start(file: File, backend: Backend, context: Context): LiteRtEngine {
        val engine = LiteRtEngine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = context.cacheDir.absolutePath,
            )
        )
        engine.initialize()
        return engine
    }

    /** Translate one unit. Throws rather than returning an empty string. */
    suspend fun translate(context: Context, engine: Engine, text: String): String =
        withContext(Dispatchers.Default) {
            val conversation: Conversation = synchronized(lock) {
                ensureLoaded(context, engine).engine.createConversation(conversationConfig())
            }
            val answer = try {
                conversation.sendMessage(text)
            } finally {
                // Closed explicitly rather than with `use`: the close is best-effort either way, and
                // an AutoCloseable `use` extension is one more thing to be wrong about.
                runCatching { conversation.close() }
            }
            val out = answer.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(" ") { it.text }
                .trim()
            if (out.isBlank()) throw IOException("the model returned nothing")
            out
        }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(SYSTEM),
        // Greedy: the same input should give the same translation, and the cache assumes it. topK and
        // topP have no defaults in this API, so they are stated rather than left to chance.
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        thinkingConfig = ThinkingConfig(enableThinking = false),
        maxOutputToken = MAX_OUTPUT_TOKENS,
    )

    /** Release the model - switching engines should not leave 2.7 GB resident. */
    fun unload() {
        synchronized(lock) {
            loaded?.close()
            loaded = null
        }
    }

    /**
     * Whether a model is held in memory. The first tap after a cold start pays seconds for loading
     * it, so the modal says so instead of looking hung.
     */
    fun isLoaded(): Boolean = synchronized(lock) { loaded != null }
}