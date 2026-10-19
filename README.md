# bookreader

An Android EPUB reader for language learners: **pinyin above the characters**, toggleable,
and **tap any word for its meaning** — no English translation sitting under every line.

Built for reading Chinese and German books at a level where you can mostly understand
them, and looking up only what you don't.

![Library screen](docs/library.png)

## How it works

```
   Optional (PC, once per book)                Phone
   ───────────────────────────                 ─────
   EPUB ─▶ tools/annotate.py ─▶ book.pinyin.json ─┐
            │  jieba segmentation                 │  (wins when present)
            │  CC-CEDICT readings                 ▼
            └  pypinyin fallback         EpubParser ─▶ blocks
                                         PinyinGen  ─▶ word tokens  ← or generated on device
                                         WordFlow   ─▶ tappable words
                                         Cedict     ─▶ definitions on tap
```

**Reading is language-agnostic; the language handlers are additions on top.** Any EPUB in
any language imports and renders as plain text, and nothing about opening a book depends on
segmentation, a dictionary or a sidecar.

- **Chinese** — readings above the characters, tap a word for its meaning. Pinyin is
  generated *in the app* (`PinyinGen`) from the bundled CC-CEDICT, so importing the EPUB
  alone is enough. A PC sidecar is still supported and takes precedence, but is no longer
  required. Generation is lazy and windowed around the reading position, off the main
  thread: the book renders plain immediately and readings drop in, so slow or failed
  generation can never block reading. Results are cached as `pinyin.generated.json` in the
  same sidecar format, so a second open generates nothing.
- **German** — inflection-resolving tap dictionary (`de.db`). No readings, so no toggle.
- **Anything else** — plain reading.

`PinyinGen` segments by **maximal matching over CC-CEDICT** rather than jieba: the Kotlin
port (`jieba-kmp`) is built with Kotlin 2.3.0 metadata and this project is pinned to 2.0.21
by AGP 8.6.1 / compileSdk 35, so the compiler rejects it outright. The readings barely
differ, because `annotate.py` already falls back to per-character readings whenever jieba
groups a phrase that is not itself a headword, and CC-CEDICT covers the fixtures fully.

### The one coupling that matters

The sidecar is keyed by a **hash of each paragraph's text** — not by position:

```json
{ "v": 1, "lang": "zh", "blocks": { "8c23255f8b5af2d8": [["我","wǒ","cedict"], ...] } }
```

The hash strips all whitespace, so the Python extractor and the Kotlin extractor
cannot drift out of sync over formatting differences. If they ever do disagree, the
failure is *"this line shows no pinyin"* rather than *"every line shows the wrong
pinyin"*. `BlockHashAgreementTest` asserts agreement across both extractors.

## Build

Requirements: JDK 17+ (Temurin 21 used here), Android SDK with **platform android-35**
and **build-tools 35.0.1**, and an Android device or emulator.

```bash
# 1. Build the dictionary assets first — they are not vendored into the repo.
cd tools
python build_cedict.py            # -> ../app-android/app/src/main/assets/cedict.db
curl -sSL -o kaikki-de.jsonl.gz \
  "https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz"
python build_german.py kaikki-de.jsonl.gz ../app-android/app/src/main/assets/de.db

# 2. Build the APK.
cd ../app-android
./gradlew assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
```

On Windows, `./build.sh` wraps this and also sets `JAVA_HOME` (bash's `PATH` does not
reach the `.bat` launcher):

```bash
./build.sh assembleDebug
./build.sh testDebugUnitTest      # extractor-agreement test
```

Test results are written to `app/build/test-results/testDebugUnitTest/*.xml`; Gradle
filters the console, so read the XML for the numbers.

Install over adb, then push the fixtures:

```bash
adb install -r app-android/app/build/outputs/apk/debug/app-debug.apk
adb push hsk1.epub hsk1.pinyin.json /sdcard/Download/
```

Import both files in **one** multi-select gesture — the picker pairs an EPUB with its
`.pinyin.json` by filename. The sidecar is optional: import the EPUB on its own and the app
generates the readings itself.

## Annotating a book

```bash
cd tools
pip install pypinyin jieba          # or: uv pip install pypinyin jieba
python build_cedict.py
python annotate.py mybook.epub cedict.txt mybook.pinyin.json
```

`gen_hsk1.py` authors a small HSK1-level fixture from the 150-word vocabulary list —
useful for testing without needing a real graded reader.

## Design notes

These were measured on real books, not assumed. They are the reason the code looks
the way it does.

**CC-CEDICT is the reading source; pypinyin is a last resort.** pypinyin never applies
the neutral tone (轻声), so it is wrong on 16 of 24 common words — 爸爸 → `bà bà`
instead of `bà ba`, and likewise 谢谢, 朋友, 时候, 喜欢, 认识, 东西. These are the first
words a beginner meets, so the error lands exactly where it hurts.
(`tools/check_pypinyin_quality.py` reproduces that table, so the claim can be re-checked
against a newer pypinyin release.) CC-CEDICT encodes
the neutral tone explicitly (`ba4 ba5`; tone 5 = neutral) and is keyed on both scripts,
which also fixes traditional-character readings pypinyin gets wrong (銀行 → `yín xíng`).

**Lookup goes word → character → give up.** jieba groups words into phrases that are
not dictionary headwords (不敢, 兩個, 一座, 一日 — "numeral + measure word" combinations
in particular). Falling back to per-character readings took CC-CEDICT coverage from
**74.4% to 100.0%** on a 367k-token novel (fallbacks: 93,868 → 49). Each token records
which source produced its reading, so a weak reading is visible in the UI rather than
plausible.

**German needs inflection resolution, and Wiktionary's paradigm tables lie.** German is
space-separated, so no segmentation is needed — but *ging* has to resolve to *gehen*, so
`de.db` carries 357k lemmas with English glosses plus 1.34M surface→lemma mappings. Two
traps cost real debugging, and **both produced answers that looked like successes**:

- **Articles, pronouns, determiners and numerals share cross-linking paradigm tables.**
  Building surface→lemma from every inflection table mapped `er` ("he") to `du` ("you")
  and `die` to `der`. Mappings are now built only for `noun`/`verb`/`adj`/`adv`/`name`.
- **A name entry's head template renders *with* an article**, so `der` appeared among the
  `forms` of an acronym entry — mapping the definite article to that acronym
  (`der` → `CIA`, `das` → `FBI`). Function words are now blocked as mapping *sources*;
  tapping one resolves through its own dictionary entry instead.

Neither was visible in the coverage number: coverage was 95% *before and after* the `er`
bug, because `er` resolved — just to the wrong word. They were only visible by printing
the most frequent hits for a real book and reading them. `build_german.py` now prints a
function-word sanity table so the second class cannot silently return.

Measured on *Der Struwwelpeter*, with Project Gutenberg's English licence boilerplate
excluded (it otherwise dominates the token counts): **91.9% of distinct tokens and 95.9%
weighted by frequency**. The residue is proper nouns (`Struwwelpeter`, `Paulinchen`) and
publisher names. `tools/probe_german_lookup.py` reproduces that measurement using the
app's exact SQL, so the number is about the shipped lookup path rather than a
convenient reimplementation.

**Results are ranked by usefulness, not by lookup step.** The form-table hit used to come
first always, which reads wrong for a function word: tapping `mein` led with `→ ich` (its
`form_of` pointer) when the primary sense is the determiner "my". There are three buckets
now:

1. the token's own entry, with real meaning — `mein` → det "my"
2. the lemma a form resolves to — `Häuser` → `Haus` → "house"
3. the token's own entry, but pointer-only — `Häuser` → "plural of Haus"

"Own entry first" is *not* the rule either: `Häuser` and `ging` have their own entries too,
and theirs are bare pointers, so bucket 2 has to win for them. `isPointerGloss` draws that
line, and the source mixes both cases inside one gloss — `nominative/accusative neuter
singular of der: the` carries its meaning *after* the colon so it counts as real (otherwise
tapping `das` regresses to leading with `der`), while a trailing colon
(`inflection of lustig:`) does not. `GermanGlossTest` covers both. Duplicate glosses are
collapsed at build time as well: the source repeats the same sense across senses, so
`lustige` used to list "inflection of lustig:" three times.

**Each word is its own box; no ruby-text library.** The rendering is one small `Column`
per word — pinyin above, the word tappable below. Word-level tap targets come out exact
with no coordinate arithmetic, and pinyin alignment is correct by construction.
The ruby libraries were rejected on evidence: Furiganable documents `onTextLayout` as
unsupported (it swaps in an `AndroidTextView` in some paths), which removes the only way
to hit-test a tapped word by text offset; `androidx.text`'s `RubySpan` is still beta and
requires `compileSdk 37` + AGP 9.1.

**No Readium.** `readium-streamer` 3.4.0 requires `compileSdk 37` plus core library
desugaring (3.2.0+ requires 36). Version 3.1.2 would work — it declares
`minCompileSdk=1` — but the spec coverage was not worth the dependency for plain EPUB3.
Check that field before adopting any AAR:

```bash
unzip -p some.aar META-INF/com/android/build/gradle/aar-metadata.properties
```

**`buildToolsVersion` is pinned** to 35.0.1 because AGP 8.6.1 defaults to 34.0.0, which
is often not installed, and without `cmdline-tools` AGP cannot fetch it.

## Data sources and attribution

- **[CC-CEDICT](https://cc-cedict.org/)** — Chinese–English dictionary, **CC BY-SA 4.0**.
  Used for readings and definitions. It is **not** committed to this repo; `tools/build_cedict.py`
  builds the SQLite asset from the upstream release. Pinyin sidecars (§ above) are derived
  from CC-CEDICT readings and should be treated as carrying the same licence.
- **[Kaikki / Wiktextract](https://kaikki.org/dictionary/German/)** — German dictionary,
  **CC BY-SA 4.0**, extracted from the *English* Wiktionary so the glosses are English.
  Also not committed; `tools/build_german.py` builds `de.db` from the upstream JSONL.
- **[jieba](https://github.com/fxsjy/jieba)** — Chinese word segmentation (MIT).
- **[pypinyin](https://github.com/mozillazg/python-pinyin)** — fallback readings only (MIT).
- **[Project Gutenberg](https://www.gutenberg.org/)** — the German test books used during
  development are public domain and are not committed here.

## Known gaps

- **Hyphenated German compounds don't resolve.** `Guck-in-die-Luft` arrives as one token
  (correct), but the whole compound isn't a dictionary headword, so tapping it yields
  nothing. German is head-final, so trying the last segment (`Luft`) would fix most cases.
- The German asset is large (113 MB on disk, ~40 MB compressed in the APK). Pruning the
  inflection table is the obvious lever; see `build_german.py`'s size report.
- Pinyin generation is a PC pass, not in-app.
- No LLM gloss, no Anki export, no bookmarks; library metadata is a JSON manifest.
- Only EPUB3 XHTML text is parsed — no fixed-layout, no PDF, no cover thumbnails.

## Licence

MIT — see [LICENSE](LICENSE).
