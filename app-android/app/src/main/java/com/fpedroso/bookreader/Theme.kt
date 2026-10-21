package com.fpedroso.bookreader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Material 3's stock schemes, unmodified - one dark, one light.
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
 * Which palette the reader uses.
 *
 * Ids are stored in `settings.json`, so they are stable strings rather than enum ordinals, and an
 * unknown id reads back as [DARK] - the same rule [Engine.fromId] follows. A settings file written
 * before this existed has no id at all, which resolves here rather than failing.
 */
enum class ThemeMode(val id: String, val label: String, val note: String) {
    DARK("dark", "Dark", "The reading surface the app was designed around."),
    LIGHT("light", "Light", "A light reading surface, for daylight reading."),
    SYSTEM("system", "Follow the system", "Light or dark, as the phone is set."),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: DARK
    }
}

/**
 * The chosen [ThemeMode], shared by the whole window.
 *
 * [MainActivity] wraps everything in the theme, but Settings - which changes it - is rendered
 * inside `App`, below that call, so the choice has to live above both. One Activity and one window
 * make a single holder the simplest correct place for it.
 *
 * [apply] runs before the first frame with the stored value, so the window never flashes the wrong
 * palette at startup, and again whenever Settings saves, so picking a mode recolours the app
 * immediately rather than after a restart.
 */
object Appearance {
    var mode by mutableStateOf(ThemeMode.DARK)

    fun apply(id: String?) {
        mode = ThemeMode.fromId(id)
    }
}

/**
 * The app's theme, in whichever [ThemeMode] is current.
 *
 * Deliberately stock Material in both directions: every colour the reader uses is a
 * MaterialTheme role, so the light palette recolours the page, the sentence borders, the inline
 * chips and the dialogs with no per-screen work and no role left undefined.
 */
@Composable
fun BookReaderTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
