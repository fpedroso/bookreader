package com.fpedroso.bookreader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

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
 * Whether the window is dark right now - the reader's choice, or the system's when they picked
 * "follow the system". Read from [Appearance] rather than passed around, so the theme and anything
 * that colours text by hand cannot disagree about which palette is on.
 */
@Composable
fun isDarkTheme(): Boolean = when (Appearance.mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * The app's theme, in whichever [ThemeMode] is current.
 *
 * Deliberately stock Material in both directions: every colour the reader uses is a
 * MaterialTheme role, so the light palette recolours the page, the sentence borders, the inline
 * chips and the dialogs with no per-screen work and no role left undefined.
 */
@Composable
fun BookReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

// ------------------------------------------------------------ frequency shading

// Explicit colours, not an alpha on the text colour. An alpha low enough to read as "faint" over
// near-black renders as a smudge rather than as information, and it compounds differently on each
// background. Four fixed steps per theme instead, and the faintest step is still above 4:1 against
// its page: the faint band is 34-44% of the running text, so it has to be READ, not just noticed.
private val FADED_FREQUENT_DARK = Color(0xFFBDB6C6)
private val FADED_OCCASIONAL_DARK = Color(0xFF97909F)
private val FADED_RARE_DARK = Color(0xFF7E7886)
private val FADED_FREQUENT_LIGHT = Color(0xFF3A3644)
private val FADED_OCCASIONAL_LIGHT = Color(0xFF5C5764)
private val FADED_RARE_LIGHT = Color(0xFF7B7684)

/**
 * The colour for a word in [band], or null to leave it in the page's ordinary text colour.
 *
 * **CONSTANT returns null, and that is the design.** The words a book repeats most are 27-40% of
 * its running text, so tinting THEM would colour the majority of every page and leave the untinted
 * minority as the thing that stands out - exactly backwards. What shading does is FADE THE TAIL:
 * words the book barely uses recede, and the recurring core reads as the solid text. It also keeps
 * most of a page at full contrast, which is the version a person can actually read for an hour.
 */
@Composable
fun wordTint(band: Band): Color? = when (band) {
    Band.CONSTANT -> null
    Band.FREQUENT -> if (isDarkTheme()) FADED_FREQUENT_DARK else FADED_FREQUENT_LIGHT
    Band.OCCASIONAL -> if (isDarkTheme()) FADED_OCCASIONAL_DARK else FADED_OCCASIONAL_LIGHT
    Band.RARE -> if (isDarkTheme()) FADED_RARE_DARK else FADED_RARE_LIGHT
}
