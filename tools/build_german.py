# -*- coding: utf-8 -*-
"""Build the German->English dictionary asset from the Kaikki/Wiktextract extract.

Source: https://kaikki.org/dictionary/German/ (extracted from the ENGLISH Wiktionary,
so glosses are English). Download the .gz first (see README).

Why this source rather than FreeDict: German inflects hard, so a lemma list alone is
useless - tapping "ging" or "Häusern" has to resolve to "gehen" / "Haus". Wiktextract
gives both a lemma list with English glosses AND the inflection tables, plus a
structured form_of pointer on the inflected entries themselves.

Trap: the raw `forms` arrays are NOT a clean inflection list. They contain
 - template artifacts: 'strong', 'de-conj', '7 strong'  (tags: table-tags,
   inflection-template, class)
 - auxiliaries: 'sein'                                  (tags: auxiliary) - would
   otherwise map the auxiliary verb to every verb that uses it
 - diminutives: 'Häuschen'                               (tags: diminutive) - these are
   separate words with their own entries
So forms are kept only when their tags avoid that blocklist.

Usage: build_german.py <kaikki-de.jsonl.gz> <out.db>
"""
import gzip
import json
import os
import sqlite3
import sys

# Tags that mean "not an inflection of this lemma".
BLOCKED_TAGS = {
    "table-tags", "inflection-template", "class", "auxiliary", "diminutive",
    "no-table-tags", "inflection-auxiliary",
    # Non-standard spellings. These are a large share of the raw mapping count
    # (dialectal diminutives and obsolete/alternative spellings) and mapping them
    # only makes lookups hit weird forms while bloating the asset.
    "alternative", "obsolete", "archaic", "rare", "dialectal", "nonstandard",
    "misspelling", "poetic", "dated", "colloquial", "informal", "regional",
    "Austrian", "Bavarian", "Swiss", "Swabian", "Ruhrdeutsch", "Berlinisch",
}

MAX_SENSES = 3

# Only build inflection mappings for parts of speech where resolving a form back to
# its lemma is actually informative. Articles, pronouns, determiners and numerals share
# paradigm tables that cross-link each other, which produced actively wrong answers:
# "er" (he) mapped to "du" (you), "die" to "der". Those tokens almost always have their
# own dictionary entry anyway, so the direct-lemma step covers them accurately.
ALLOWED_FORM_POS = {"noun", "verb", "adj", "adv", "name"}

# Function words are never "inflections of" an arbitrary lemma, yet some entries' form
# lists contain them - a name entry whose head template renders with an article lists
# "der"/"die" among its forms. That produced "der" -> CIA and "das" -> FBI. Block these
# as SURFACE forms; tapping one still works, because the articles and pronouns have
# their own entries and are reached by the direct-lemma step.
FUNC_SURFACES = {
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem",
    "eines", "einer", "eines", "er", "sie", "es", "du", "ich", "wir", "ihr", "uns",
    "euch", "mein", "dein", "sein", "unser", "und", "oder", "aber", "nicht",
}


def lower(s: str) -> str:
    # German sharp s: keep lookup stable without pulling in a Unicode dependency.
    return s.replace("ß", "ss").lower()


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = sys.argv[1], sys.argv[2]

    if os.path.exists(out):
        os.remove(out)
    con = sqlite3.connect(out)
    cur = con.cursor()
    cur.execute("PRAGMA journal_mode=OFF")
    cur.execute("PRAGMA synchronous=OFF")
    cur.execute(
        "CREATE TABLE entry (id INTEGER PRIMARY KEY, lemma TEXT, lemma_lower TEXT, "
        "pos TEXT, glosses TEXT)"
    )
    # Built with the lemma spelled out, then rewritten to integer lemma ids at the
    # end: repeating a ~9-char lemma across 1.4M rows is what made the first version
    # 127 MB, which is too big to ship inside the APK.
    cur.execute("CREATE TABLE form (surface_lower TEXT, lemma_lower TEXT)")

    entries, forms, n_lines = [], [], 0
    seen_entry, seen_form = set(), set()
    stats = {"lines": 0, "entries": 0, "form_of": 0, "forms": 0, "no_gloss": 0}

    def flush():
        if entries:
            cur.executemany(
                "INSERT INTO entry (lemma, lemma_lower, pos, glosses) "
                "VALUES (?,?,?,?)", entries)
            entries.clear()
        if forms:
            cur.executemany(
                "INSERT INTO form (surface_lower, lemma_lower) VALUES (?,?)", forms)
            forms.clear()

    with gzip.open(src, "rt", encoding="utf-8") as fh:
        for line in fh:
            stats["lines"] += 1
            if not line.strip():
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            if d.get("lang_code") != "de":
                continue
            word = (d.get("word") or "").strip()
            if not word:
                continue
            pos = d.get("pos") or ""
            senses = d.get("senses") or []

            # --- surface form -> lemma, from the structured form_of pointer ---
            for s in senses:
                for fo in (s.get("form_of") or []):
                    lemma = (fo.get("word") or "").strip()
                    if lemma and lemma != word:
                        key = (lower(word), lower(lemma))
                        if key not in seen_form:
                            seen_form.add(key)
                            forms.append(key)
                            stats["form_of"] += 1

            # --- lemma itself: first glosses of the first few senses ---
            glosses = []
            for s in senses[:MAX_SENSES]:
                g = (s.get("glosses") or [])
                if g:
                    glosses.append(g[0].strip())
            if not glosses and senses and senses[0].get("form_of"):
                # A pure inflected-form entry: record the relationship (already done
                # above) but it gets no entry row of its own.
                continue
            if not glosses:
                stats["no_gloss"] += 1
                continue

            key = (word, pos)
            if key not in seen_entry:
                seen_entry.add(key)
                entries.append((word, lower(word), pos, " | ".join(glosses)))
                stats["entries"] += 1

            # --- lemma -> its inflected forms, minus the tagged noise ---
            if pos not in ALLOWED_FORM_POS:
                continue
            for f in (d.get("forms") or []):
                surface = (f.get("form") or "").strip()
                tags = set(f.get("tags") or [])
                if not surface or tags & BLOCKED_TAGS:
                    continue
                if lower(surface) in FUNC_SURFACES:
                    continue
                if surface == word:
                    continue
                k = (lower(surface), lower(word))
                if k not in seen_form:
                    seen_form.add(k)
                    forms.append(k)
                    stats["forms"] += 1

            if len(entries) + len(forms) > 20000:
                flush()

    flush()

    # Rewrite form.lemma_lower -> entry.id. Forms whose lemma never produced an entry
    # row are dropped by the inner join (they are unusable for display anyway).
    # lemma_lower is NOT unique (noun "Haus" and proper-noun "Haus" both normalise to
    # "haus"), so joining directly against entry fans out and inflates the file. Map
    # each lemma_lower to a single representative id first.
    raw_forms = cur.execute("SELECT COUNT(*) FROM form").fetchone()[0]
    cur.execute(
        "CREATE TABLE lemma_ids AS "
        "SELECT lemma_lower, MIN(id) AS id FROM entry GROUP BY lemma_lower"
    )
    cur.execute("CREATE INDEX idx_lemma_ids ON lemma_ids(lemma_lower)")
    cur.execute("CREATE TABLE form_ids (surface_lower TEXT, lemma_id INTEGER)")
    cur.execute(
        "INSERT INTO form_ids SELECT DISTINCT f.surface_lower, m.id FROM form f "
        "JOIN lemma_ids m ON m.lemma_lower = f.lemma_lower"
    )
    cur.execute("DROP TABLE form")
    cur.execute("ALTER TABLE form_ids RENAME TO form")
    cur.execute("DROP TABLE lemma_ids")
    kept_forms = cur.execute("SELECT COUNT(*) FROM form").fetchone()[0]

    cur.execute("CREATE INDEX idx_form_surface ON form(surface_lower)")
    cur.execute("CREATE INDEX idx_entry_lemma ON entry(lemma_lower)")
    con.commit()
    cur.execute("VACUUM")
    con.commit()

    n_entry = cur.execute("SELECT COUNT(*) FROM entry").fetchone()[0]
    n_form = kept_forms
    print(f"  form mappings : kept {kept_forms} of {raw_forms} "
          f"({raw_forms - kept_forms} dropped: no lemma entry)")
    con.close()

    size = os.path.getsize(out) / 1e6
    # Where the bytes actually go, so size work is driven by measurement.
    con = sqlite3.connect(out)
    for label, q in [
        ("form rows", "SELECT COUNT(*) FROM form"),
        ("form surface bytes", "SELECT SUM(LENGTH(surface_lower)) FROM form"),
        ("entry rows", "SELECT COUNT(*) FROM entry"),
        ("entry lemma bytes", "SELECT SUM(LENGTH(lemma)) FROM entry"),
        ("entry gloss bytes", "SELECT SUM(LENGTH(glosses)) FROM entry"),
    ]:
        v = con.execute(q).fetchone()[0] or 0
        print(f"  {label:<20}: {v:,}")
    con.close()
    print(f"scanned {stats['lines']} lines")
    print(f"  lemma entries : {n_entry}")
    print(f"  form mappings : {n_form}  (from form_of: {stats['form_of']}, "
          f"from inflection tables: {stats['forms']})")
    print(f"  skipped (no gloss): {stats['no_gloss']}")
    print(f"-> {out}  ({size:.1f} MB)")

    # --- the cases that decide whether this is usable on a real book ---
    print("\ninflection resolution check:")
    con = sqlite3.connect(out)
    for probe, expected in [
        ("ging", "gehen"), ("lief", "laufen"), ("Häusern", "Haus"),
        ("Häuser", "Haus"), ("gegangen", "gehen"), ("fuhr", "fahren"),
    ]:
        lemmas = [r[0] for r in con.execute(
            "SELECT e.lemma FROM form f JOIN entry e ON e.id = f.lemma_id "
            "WHERE f.surface_lower = ? LIMIT 4", (lower(probe),))]
        g = con.execute(
            "SELECT glosses FROM entry WHERE lemma_lower=? LIMIT 1",
            (lower(lemmas[0] if lemmas else probe),)).fetchone()
        mark = "OK " if expected in lemmas else "MISS"
        print(f"  {mark} {probe:<10} -> {lemmas}  gloss={g[0][:40] if g else '-'}")

    # Regression guard: function words must never resolve to a content word. These
    # were "der -> CIA" / "das -> FBI" before FUNC_SURFACES existed.
    print("\nfunction-word sanity (must not point at a name/acronym):")
    for probe in ("der", "das", "die", "den", "er", "ein"):
        rows = con.execute(
            "SELECT e.lemma, e.pos, e.glosses FROM form f JOIN entry e ON e.id=f.lemma_id "
            "WHERE f.surface_lower=? LIMIT 4", (probe,)).fetchall()
        bad = [r for r in rows if r[1] in ("name", "character")]
        flag = "FAIL" if bad else "ok  "
        print(f"  {flag} {probe:<5} -> {[(r[0], r[1]) for r in rows]}")
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
