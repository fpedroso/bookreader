package com.fpedroso.bookreader

/**
 * A run of consecutive sentences inside one paragraph's [RowItem.sentences] - the unit that is
 * translated as a whole.
 *
 * `count` is 1 for a plain sentence tap. "Translate more" grows it by one sentence at a time, so
 * a reader can compose a longer unit from a fragment the structural splitter leaves at a
 * paragraph start (`Mr. und Mrs.`) - fragments translate badly on their own because their verb
 * and object live in the next sentence.
 */
data class UnitSpan(val start: Int, val count: Int)

/**
 * The source text of [span]: its sentence texts joined with a single space.
 *
 * A space is exact here, not an approximation. [Sentences.split] collapses every whitespace run
 * before cutting, and it only ever cuts at whitespace, so joining the pieces with " "
 * reconstructs the paragraph text. There are no character offsets to rebuild from - that
 * machinery was deleted on purpose, see [translationKey].
 */
fun unitSource(sentences: List<String>, span: UnitSpan): String =
    sentences.subList(span.start, span.start + span.count).joinToString(" ")

/**
 * The span one sentence longer than [span], or null when the paragraph holds no following
 * sentence. Null is what hides "Translate more" rather than showing an action that does nothing.
 */
fun nextSpan(sentences: List<String>, span: UnitSpan): UnitSpan? {
    val next = span.start + span.count
    return if (next < sentences.size) UnitSpan(span.start, span.count + 1) else null
}
