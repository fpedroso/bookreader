# TODO

Ideas and known gaps, roughly in the order they'd be worth doing. Nothing here is
committed to; it's a parking lot. See the *Design notes* and *Known gaps* sections of
[README.md](README.md) for the reasoning behind the constraints mentioned below.

## Pinyin generation in the app

Today pinyin is a **PC pass** (`tools/annotate.py`): jieba segments, CC-CEDICT supplies
readings, and the result ships as a `.pinyin.json` sidecar keyed by paragraph hash.
Generating it natively would remove the desktop step entirely and let a user import any
Chinese EPUB and read it immediately.

- Segmentation on-device: [`jieba-kmp`](https://klibs.io/project/ErolC/jieba-kmp)
  (`cn.erolc.jieba:jieba-kmp`) is a Kotlin Multiplatform port. Its own measurements put
  dictionary init at ~1-2 s on Android, which is acceptable if done once per book and
  cached.
- Readings: CC-CEDICT is already bundled as `cedict.db`, so no new asset is needed —
  word lookup, then per-character fallback, is the same chain `annotate.py` uses.
- Store the result in the same sidecar format so the reader path is unchanged, and keep
  the sidecar-keyed-by-hash design: it means a partially annotated book still renders
  correctly rather than showing wrong pinyin everywhere.
- Sizing: 113 MB of Chinese novel produced a 12 MB sidecar measured earlier; worth
  regenerating lazily per chapter rather than for the whole book up front.

## Content: a store of properly-licensed ebooks

A browseable list of free, correctly-licensed sources per language. **Licensing is the
whole point of this item**: "free to download" and "legal to redistribute" are not the
same thing.

Chinese:
- [StoryWeaver](https://storyweaver.org.in/) (Pratham Books) — **CC BY 4.0**, >100 titles
  in Chinese, EPUB downloads, safe to link *and* bundle if we ever want to.
- [Project Gutenberg](https://www.gutenberg.org/) Chinese shelf — public domain, but
  mostly Ming-dynasty vernacular (水浒传, 西遊記) and 1920s literary Chinese. Hard for a
  beginner.
- Graded readers are the right content but commercial: **Mandarin Companion** (~$8/ebook,
  DRM-free, 150/300/450 unique characters) and **Imagin8 Press** (~$10-15, 31-book
  *Journey to the West*, free companion audio on YouTube). Link them as "buy", not "free".
- ⚠️ **Do not link Chinese web-novel aggregator sites.** They are overwhelmingly pirated,
  whatever the disclaimer says.

German:
- [Project Gutenberg](https://www.gutenberg.org/) German shelf — public domain. The
  easiest titles (Der Struwwelpeter, Max und Moritz) are already the test fixtures.
- **Dino lernt Deutsch** (André Klein) — A1/A2, 12 volumes, **100% DRM-free EPUB**, paid.
  The honest answer for a beginner; there is no free A1 German EPUB.
- Free A1 German essentially does not exist in EPUB form.

Design questions to settle: do we ship a hardcoded curated list, or a remote JSON we can
update without an app release? And do we link out, or download into the library?
**Prefer a remote list** — the licensing situation per source will change and a shipped
list would go stale.

## Pagination

Currently the reader is a continuous `LazyColumn` scroll. Page-turn pagination is what a
book is supposed to feel like, and was in the original brief.

- The current layout is a `FlowRow` of per-word boxes, so pagination means measuring
  how many *rows* fit the viewport and splitting paragraph token-lists at that boundary.
- Deliberately **not** the same problem as the ruby-text road not taken: the earlier
  design worried about canonical, device-independent pages. That is not needed here —
  font size changing the page count is normal and fine. This is just viewport fitting.
- Keep it behind an interface so scroll mode stays available; dyslexic/accessibility
  readers often prefer scroll.

## Book folder / library sources

Let the user nominate a folder the app reads books from, instead of importing one file at
a time.

- `ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile`, and **take a persistable URI permission**
  (`takePersistableUriPermission`) or access is lost on reboot — a classic trap.
- Needs a rescan on app resume, since the folder can change behind the app's back.
- Interacts with the store above: a folder + curated links + one-off imports is three
  sources for one library, so the library model should probably become a list of *sources*
  rather than a flat list of books.
- Pinyin sidecars live next to the EPUB on the desktop, so a folder scan should pick up
  `<book>.pinyin.json` automatically when present.

## Configurable pinyin and translation

Right now these are effectively fixed: pinyin is on/off with one switch, and the
dictionary is chosen by `dc:language`. Worth making explicit settings:

- pinyin: on/off, and possibly *thresholded* — only show readings for characters above a
  given frequency, which is how learners graduate off pinyin without turning it off
  wholesale. Requires a per-character frequency table.
- display: font size, line spacing, dark mode, traditional vs simplified preference.
- dictionary: which target language to gloss into, and per-book override.
- the LLM gloss (below) needs its own config: model, whether it's enabled at all, and
  where the key lives.

## Dictionary result ordering

For function words the popup headline can be misleading. Tapping German `mein` heads with
`→ ich · pron`, because the token's `form_of` mapping is inserted before its own entry —
so it reads as "mein means ich" when the primary sense is the determiner **"my"**. All
three senses *are* listed correctly underneath; only the order is wrong.

Results should be ranked rather than ordered by lookup step:

1. the token's own entry with real glosses — `mein` → "my"
2. form-resolved lemmas — `Häuser` → `Haus` → "house"
3. entries whose gloss is *only* a pointer — `Häuser` → "plural of Haus"

Careful: this must not regress `Häuser` or `ging`, where the form-resolved lemma is the
useful answer and the token's own entry is a bare pointer. So "own entry first" is not the
rule either — the pointer-gloss distinction is what makes it work.

## Duplicated glosses in the German data

`lustige` renders "inflection of lustig:" three times, because the Kaikki/Wiktextract
source repeats the same gloss in multiple senses for that form. `build_german.py` should
de-duplicate glosses per entry (case-insensitively, after trimming). Cheap fix, and it
affects every inflected adjective, so it is more visible than it sounds.

## LLM gloss for sentences you're stuck on

Not in the original list but the natural companion to tap-a-word, and it was in the
original brief. Per-word lookup answers "what does this word mean"; it does not answer
"what is this sentence doing" — word order, separable verbs, particle usage.

- On demand per sentence, prompted as a **tutor** (literal gloss + structure) rather than
  as a translation engine. Fluent output is the enemy here: it hides the structure the
  learner is trying to see.
- Cache by block hash, same as the pinyin sidecar, so re-reading costs nothing.
- Needs an API key, so it must be strictly opt-in and off by default.

## Smaller known gaps

Carried from README so they aren't lost:

- Hyphenated German compounds (`Guck-in-die-Luft`, `Zappel-Philipp`) arrive as one token
  and do not resolve. German is head-final, so retrying the last segment would fix most.
- German asset is 113 MB on disk (~40 MB in the APK). The inflection table is the lever.
- No bookmarks, no highlights, no Anki export.
- Only EPUB3 XHTML text: no fixed-layout, no PDF, no cover thumbnails.
- Library metadata is a JSON manifest; it'll want a real database eventually.
