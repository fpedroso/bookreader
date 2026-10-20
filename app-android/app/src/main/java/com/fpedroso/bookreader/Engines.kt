package com.fpedroso.bookreader

/**
 * The translation engines this app can offer, and the cache namespace that follows from the choice.
 *
 * Two engines' answers for the SAME text are different answers, so the engine is part of the
 * translation cache key ([unitKey]). Without that, switching engines would serve the other engine's
 * output for anything already translated - silently, and looking exactly like a quality regression.
 */
enum class Engine(
    val id: String,
    val label: String,
    val note: String,
) {
    MLKIT(
        id = "mlkit",
        label = "ML Kit",
        note = "Fast and tiny (~60 MB of language packs). Occasionally produces broken English on hard sentences.",
    ),
    QWEN_1_7B(
        id = "qwen3-1.7b",
        label = "Qwen3 1.7B",
        note = "977 MB. 2-4 s per sentence. Writes real sentences where ML Kit breaks down; can misread meaning.",
    ),
    QWEN_4B(
        id = "qwen3-4b",
        label = "Qwen3 4B",
        note = "2.5 GB. Roughly twice the wait of the 1.7B, and the best of the three local models on the hardest sentences.",
    ),
    OPENROUTER(
        id = "openrouter",
        label = "OpenRouter (cloud)",
        note = "Cents for a whole book, and usually the best of the four — but the text leaves the device. Needs an API key.",
    ),
    ;

    /**
     * Whether the engine translates a word with its sentence as context.
     *
     * A language model can use the context sentence and a word alone is exactly what translates
     * ambiguously, so the word's cache key has to include that sentence for these engines - otherwise
     * "Bank" in two different sentences would share one entry. ML Kit's API accepts only the text, so
     * for it the context is not part of the key and would only duplicate entries.
     */
    val usesContext: Boolean get() = this != MLKIT

    /**
     * Whether the text leaves the device for this engine.
     *
     * Surfaced in Settings rather than buried: the app is otherwise entirely on-device, and a reader
     * choosing this engine is choosing to send what they are reading to a third party.
     */
    val cloud: Boolean get() = this == OPENROUTER

    /**
     * Whether this build can run the engine.
     *
     * All three, now that LiteRT-LM is a dependency, so what actually gates a choice at runtime is
     * whether the model is on the device - see [Models.resolve]. The distinction is kept because an
     * engine could be listed before its runtime ships, and then it must say so rather than be
     * selectable and fail on the first tap.
     */
    val runnable: Boolean get() = true

    companion object {
        /** An unknown or missing id reads as the default rather than as a crash. */
        fun fromId(id: String?): Engine = entries.firstOrNull { it.id == id } ?: MLKIT
    }
}

/**
 * The translation cache key for one unit under one engine.
 *
 * The text is what a translation depends on, so it is what the key is built from - plus the engine,
 * because two engines give different answers, and plus the context sentence for engines that read it.
 * NUL is the separator: [Annotations.key] strips whitespace, so a separator has to be something that
 * cannot appear in the text and is not whitespace.
 */
fun unitKey(
    engine: Engine,
    source: String,
    context: String? = null,
    model: String? = null,
): String {
    val parts = ArrayList<String>(4)
    parts.add(engine.id)
    parts.add(source)
    if (engine.usesContext && !context.isNullOrBlank()) parts.add(context)
    // A cloud engine's model is a free-text setting, so it is part of what answered and has to be
    // part of the key: otherwise retyping the model id serves the previous model's translations
    // while the modal names the new one, which reads as the new model being bad at its job. (The
    // local engines pin their model to the engine id, so nothing is passed there.)
    if (engine.cloud && !model.isNullOrBlank()) parts.add(model)
    // Built on [translationKey] so the properties that make the key safe - whitespace-invariant,
    // sensitive to the text itself - hold for namespaced keys too.
    return translationKey(parts.joinToString("\u0000"))
}
