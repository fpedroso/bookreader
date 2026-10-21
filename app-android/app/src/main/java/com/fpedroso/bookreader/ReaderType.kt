package com.fpedroso.bookreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.TextUnit

/**
 * The reading page's type and rhythm, in ONE place.
 *
 * This exists because pagination trusts a measurement: `TextMeasurer` says how many lines a block
 * takes, and on that number the page breaks depend. If the measurement and the render disagree by
 * even one line, text is either clipped away or doubled - and the documented way to get that wrong
 * is subtle (a `measure()` call with no constraints lays the text out at INFINITE width: 25 lines
 * across 30,154px, against 235 real lines at 1080px).
 *
 * So the two halves share ONE [TextStyle] object rather than two sets of matching numbers: the same
 * font size, line height, weight and everything else the ambient style contributes. Same inputs,
 * same layout, by construction.
 */
object ReaderType {
    val BODY_FONT = 19.sp
    val BODY_LINE = 30.sp
    val PARA_GAP = 20.dp
    val HEAD_GAP_TOP = 40.dp
    val HEAD_GAP_BOTTOM = 10.dp

    /**
     * The page's text inset, on every block - heading and body alike - so the whole book keeps ONE
     * left edge. It used to be the padding INSIDE the sentence band; the band is gone but the margin
     * stayed, because without it the prose slides under the headings' left edge.
     */
    val TEXT_INSET = 6.dp

    /** The page counter's bar at the foot of the page, reserved out of the page's height. */
    val COUNTER_HEIGHT = 26.dp
}

/**
 * [ReaderType] in the two forms the two halves need: [TextStyle]s for drawing and measuring, and
 * pixels for the arithmetic. Built once per theme/density and then shared.
 */
data class ReaderMetrics(
    val bodyStyle: TextStyle,
    val headStyle: TextStyle,
    val bodyPx: Float,
    val bodyLinePx: Float,
    val headPx: Float,
    val headLinePx: Float,
    val insetPx: Float,
    val paraGapPx: Float,
    val headGapTopPx: Float,
    val headGapBottomPx: Float,
    val counterPx: Float,
) {
    /** The style a row is drawn and measured with. One lookup, one answer. */
    fun style(heading: Boolean): TextStyle = if (heading) headStyle else bodyStyle

    /** The gap that precedes a row, which is also the `gapAbove` of its first measured line. */
    fun gapBefore(heading: Boolean): Float = if (heading) headGapTopPx else paraGapPx

    /** What a page layout depends on - see [LayoutSignature]. */
    fun signature(widthPx: Int, heightPx: Int) = LayoutSignature(
        widthPx = widthPx,
        heightPx = heightPx,
        bodyPx = bodyPx,
        linePx = bodyLinePx,
        paraGapPx = paraGapPx,
        headGapTopPx = headGapTopPx,
        headGapBottomPx = headGapBottomPx,
        headLinePx = headLinePx,
    )
}

/**
 * Read the reading page's metrics. `LocalTextStyle` is merged rather than replaced so the family,
 * letter spacing and so on that the theme contributes are still in play - exactly what passing
 * `fontSize`/`lineHeight` to a `Text` does, which is how the scrolling reader has always drawn it.
 */
@Composable
fun rememberReaderMetrics(): ReaderMetrics {
    val ambient = LocalTextStyle.current
    val typography = androidx.compose.material3.MaterialTheme.typography
    val density = LocalDensity.current
    val headFont = typography.titleLarge.fontSize
    val headLine = typography.titleLarge.lineHeight
    return remember(ambient, density, headFont, headLine, typography) {
        val body = ambient.merge(TextStyle(fontSize = ReaderType.BODY_FONT, lineHeight = ReaderType.BODY_LINE))
        val head = ambient.merge(
            TextStyle(
                fontSize = headFont,
                lineHeight = headLine,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        with(density) {
            ReaderMetrics(
                bodyStyle = body,
                headStyle = head,
                bodyPx = ReaderType.BODY_FONT.toPx(),
                bodyLinePx = ReaderType.BODY_LINE.toPx(),
                headPx = headFont.toPx(),
                headLinePx = headLine.toPx(),
                insetPx = ReaderType.TEXT_INSET.toPx(),
                paraGapPx = ReaderType.PARA_GAP.toPx(),
                headGapTopPx = ReaderType.HEAD_GAP_TOP.toPx(),
                headGapBottomPx = ReaderType.HEAD_GAP_BOTTOM.toPx(),
                counterPx = ReaderType.COUNTER_HEIGHT.toPx(),
            )
        }
    }
}
