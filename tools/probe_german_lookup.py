# -*- coding: utf-8 -*-
"""Measure German lookup coverage on a real book, using the app's exact queries.

Simulates GermanDict.query() in Kotlin against de.db so the coverage number is
about the shipped lookup path, not a convenient Python reimplementation:
  1. token as an inflected form -> form.surface_lower -> entry
  2. token as a lemma in its own right -> entry.lemma_lower
with the same ß->ss + lowercase normalisation.

Usage: probe_german_lookup.py <book.epub> <de.db>
"""
import collections
import re
import sqlite3
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from annotate import epub_blocks  # noqa: E402  (reuses the EPUB block extractor)

# Same shape as GermanTokenizer.TOKEN in Dict.kt.
TOKEN = re.compile(r"[\w\u00c0-\u024f\u1e00-\u1eff]+(?:['\u2019\-][\w\u00c0-\u024f\u1e00-\u1eff]+)*")
CAP = 6


def norm(w: str) -> str:
    return w.replace("ß", "ss").lower()


# Must mirror GermanDict.kt, including its noise filter.
NOISE = "('character','punct','symbol')"
Q_FORM = (
    "SELECT e.lemma, e.pos, e.glosses FROM form f "
    "JOIN entry e ON e.id = f.lemma_id "
    f"WHERE f.surface_lower = ? AND e.pos NOT IN {NOISE} LIMIT ?"
)
Q_LEMMA = (
    f"SELECT lemma, pos, glosses FROM entry WHERE lemma_lower = ? "
    f"AND pos NOT IN {NOISE} LIMIT ?"
)


def main() -> int:
    epub, db = sys.argv[1], sys.argv[2]
    blocks = epub_blocks(epub)

    # Project Gutenberg wraps the text in a long ENGLISH licence. Measuring coverage
    # over it drags the number down with English function words, so measure only the
    # body between the START/END markers when they are present.
    start = end = None
    for i, b in enumerate(blocks):
        if start is None and re.search(r"START OF (THE|THIS) PROJECT GUTENBERG", b, re.I):
            start = i
        if end is None and re.search(r"END OF (THE|THIS) PROJECT GUTENBERG", b, re.I):
            end = i
    if start is not None and end is not None and end > start:
        print(f"  (licence boilerplate excluded: kept blocks {start + 1}..{end - 1} "
              f"of {len(blocks)})")
        blocks = blocks[start + 1:end]

    text = " ".join(blocks)
    tokens = [m.group(0) for m in TOKEN.finditer(text)]
    counts = collections.Counter(tokens)

    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)

    def _q(w):
        k = norm(w)
        rows = con.execute(Q_FORM, (k, CAP)).fetchall()
        if rows:
            return rows, "form"
        rows = con.execute(Q_LEMMA, (k, CAP)).fetchall()
        return (rows, "lemma") if rows else ([], None)

    def lookup(w):
        # Mirrors GermanDict.lookup(): retry on the stem for elided contractions.
        rows, how = _q(w)
        if rows:
            return rows, how
        cut = max(w.find("\u2019"), w.find("'"))
        if cut > 1:
            return _q(w[:cut])
        return [], None

    resolved, unresolved = {}, {}
    for w in counts:
        rows, how = lookup(w)
        if rows:
            resolved[w] = (how, rows)
        else:
            unresolved[w] = counts[w]

    total_tok = sum(counts.values())
    hit_tok = sum(counts[w] for w in resolved)
    print(f"book            : {os.path.basename(epub)}")
    print(f"blocks          : {len(blocks)}")
    print(f"tokens (total)  : {total_tok}")
    print(f"tokens (distinct): {len(counts)}")
    print()
    print(f"resolved distinct: {len(resolved)} / {len(counts)} "
          f"({100.0 * len(resolved) / max(1, len(counts)):.1f}%)")
    print(f"resolved by token: {hit_tok} / {total_tok} "
          f"({100.0 * hit_tok / max(1, total_tok):.1f}%)")
    via = collections.Counter(h for h, _ in resolved.values())
    print(f"  via inflection table: {via['form']}   via direct lemma: {via['lemma']}")

    print("\nsample hits (token -> resolved lemma | first gloss):")
    for w, _ in sorted(resolved.items(), key=lambda kv: -counts[kv[0]])[:12]:
        how, rows = resolved[w]
        lemma, pos, glosses = rows[0][0], rows[0][1], rows[0][2]
        mark = "inflected" if how == "form" else "direct   "
        print(f"  {w:<16} [{mark}] -> {lemma:<14} {pos:<5} {glosses[:44]}")

    if unresolved:
        print("\nunresolved, most frequent first (proper nouns / archaic forms are expected):")
        for w, c in sorted(unresolved.items(), key=lambda kv: -kv[1])[:12]:
            print(f"  {w:<24} x{c}")
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
