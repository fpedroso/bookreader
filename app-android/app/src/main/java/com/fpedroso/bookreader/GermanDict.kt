package com.fpedroso.bookreader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

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

    private fun query(word: String): List<Lookup> {
        val key = normalise(word)
        val out = LinkedHashMap<String, Lookup>()

        // 1. the token as an inflected form of some lemma
        val viaForm = ArrayList<Pair<String, String>>() // lemma, pos
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
                val glosses = splitGlosses(c.getString(2))
                val lk = viaForm.firstOrNull { it.first == lemma }
                if (lk == null) {
                    viaForm.add(lemma to pos)
                    out["f:$lemma"] = Lookup(
                        headword = word, lemma = lemma, pos = pos, glosses = glosses,
                    )
                }
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
                if (out.containsKey("f:$lemma")) continue
                out["e:$lemma:${c.getString(1)}"] = Lookup(
                    headword = word,
                    lemma = if (lemma.equals(word, true)) null else lemma,
                    pos = c.getString(1) ?: "",
                    glosses = splitGlosses(c.getString(2)),
                )
            }
        }
        return out.values.take(6)
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
