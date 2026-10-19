package com.fpedroso.bookreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark pastel palette.
 *
 * There was no Compose theme at all before this, so the app rendered on Material's defaults
 * over a light Android window - a stark white page. Pastel means high lightness and low
 * saturation, so "darker pastel" is a dark ground with accents that stay soft and desaturated:
 * nothing here is fully saturated, and nothing is pure black or pure white.
 *
 * The reading surface is deliberately the darkest thing in the palette. A page of text is
 * mostly background, so that colour sets the whole mood, and keeping the ground near-black
 * while lifting `surface` slightly is what lets the per-sentence borders read as edges.
 */
private val DarkPastel = darkColorScheme(
    // Pastel lavender: selection, links, the accent that says "this one".
    primary = Color(0xFFCDB4DB),
    onPrimary = Color(0xFF2B2233),
    primaryContainer = Color(0xFF443A4E),
    onPrimaryContainer = Color(0xFFEFE2F7),
    // Dusty sage and blush, for anything needing a second and third voice.
    secondary = Color(0xFFA8C5B4),
    onSecondary = Color(0xFF1E2A23),
    tertiary = Color(0xFFE4B7B0),
    onTertiary = Color(0xFF2F2220),
    background = Color(0xFF16161B),
    onBackground = Color(0xFFE7E2E9),
    surface = Color(0xFF1C1C22),
    onSurface = Color(0xFFE7E2E9),
    surfaceVariant = Color(0xFF2A2A33),
    onSurfaceVariant = Color(0xFFB6B0BC),
    // outlineVariant draws the per-sentence border. Light enough to be seen against the dark
    // ground, dark enough that a page of sentences stays texture rather than a grid of frames.
    outline = Color(0xFF6A6472),
    outlineVariant = Color(0xFF3D3A45),
    error = Color(0xFFE7A3A9),
    onError = Color(0xFF2C1A1C),
)

/**
 * The app's single theme. Deliberately not switching on the system setting: the reading
 * surface has one intended look, and a light variant would be a second thing to maintain.
 */
@Composable
fun BookReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkPastel, content = content)
}
