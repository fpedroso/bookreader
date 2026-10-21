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
 * Alpha of the wash behind the word under the finger in the paged reader.
 *
 * Low on purpose: the wash is the primary colour at a fifth strength, so the word's frequency tint
 * still reads through it. A solid highlight would answer "which word" by hiding the answer to "how
 * common is it", and the two are on screen at once.
 */
const val PRESSED_ALPHA = 0.22f

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
// background. Four fixed steps per theme instead, evenly spaced: each theme's five bands are its
// ordinary text colour plus these four, and every step is the same distance from its neighbour
// (~23 per channel), so the page reads as a gentle ramp rather than as stripes. The faintest still
// sits at 5.2:1 (dark) and 4.4:1 (light) against its page - that band is around a fifth of the
// running text, so it has to be READ, not merely noticed.
private val FADED_FREQUENT_DARK = Color(0xFFD0CAD7)
private val FADED_OCCASIONAL_DARK = Color(0xFFB9B3C1)
private val FADED_UNCOMMON_DARK = Color(0xFFA29CAB)
private val FADED_RARE_DARK = Color(0xFF8B8595)
private val FADED_FREQUENT_LIGHT = Color(0xFF34313A)
private val FADED_OCCASIONAL_LIGHT = Color(0xFF4B4852)
private val FADED_UNCOMMON_LIGHT = Color(0xFF625F6A)
private val FADED_RARE_LIGHT = Color(0xFF797682)

/**
 * The colour for [band] on a dark or light page, or null to leave it alone.
 *
 * **Pure, and deliberately not composable**, so a test can assert the invariants the reader depends
 * on: every band except [Band.CONSTANT] has a colour, no two steps are the same, they get fainter in
 * order, and the faintest one still has readable contrast. A missing colour is not a cosmetic
 * problem - the word silently falls back to ordinary text and reads as a word that repeats
 * constantly, which is what happened when the fifth band was added.
 */
fun tintFor(band: Band, dark: Boolean): Color? = when (band) {
    // The band that repeats most is the page's ordinary text, deliberately: it is 30-40% of the
    // running text, so tinting it would colour the majority of every page and leave the untinted
    // minority as the thing that stands out. What shading does is FADE THE TAIL.
    Band.CONSTANT -> null
    Band.FREQUENT -> if (dark) FADED_FREQUENT_DARK else FADED_FREQUENT_LIGHT
    Band.OCCASIONAL -> if (dark) FADED_OCCASIONAL_DARK else FADED_OCCASIONAL_LIGHT
    Band.UNCOMMON -> if (dark) FADED_UNCOMMON_DARK else FADED_UNCOMMON_LIGHT
    Band.RARE -> if (dark) FADED_RARE_DARK else FADED_RARE_LIGHT
}

/** [tintFor] under the current theme. */
@Composable
fun wordTint(band: Band): Color? = tintFor(band, isDarkTheme())
