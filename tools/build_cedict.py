# -*- coding: utf-8 -*-
"""Build CC-CEDICT into a SQLite asset the Android app can query by tap.

CC-CEDICT line format:  TRAD SIMP [pin1 yin1] /def1/def2/
Tone 5 is the neutral tone (轻声) - the thing pypinyin gets wrong, which is
why CC-CEDICT is the primary reading source rather than a fallback.
"""
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "cedict.txt")
OUT = os.path.join(HERE, "..", "app-android", "app", "src", "main", "assets", "cedict.db")

LINE = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/(.*)/\s*$")

TONES = {
    "a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò",
    "u": "ūúǔù", "ü": "ǖǘǚǜ",
}


def mark(syl: str) -> str:
    """Numbered pinyin syllable -> tone-marked. 'ba4'->'bà', 'ba5'->'ba' (neutral)."""
    syl = syl.replace("u:", "ü").replace("v", "ü")
    m = re.match(r"^([A-Za-züÜ]+)(\d)$", syl)
    if not m:
        return syl
    base, tone = m.group(1), int(m.group(2))
    if tone == 5 or tone == 0:
        return base
    low = base.lower()
    # Standard placement: a > o > e > (iu/ui take the last vowel)
    for v in ("a", "o", "e"):
        if v in low:
            idx = low.index(v)
            break
    else:
        vowels = [i for i, ch in enumerate(low) if ch in "aeiouü"]
        if not vowels:
            return base
        idx = vowels[-1]
    ch = base[idx]
    marked = TONES[ch.lower()][tone - 1]
    if ch.isupper():
        marked = marked.upper()
    if not ch.islower() and not ch.isupper():
        return base
    return base[:idx] + marked + base[idx + 1:]


def toned(pinyin_field: str) -> str:
    return " ".join(mark(s) for s in pinyin_field.split())


def main() -> int:
    if not os.path.exists(SRC):
        print(f"missing {SRC}", file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)

    con = sqlite3.connect(OUT)
    cur = con.cursor()
    cur.execute("PRAGMA journal_mode=OFF")
    cur.execute("PRAGMA synchronous=OFF")
    cur.execute(
        "CREATE TABLE entry (id INTEGER PRIMARY KEY, trad TEXT, simp TEXT, "
        "py TEXT, py_toned TEXT, defs TEXT)"
    )

    rows, skipped = [], 0
    with open(SRC, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            m = LINE.match(line)
            if not m:
                skipped += 1
                continue
            trad, simp, py, defs = m.group(1), m.group(2), m.group(3), m.group(4)
            # Keep defs pipe-separated so one entry = one field.
            rows.append((trad, simp, py, toned(py), defs.replace("/", "|")))

    cur.executemany(
        "INSERT INTO entry (trad, simp, py, py_toned, defs) VALUES (?,?,?,?,?)", rows
    )
    cur.execute("CREATE INDEX idx_simp ON entry(simp)")
    cur.execute("CREATE INDEX idx_trad ON entry(trad)")
    con.commit()
    n = cur.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    con.close()

    size = os.path.getsize(OUT) / 1e6
    print(f"entries: {n}   skipped: {skipped}   db: {size:.1f} MB -> {os.path.relpath(OUT)}")

    # Spot-check the exact cases that motivated using CC-CEDICT over pypinyin.
    con = sqlite3.connect(OUT)
    for w in ("爸爸", "妈妈", "谢谢", "朋友", "时候", "喜欢", "银行", "觉得"):
        r = con.execute(
            "SELECT py, py_toned, defs FROM entry WHERE simp=? LIMIT 1", (w,)
        ).fetchone()
        print(f"  {w}: {r[0] if r else '-'}  ->  {r[1] if r else '-'}")
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
