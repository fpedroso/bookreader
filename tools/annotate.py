# -*- coding: utf-8 -*-
"""Annotate a Chinese EPUB with per-word pinyin -> sidecar JSON.

Output is keyed by PARAGRAPH HASH, not by position, so the Python block
extraction here and the Readium block extraction in the Kotlin app cannot
drift out of sync. Repeated paragraphs dedupe for free.

Readings come from CC-CEDICT first (because it encodes the neutral tone that
pypinyin drops: 爸爸 ba4 ba5 vs pypinyin's wrong 'bà bà'), pypinyin only for
out-of-vocabulary words. Every reading records its source so wrong ones are
visible rather than plausible.

Usage:  annotate.py <book.epub> <cedict.txt> <out.json>
"""
import collections
import hashlib
import html
import json
import os
import re
import sys
import zipfile
from xml.etree import ElementTree as ET

import jieba
from pypinyin import lazy_pinyin, Style

# ---------------------------------------------------------------- CC-CEDICT

class Cedict:
    """Simplified + traditional -> numbered pinyin, first sense wins."""

    LINE = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]+)\]")

    def __init__(self, path: str) -> None:
        self.simp: dict[str, str] = {}
        self.trad: dict[str, str] = {}
        self.defs: dict[str, str] = {}
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                m = self.LINE.match(line)
                if not m:
                    continue
                t, s, py = m.group(1), m.group(2), m.group(3)
                self.simp.setdefault(s, py)
                self.trad.setdefault(t, py)
                d = re.search(r"/(.*)/", line)
                if d:
                    self.defs.setdefault(s, d.group(1).replace("/", " | "))

    def lookup(self, word: str) -> str | None:
        return self.simp.get(word) or self.trad.get(word)

    def define(self, word: str) -> str | None:
        return self.defs.get(word)


def numbered_to_toned(py: str) -> str:
    """CC-CEDICT numbered pinyin -> tone marks. Kept local so this tool has no
    dependency on the app-side conversion."""
    tones = {"a": "āáǎà", "e": "ēéěè", "i": "īíǐì",
             "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ"}
    out = []
    for syl in py.split():
        syl = syl.replace("u:", "ü")
        m = re.match(r"^([A-Za-zü]+)(\d)$", syl)
        if not m:
            out.append(syl)
            continue
        base, tone = m.group(1), int(m.group(2))
        if tone >= 5:
            out.append(base)
            continue
        low = base.lower()
        idx = -1
        for v in ("a", "o", "e"):
            if v in low:
                idx = low.index(v)
                break
        if idx < 0:
            cand = [i for i, c in enumerate(low) if c in "aeiouü"]
            if not cand:
                out.append(base)
                continue
            idx = cand[-1]
        ch = base[idx]
        # Proper nouns arrive capitalised (Bei3 jing1); look the tone up
        # case-insensitively and restore the capital on the marked vowel.
        marked = tones[ch.lower()][tone - 1]
        if ch.isupper():
            marked = marked.upper()
        out.append(base[:idx] + marked + base[idx + 1:])
    return " ".join(out)


# ---------------------------------------------------------------- EPUB -> blocks

BLOCK_TAGS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "div", "td"}
SKIP_TAGS = {"script", "style", "head", "title", "rt", "rp"}

# Containers whose *own* text is a block. `body` matters because converter and
# ebook-splitter output frequently dumps prose as bare text nodes inside body with empty
# <p> tags as spacers - a real Harry Potter EPUB is exactly that shape, and without this
# every heading was extracted and all 530 KB of prose was silently dropped.
CONTAINERS = BLOCK_TAGS | {"body"}

# Sentinel for a real line break, swapped for "\n" only after whitespace is collapsed.
# It must be non-whitespace so the collapsing pass cannot eat it, and it must be gone from
# the final text because the block hash strips whitespace - which is exactly what keeps
# these breaks invisible to the hash and the sidecars compatible with the Kotlin extractor.
BR = "\u241e"

# NBSP is whitespace for our purposes but not for Kotlin's Char.isWhitespace.
WS_RE = re.compile(r"[\s\u00a0]+")
BREAK_PAD = re.compile(r" *\n *")
BREAK_RUN = re.compile(r"\n{2,}")


def local_name(tag) -> str:
    """ElementTree gives '{ns}name' or 'prefix:name'; take the local part.

    Kotlin's XmlPullParser reports the qualified name, so BOTH sides must normalise or
    `dc:language` never matches 'language' - which is why language detection silently
    failed for every real EPUB.
    """
    if not isinstance(tag, str):
        return ""
    return tag.split("}")[-1].split(":")[-1].lower()


def strip_tags(el) -> str:
    """Full text of a subtree, skipping script/style/ruby-annotation subtrees."""

    def walk(node):
        tag = local_name(node.tag)
        if tag in SKIP_TAGS:
            return
        # A <br> is a real line break *inside* one block: verse keeps a whole stanza in a
        # single <p> with <br/> between the lines, so without this every stanza flattens.
        if tag == "br":
            parts.append(BR)
        if node.text:
            parts.append(node.text)
        for child in node:
            walk(child)
            if child.tail:
                parts.append(child.tail)

    parts = []
    walk(el)
    return html.unescape("".join(parts))


def clean(raw: str) -> str:
    """Collapse whitespace, keep only genuine <br> breaks, as "\\n"."""
    t = WS_RE.sub(" ", raw).strip()
    t = BREAK_PAD.sub("\n", t.replace(BR, "\n"))
    return BREAK_RUN.sub("\n", t).strip()


def blocks_from_xhtml(data: bytes) -> list[str]:
    """Blocks in document order, mirroring EpubParser.parseChapter.

    A container emits its OWN text as a block, and that text is flushed when a nested
    container begins - so `<body> A <p></p> B </body>` yields "A" then "B", matching the
    Kotlin parser block for block. The block hash is shared between the two extractors, so
    any divergence here degrades sidecar lookups.
    """
    root = ET.fromstring(data)
    out: list[str] = []

    def flush(buf) -> None:
        t = clean("".join(buf))
        if t:
            out.append(t)

    def walk(el) -> None:
        tag = local_name(el.tag)
        if tag in SKIP_TAGS:
            return
        container = tag in CONTAINERS
        buf = [el.text or ""] if container else []
        for c in el:
            if local_name(c.tag) in CONTAINERS:
                if container:
                    flush(buf)
                    buf = [c.tail or ""]
                walk(c)
            else:
                # Inline child: its text belongs to this block, not to a block of its own.
                if container:
                    buf.append("".join(c.itertext()))
                    buf.append(c.tail or "")
                else:
                    walk(c)
        if container:
            flush(buf)

    walk(root)
    return out


def epub_blocks(epub_path: str) -> list[str]:
    """Spine-ordered blocks. XHTML only (EPUB3 text content)."""
    with zipfile.ZipFile(epub_path) as z:
        names = z.namelist()
        opf = next((n for n in names if n.endswith(".opf")), None)
        if not opf:
            raise SystemExit("no OPF found - not an EPUB?")
        base = os.path.dirname(opf)
        root = ET.fromstring(z.read(opf))
        man = {}
        for el in root.iter():
            if el.tag.split("}")[-1] == "item":
                man[el.get("id")] = el.get("href")
        order = []
        for el in root.iter():
            if el.tag.split("}")[-1] == "itemref":
                href = man.get(el.get("idref"))
                if href:
                    order.append(os.path.normpath(os.path.join(base, href)).replace("\\", "/"))
        blocks = []
        for href in order:
            if href not in names:
                continue
            if not re.search(r"\.x?html?$", href, re.I):
                continue
            try:
                blocks.extend(blocks_from_xhtml(z.read(href)))
            except ET.ParseError as e:
                print(f"  ! parse fail {href}: {e}", file=sys.stderr)
        return blocks


# ---------------------------------------------------------------- annotate

CJK = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf]")


def is_word(tok: str) -> bool:
    return bool(CJK.search(tok))


def annotate_block(text: str, cedict: Cedict) -> list[list[str]] | None:
    """-> [[word, pinyin, source], ...] or None if no Chinese at all."""
    words = [w for w in jieba.cut(text) if w.strip()]
    if not any(is_word(w) for w in words):
        return None
    out = []
    for w in words:
        if not is_word(w):
            # Keep punctuation and Latin runs. Dropping them silently deletes
            # every 、，。 from the rendered book, which also makes the words
            # either side of a sentence boundary look run together.
            out.append([w, "", "none"])
            continue
        py = cedict.lookup(w)
        if py:
            out.append([w, numbered_to_toned(py), "cedict"])
            continue
        # jieba regularly groups words into phrases that are not CC-CEDICT
        # headwords (不敢, 兩個, 一座, 一日). Their characters almost always
        # ARE headwords, and CC-CEDICT readings beat pypinyin's (which drops
        # the neutral tone and misses traditional-only forms).
        per_char = [cedict.lookup(c) for c in w if is_word(c)]
        if per_char and all(per_char):
            out.append([w, " ".join(numbered_to_toned(p) for p in per_char), "cedict-chars"])
        else:
            out.append([w, " ".join(lazy_pinyin(w, style=Style.TONE)), "pypinyin"])
    return out or None


def key(text: str) -> str:
    norm = re.sub(r"\s+", "", text)
    return hashlib.sha1(norm.encode("utf-8")).hexdigest()[:16]


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    epub, cedict_path, out_path = sys.argv[1:4]
    jieba.setLogLevel(60)

    cedict = Cedict(cedict_path)
    print(f"cedict entries: {len(cedict.simp)} simp / {len(cedict.trad)} trad")

    blocks = epub_blocks(epub)
    print(f"blocks extracted: {len(blocks)}")

    table, n_cjk, src_counts = {}, 0, collections.Counter()
    for b in blocks:
        ann = annotate_block(b, cedict)
        if not ann:
            continue
        n_cjk += 1
        for _, _, s in ann:
            src_counts[s] += 1
        table[key(b)] = ann

    total = sum(src_counts.values())
    # Coverage is measured over Han tokens only; "none" rows are punctuation.
    # "cedict-chars" is still CC-CEDICT data (per-character readings for a
    # phrase that is not itself a headword), so it counts toward coverage.
    han = src_counts["cedict"] + src_counts["cedict-chars"] + src_counts["pypinyin"]
    from_cedict = src_counts["cedict"] + src_counts["cedict-chars"]
    cov = 100.0 * from_cedict / han if han else 0.0
    payload = {
        "v": 1,
        "lang": "zh",
        "source": os.path.basename(epub),
        "blocks": table,
    }
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False)

    size = os.path.getsize(out_path) / 1024
    print(f"annotated blocks: {n_cjk} / {len(blocks)}")
    print(f"tokens: {total} (han={han}, punctuation/other={src_counts['none']})")
    print(f"  cedict-word={src_counts['cedict']}  cedict-chars={src_counts['cedict-chars']}"
          f"  => CC-CEDICT coverage {cov:.1f}%")
    print(f"  pypinyin-fallback={src_counts['pypinyin']} ({100.0 - cov:.1f}%)")
    print(f"-> {out_path}  ({size:.0f} KB)")

    if n_cjk:
        sample = next(iter(table.values()))
        print("sample:", " ".join(f"{w}[{p}]" for w, p, _ in sample[:14]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
