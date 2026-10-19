# bookreader

An Android EPUB reader for language learners: read any EPUB, and **tap a sentence to see it
translated into English** — on device, offline.

Built for reading German books at a level where you can mostly understand them, and checking
only the sentences you can't.

## What it does

1. **Import** an EPUB (single-file picker). The file is copied into app storage and recorded
   in a small JSON manifest (`Store.kt`).
2. **Read** it as prose. Blocks come from `EpubParser` in `Epub.kt` — OPF spine order, and a
   stack of block collectors so a container's own text is a block in its own right. The
   reading view is continuous scrolling by design: there is no pagination.
3. **Sentences.** For German, each paragraph is split into sentences (`Sentences.kt`). Each
   sentence starts on its own line, inside a subtle tinted band (`surfaceContainer`, 6dp of padding
   inside it, 12dp between sentences), so the unit of translation is visible at a glance — and a tap
   translates exactly that sentence. **Translate more** in the modal extends the unit by one sentence
   at a time, which is the escape hatch when a split landed in the wrong place.
4. **Per-block language.** A block's language comes from the document's own `lang`/`xml:lang`
   (the nearest ancestor first, then `<html lang>`), then the book's `dc:language`, then how
   the book was imported — and only if all of those are silent, ML Kit Language
   Identification. The book-level `dc:language` is not trusted on its own: a German novel's
   front matter is routinely English, and translating it as German returns the original words
   with nothing to say why.
5. **Translate.** Tapping a sentence translates it on device with ML Kit (`Translate.kt`) and
   opens the result in a modal (`TranslationDialog`) that names the pair, e.g.
   `German → English`. If the sentence is already English it says so and never calls the
   model. No account, no cost, and the text never leaves the device — the ~30 MB model
   downloads once per language pair, and the dialog says so while that happens.
6. **Cache.** Translations are stored per book in `translations.json` beside the book, keyed by
   a hash of the source text itself — never by position, so a sentence cannot inherit another
   sentence's translation when the splitter changes, and re-tapping is free.

**Opening a book never blocks on any per-language preparation.** Any EPUB in any language
imports and renders as plain text; German just adds sentence splitting and translation. ML
Kit supports many other pairs, but the splitter and the UI are built and tested for German.

## Build

Requirements: JDK 17+ (Temurin 21 used here), Android SDK with **platform android-35** and
**build-tools 35.0.1**.

```bash
./build.sh testDebugUnitTest assembleDebug
```

On Windows, `./build.sh` wraps the Gradle wrapper and sets `JAVA_HOME` (bash's `PATH` does
not reach the `.bat` launcher). The Gradle console is filtered, so read the numbers from
`app-android/app/build/test-results/testDebugUnitTest/*.xml`.

Unit tests run under the JVM only (kxml2 supplies the XML parser), so no device is needed.

Install over adb:

```bash
adb install -r app-android/app/build/outputs/apk/debug/app-debug.apk
```

## Design notes

Measured on real books, not assumed.

**The splitter relies on structure alone.** `Sentences.kt` has no abbreviation list and no
fragment merging. What still keeps `564. Auflage` and `H. Müller` together is structural:
the digit before the dot, the single-capital initial, the initial chain `H.P.`, the case of
the following token, and the comma/colon rule. The cost is real and accepted — `Dr. Müller` and
`Mrs. Smith` split, because "Capital. Capital" is lexically identical to a real boundary
(`Er ging. Müller kam.`), and a bare `Pfui!` is now its own unit that ML Kit may translate badly
without its referent. A hardcoded list was rejected in exchange for rules that need no
maintenance, and a "short capitalised word before the dot" rule was rejected too: it also
suppresses the split in `Er traf Hans. Dann ging er.`

**Each sentence gets its own line, in a subtle band.** One composable per sentence, stacked, each
behind a `surfaceContainer` tint with 6dp of padding inside it and 12dp between sentences (still
less than the 20dp between paragraphs, so a paragraph break reads as the bigger one). That is what
marks the unit — the page had become a grid of frames when sentences were outlined instead.

The tint is only viable because of this layout, which is worth knowing before "simplifying" it back
into flowing prose: applied to a range of continuous text it merges two sentences sharing a line
into one continuous band, and paints per line segment, so a sentence across four lines reads as four
stripes. The price of the layout is a ragged right edge, which is the point for a reader comparing
sentence structures, plus an exact tap target: a composable that *is* the sentence cannot be
mis-hit, where resolving a tap by nearest character offset was an approximation.

**Translation runs on device.** ML Kit's models (~30 MB per pair) download on first use and
inference is offline and free, so translation can be lazy and automatic with no per-sentence
cost decision. The price is that it translates rather than explains.

**No Readium.** `readium-streamer` 3.4.0 requires `compileSdk 37` plus core library
desugaring (3.2.0+ requires 36). Version 3.1.2 would work — it declares `minCompileSdk=1` —
but the spec coverage was not worth the dependency for plain EPUB3.

**`buildToolsVersion` is pinned** to 35.0.1 because AGP 8.6.1 defaults to 34.0.0, which is
often not installed, and without `cmdline-tools` AGP cannot fetch it.

## Known gaps

- The sentence splitter is German-focused; other Latin-script languages fall back to a plain
  punctuation split. It is structure-only: no abbreviation list and no fragment merging, so a
  title before a capitalised surname (`Mrs. Smith`) splits, and a bare `Pfui!` is its own unit.
- Hyphenated German compounds (`Baden-Württemberg`, `Nord-Süd`) arrive as one token and translate
  as one word.
- Translation is fixed German → English: no target-language setting and no per-book override.
- No bookmarks, no highlights, no Anki export.
- Only EPUB3 XHTML text is parsed — no fixed-layout, no PDF, no cover thumbnails.
- Library metadata is a JSON manifest; it will want a real database eventually.

## Licence

MIT — see [LICENSE](LICENSE).
