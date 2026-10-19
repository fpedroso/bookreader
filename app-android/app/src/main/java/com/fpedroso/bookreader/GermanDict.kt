package com.fpedroso.bookreader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Grammar words that only ever appear in a *pointing* gloss ("plural of Haus",
 * "first/third-person singular preterite of gehen"). If anything else appears before the
 * " of ", the gloss is describing meaning ("a kind of bread") and counts as real.
 */
private val POINTER_TERMS = setOf(
    "inflection", "form", "plural", "singular", "genitive", "dative", "accusative",
    "nominative", "comparative", "superlative", "preterite", "past", "present",
    "participle", "imperative", "subjunctive", "indicative", "contraction", "alternative",
    "diminutive", "vocative", "strong", "weak", "mixed", "definite", "indefinite",
    "first", "second", "third", "person", "and", "or",
    // Gender and spelling qualifiers also appear in redirect phrases.
    "masculine", "feminine", "neuter", "spelling", "misspelling", "obsolete", "archaic",
)

/**
 * True when a gloss only redirects to another word rather than defining this one.
 *
 * `internal` rather than private so it is reachable from JVM unit tests: this is a pure
 * string predicate with no Android or database dependency, and getting it wrong changes
 * which sense leads the popup.
 */
internal fun isPointerGloss(gloss: String): Boolean {
    val t = gloss.trim().trimEnd(':', '.', ',', ';')
    // The data writes a redirect followed by the real meaning it points at, e.g.
    // "nominative/accusative neuter singular of der: the". That carries a definition, so
    // it must count as real - otherwise a word whose own entry is spelled this way gets
    // demoted and the bare lemma wins the popup, which is the bug this predicate exists
    // to prevent. Only a colon with *nothing* after it is a pure redirect.
    val colon = t.lastIndexOf(':')
    if (colon >= 0 && t.substring(colon + 1).trim().isNotEmpty()) return false
    val of = t.lastIndexOf(" of ")
    if (of <= 0) return false
    val words = t.substring(0, of).lowercase()
        .split(Regex("[/,\\s-]+"))
        .filter { it.isNotEmpty() }
    return words.isNotEmpty() && words.all { it in POINTER_TERMS }
}

/**
 * German -> English lookup backed by de.db, built by tools/build_german.py from the
 * Kaikki/Wiktextract German extract (which is itself extracted from the ENGLISH
 * Wiktionary, so the glosses are English). CC BY-SA 4.0.
 *
 * German inflects hard, so a lemma list alone is useless: the tapped token is usually
 * an inflected form ("ging", "Häusern", "gegangen"). The asset therefore carries an
 * inflection table mapping 1.34M surface forms back to their lemma, and lookup is
 * two steps: surface form -> lemma -> glosses. See build_german.py for why some raw
 * forms are deliberately excluded (template artefacts, auxiliaries, diminutives,
 * dialectal/obsolete spellings).
 */
class GermanDict(context: Context) : Dictionary {

    private val appContext = context.applicationContext

    @Volatile
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase = db ?: synchronized(this) {
        db ?: run {
            val target = File(appContext.filesDir, "de.db")
            if (!target.exists()) {
                appContext.assets.open("de.db").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).also { db = it }
        }
    }

    /**
     * German nouns are capitalised and sentence-initial words look capitalised too, so
     * lookups must be case-insensitive. ß and ss are folded together for the same
     * reason (Hauß/Haus). Must match tools/build_german.py's lower().
     */
    private fun normalise(word: String): String = word.replace("ß", "ss").lowercase()

    private val cache = HashMap<String, List<Lookup>>()

    override fun lookup(word: String): List<Lookup> = cache.getOrPut(word) {
        runCatching {
            query(word).ifEmpty {
                // German elides apostrophes: hat’s, wie’s, geht’s. Retry on the stem so
                // common contractions still resolve.
                val cut = word.indexOfFirst { it == '’' || it == '\'' }
                if (cut > 1) query(word.substring(0, cut)) else emptyList()
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Results are ranked, not ordered by lookup step.
     *
     * Ordering by step put the form-table hit first, which reads wrong for function
     * words: tapping `mein` led with `→ ich` (its form_of pointer) when the primary
     * sense is the determiner "my". Three buckets fix that:
     *
     *   1. the token's own entry, with real meaning     `mein`   -> "my"
     *   2. the lemma that a form resolves to            `Häuser` -> Haus -> "house"
     *   3. the token's own entry, but only a pointer    `Häuser` -> "plural of Haus"
     *
     * Note that "own entry first" is *not* the rule either: `Häuser` and `ging` each have
     * their own entry, but it is a bare pointer, so bucket 2 has to win for them. The
     * pointer distinction is what separates the two cases, and it is why this cannot be
     * a simple preference for the direct lemma.
     */
    private fun query(word: String): List<Lookup> {
        val key = normalise(word)
        val viaForm = ArrayList<Lookup>()
        val ownMeaning = ArrayList<Lookup>()
        val ownPointer = ArrayList<Lookup>()

        // 1. the token as an inflected form of some lemma
        val seenLemma = HashSet<String>()
        open().rawQuery(
            "SELECT e.lemma, e.pos, e.glosses FROM form f " +
                "JOIN entry e ON e.id = f.lemma_id " +
                // 'character'/'punct'/'symbol' are single-letter and punctuation
                // entries ("a" -> the letter A), which are pure noise on a tap.
                "WHERE f.surface_lower = ? AND e.pos NOT IN ('character','punct','symbol') " +
                "LIMIT 8",
            arrayOf(key),
        ).use { c ->
            while (c.moveToNext()) {
                val lemma = c.getString(0) ?: continue
                val pos = c.getString(1) ?: ""
                if (!seenLemma.add(lemma)) continue
                viaForm.add(
                    Lookup(
                        headword = word, lemma = lemma, pos = pos,
                        glosses = splitGlosses(c.getString(2)),
                    )
                )
            }
        }

        // 2. the token as a lemma in its own right (uninflected, or a noun already in
        //    its citation form). Skipped when step 1 already produced that lemma.
        open().rawQuery(
            "SELECT lemma, pos, glosses FROM entry WHERE lemma_lower = ? " +
                "AND pos NOT IN ('character','punct','symbol') LIMIT 8",
            arrayOf(key),
        ).use { c ->
            while (c.moveToNext()) {
                val lemma = c.getString(0) ?: continue
                if (lemma in seenLemma) continue
                val glosses = splitGlosses(c.getString(2))
                val hit = Lookup(
                    headword = word,
                    lemma = if (lemma.equals(word, true)) null else lemma,
                    pos = c.getString(1) ?: "",
                    glosses = glosses,
                )
                // A pure pointer is the least useful thing to lead with, but still worth
                // showing - it is often the only thing on offer. A row with no glosses is
                // no more informative, so `all` over an empty list demotes it too.
                if (glosses.all(::isPointerGloss)) ownPointer.add(hit) else ownMeaning.add(hit)
            }
        }

        return (ownMeaning + viaForm + ownPointer).take(6)
    }

    private fun splitGlosses(raw: String?): List<String> =
        (raw ?: "").split(" | ").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Must be called off the main thread. The first call copies de.db (over 100 MB)
     * out of assets, which would ANR if it happened on a tap.
     */
    override fun warmUp() {
        open()
    }
}
