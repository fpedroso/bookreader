package com.fpedroso.bookreader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * CC-CEDICT lookup (CC-BY-SA 4.0), built into an indexed SQLite asset by
 * tools/build_cedict.py.
 *
 * Why CC-CEDICT rather than a pinyin library: it encodes the neutral tone
 * (轻声) that pypinyin drops - 爸爸 is [ba4 ba5], pypinyin says "bà bà" - and it
 * is keyed on both scripts, so traditional-only forms still resolve.
 */
object Cedict {

    data class Entry(val pinyin: String, val definitions: List<String>)

    @Volatile
    private var db: SQLiteDatabase? = null

    fun open(context: Context): SQLiteDatabase = db ?: synchronized(this) {
        db ?: run {
            val target = File(context.filesDir, "cedict.db")
            if (!target.exists()) {
                context.assets.open("cedict.db").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).also { db = it }
        }
    }

    private val cache = HashMap<String, List<Entry>>()

    /**
     * Reading-only lookups for in-app annotation, keyed like [cache] but including
     * misses (`null`), because segmentation probes every substring at a position and the
     * misses are the majority. Cleared wholesale if it grows past a cap, which keeps a
     * long book from holding every probed substring forever.
     */
    private val pinyinCache = HashMap<String, String?>()

    /**
     * The tone-marked reading for a headword, or null when [word] is not one.
     *
     * Ordering matters: CC-CEDICT carries several rows for a character with both a common
     * and a proper-noun reading, and capitalises the proper noun's pinyin (都 [dou1] "all"
     * vs 都 [Du1], the surname/place). An unordered LIMIT 1 picked either, which read
     * 我们都... as "wǒ men Dū" and 教 as the surname Jiào instead of "jiāo" (to teach).
     * Uppercase-first ordering pushes those behind the ordinary reading, while words that
     * are *only* proper nouns (中国) keep their capitalised reading because they are the
     * sole row. The comparison is ASCII so tone-marked vowels (ài) are not mistaken for
     * capitalised syllables.
     */
    fun pinyin(context: Context, word: String): String? {
        synchronized(pinyinCache) {
            if (pinyinCache.containsKey(word)) return pinyinCache[word]
            if (pinyinCache.size > 150_000) pinyinCache.clear()
        }
        val value = runCatching {
            open(context).rawQuery(
                "SELECT py_toned FROM entry WHERE simp = ? OR trad = ? " +
                    "ORDER BY (substr(py_toned, 1, 1) BETWEEN 'A' AND 'Z') ASC, id ASC LIMIT 1",
                arrayOf(word, word),
            ).use { c -> if (c.moveToNext()) c.getString(0) else null }
        }.getOrNull()
        synchronized(pinyinCache) { pinyinCache[word] = value }
        return value
    }

    /** Up to [limit] senses, ordinary reading before a proper-noun reading. */
    fun lookup(context: Context, word: String, limit: Int = 4): List<Entry> =
        cache.getOrPut(word) {
            runCatching {
                open(context).rawQuery(
                    "SELECT py_toned, defs FROM entry WHERE simp = ? OR trad = ? " +
                        "ORDER BY (substr(py_toned, 1, 1) BETWEEN 'A' AND 'Z') ASC, id ASC " +
                        "LIMIT ?",
                    arrayOf(word, word, limit.toString()),
                ).use { c ->
                    val out = ArrayList<Entry>()
                    while (c.moveToNext()) {
                        val defs = (c.getString(1) ?: "")
                            .split('|')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        out.add(Entry(c.getString(0) ?: "", defs))
                    }
                    out
                }
            }.getOrElse { emptyList() }
        }
}
