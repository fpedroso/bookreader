package com.fpedroso.bookreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark sepia palette.
 *
 * There was no Compose theme at all before this, so the app rendered on Material's defaults
 * over a light Android window - a stark white page. Sepia means warm, paper-like tones, so
 * this is a dark brown ground rather than a neutral or violet one, with cream text instead of
 * white: the point is that a page of text feels like paper, and cool greys fight that.
 *
 * Every colour is warm-shifted, including the greys. A "neutral" here still carries red and
 * yellow, which matters more than the accent hues do - the background is what a reader looks
 * at most, so its temperature sets the mood on its own. The reading surface stays the darkest
 * thing in the palette, with `surface` lifted just enough that outlineVariant reads as an edge.
 */
private val DarkSepia = darkColorScheme(
    // Ink and tan: the accent that says "this one", warm enough to belong on the paper.
    primary = Color(0xFFD9B382),
    onPrimary = Color(0xFF322413),
    primaryContainer = Color(0xFF4A3A26),
    onPrimaryContainer = Color(0xFFF1E2C8),
    // Aged parchment and a dusty rose, for anything needing a second and third voice.
    secondary = Color(0xFFB9A98C),
    onSecondary = Color(0xFF2A2418),
    tertiary = Color(0xFFC9A0A0),
    onTertiary = Color(0xFF30201F),
    background = Color(0xFF1E1813),
    onBackground = Color(0xFFEADFCB),
    surface = Color(0xFF241D17),
    onSurface = Color(0xFFEADFCB),
    surfaceVariant = Color(0xFF322A22),
    // Warm beige, not grey: muted text is where a cool tone would be most obvious.
    onSurfaceVariant = Color(0xFFC0B39D),
    // outlineVariant draws the per-sentence border. Warm taupe: light enough to be seen against
    // the brown ground, dark enough that a page of sentences stays texture rather than a grid.
    outline = Color(0xFF6E6252),
    outlineVariant = Color(0xFF443B30),
    error = Color(0xFFE0A08E),
    onError = Color(0xFF33211B),
)

/**
 * The app's single theme. Deliberately not switching on the system setting: the reading
 * surface has one intended look, and a light variant would be a second thing to maintain.
 */
@Composable
fun BookReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkSepia, content = content)
}
