package com.felipe.pinyinreader

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The load-bearing assumption of this app: the Kotlin block extractor and the
 * Python annotator (tools/annotate.py) must produce the SAME block text, because
 * the pinyin sidecar is keyed by a hash of it. If they drift, the app imports a
 * book and silently shows no pinyin at all.
 *
 * Runs the real production parser under the JVM (kxml2 supplies XmlPullParser),
 * against the real sidecar, and reports the exact resolution rate.
 */
class BlockHashAgreementTest {

    /**
     * Fixtures are generated artefacts (tools/gen_hsk1.py + tools/annotate.py) and
     * are not committed, so locate them rather than hardcoding one machine's path.
     * Gradle's working directory for unit tests is the module dir, hence "../content".
     * Override with -Dpinyinreader.content=<dir>.
     */
    private val contentDir: String? = listOfNotNull(
        System.getProperty("pinyinreader.content"),
        "../content",
        "../../content",
        "C:/Users/Felipe/EpubReader/content",
    ).firstOrNull { File(it, "hsk1.epub").exists() }

    /**
     * name pairs that exist on disk. hsk1.epub is the authored HSK1 fixture;
     * 23962.epub is an adversarial case (Ming-dynasty vernacular, traditional
     * characters, 367k tokens) and is optional.
     */
    private fun availableFixtures(dir: String): List<Pair<File, File>> =
        listOf(
            "hsk1.epub" to "hsk1.pinyin.json",
            "23962.epub" to "23962.pinyin.json",
        ).map { File(dir, it.first) to File(dir, it.second) }
            .filter { it.first.exists() && it.second.exists() }

    @Test
    fun kotlinBlocksResolveAgainstPythonSidecar() {
        val dir = contentDir
        assumeTrue(
            "no fixture directory found - run tools/gen_hsk1.py then tools/annotate.py",
            dir != null,
        )
        if (dir == null) return // assumeTrue already threw; keeps the compiler happy

        val fixtures = availableFixtures(dir)
        assumeTrue("no fixtures present in $dir", fixtures.isNotEmpty())

        for ((epub, sidecarFile) in fixtures) {
            assertTrue("fixtures missing: ${epub.path}", epub.exists())
            assertTrue("sidecar missing: ${sidecarFile.path}", sidecarFile.exists())

            val book = EpubParser.parse(epub)
            val sidecar = Annotations.load(sidecarFile.path)

            val isHan = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf]")
            var total = 0
            var resolved = 0
            val misses = ArrayList<String>()

            for (ch in book.chapters) {
                for (bl in ch.blocks) {
                    if (!isHan.containsMatchIn(bl.text)) continue
                    total++
                    if (Annotations.tokens(sidecar, bl.text) != null) resolved++
                    else misses.add(bl.text)
                }
            }

            val rate = if (total == 0) 0.0 else 100.0 * resolved / total
            println("=== extractor agreement :: ${epub.name} ===")
            println("  kotlin chapters        : ${book.chapters.size}")
            println("  kotlin CJK blocks      : $total")
            println("  resolved via sidecar   : $resolved  ($rate%)")
            println("  python sidecar blocks  : ${sidecar.blocks.size}")
            println("  unresolved samples     : ${misses.take(5)}")

            assertTrue("no CJK blocks parsed for ${epub.name}", total > 10)
            assertTrue(
                "only $rate% of Kotlin blocks matched the Python sidecar for " +
                    "${epub.name}; sample misses: ${misses.take(3)}",
                rate >= 95.0,
            )
        }
    }

    /** The hash must be stable across both implementations. */
    @Test
    fun hashIsWhitespaceInsensitive() {
        val a = Annotations.key("我家有四个人，爸爸、妈妈、姐姐和我。")
        val b = Annotations.key("我家有四个人，\n爸爸、  妈妈、\t姐姐和我。")
        println("  hash A=$a  hash B=$b")
        assertTrue("whitespace changed the hash", a == b)
        assertTrue("unexpected hash length: ${a.length}", a.length == 16)
    }
}
