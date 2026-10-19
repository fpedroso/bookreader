package com.felipe.pinyinreader

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

    /** Up to [limit] senses. Words with multiple readings have multiple rows. */
    fun lookup(context: Context, word: String, limit: Int = 4): List<Entry> =
        cache.getOrPut(word) {
            runCatching {
                open(context).rawQuery(
                    "SELECT py_toned, defs FROM entry WHERE simp = ? OR trad = ? LIMIT ?",
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
