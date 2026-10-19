package com.fpedroso.bookreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Material 3's stock dark scheme, unmodified.
 *
 * Two custom palettes came before this - a violet-slate "dark pastel", then a dark sepia with
 * cream text - and the sepia did not sit well. Rather than guess a third time, the app uses the
 * Material defaults. `darkColorScheme()` with no arguments IS the stock M3 dark palette, so
 * there is nothing here to maintain and nothing to drift out of date.
 *
 * One visible consequence: the per-sentence border uses outlineVariant, which the stock scheme
 * sets to #49454F - a mid grey-lavender that reads more strongly than the warm taupe it
 * replaces, so the sentence boxes are a little more present on the page than they were.
 */

/**
 * The app's single theme. Deliberately not switching on the system setting: the reading
 * surface has one intended look, and a light variant would be a second thing to maintain.
 */
@Composable
fun BookReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
