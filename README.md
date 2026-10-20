# bookreader

An Android EPUB reader for language learners: read any EPUB, **tap a word to see that word in
English**, or tap the chip at the end of a sentence to see the whole sentence — on device, offline.

Built for reading German books at a level where you can mostly understand them, and checking only
the words and sentences you can't.

## What it does

1. **Import** an EPUB (single-file picker). The file is copied into app storage and recorded
   in a small JSON manifest (`Store.kt`).
2. **Read** it as prose. Blocks come from `EpubParser` in `Epub.kt` — OPF spine order, and a
   stack of block collectors so a container's own text is a block in its own right. The
   reading view is continuous scrolling by design: there is no pagination.
3. **Sentences.** For German, each paragraph is split into sentences (`Sentences.kt`) and each
   one starts on its own line, 12dp apart (less than the 20dp between paragraphs, so a paragraph
   break reads as the bigger one). Nothing is drawn behind the text: the layout is the only thing
   defining the unit, and a small **EN** chip sits inline at the end of each sentence as its
   action.
4. **Tap a word** for that word's translation alone (`Words.kt` finds the word under the tap). The
   modal shows the sentence the word sits in — one word out of context is exactly what translates
   ambiguously — and offers **Translate sentence** to move up to the whole unit. Tapping a
   sentence's **EN** chip translates the whole sentence, and **Translate more** there extends the
   unit by one sentence at a time, which is the escape hatch when a split landed in the wrong
   place.
5. **Per-block language.** A block's language comes from the document's own `lang`/`xml:lang`
   (the nearest ancestor first, then `<html lang>`), then the book's `dc:language`, then how
   the book was imported — and only if all of those are silent, ML Kit Language
   Identification. The book-level `dc:language` is not trusted on its own: a German novel's
   front matter is routinely English, and translating it as German returns the original words
   with nothing to say why.
6. **Translate.** On device with ML Kit (`Translate.kt`), into a modal (`TranslationDialog`) that
   names the pair, e.g. `German → English`. If the text is already English it says so and never
   calls the model. No account, no cost, and the text never leaves the device — the ~30 MB model
   downloads once per language pair, and the dialog says so while that happens.
7. **Cache.** Translations are stored per book in `translations.json` beside the book, keyed by
   a hash of the source text itself — never by position, so a sentence cannot inherit another
   sentence's translation when the splitter changes, and re-tapping is free. A word and a sentence
   share the one map; different text cannot collide.
8. **Settings** (`Engines.kt`, `Models.kt`) choose the translation engine and manage its model:
   per-pack state, download with progress, and delete. The engine is part of every cache key, and
   the translation modal names the engine that answered, because two engines fail differently and
   the reader is the one weighing the output.

**Opening a book never blocks on any per-language preparation.** Any EPUB in any language
imports and renders as plain text. Translation is offered for **German books only** for now — the
gate is `translationOffered` in `Translate.kt`, one line to widen once the tap has been read on
more than one language. A row with no translation offered renders inert: no chip, no word tap.

**The engine is a setting, and its model is managed in Settings.** ML Kit is the default; Qwen3
1.7B and Qwen3 4B are listed with what they cost and can be downloaded and deleted from the same
screen. An engine whose model is missing, or which this build cannot run, says so instead of being
selectable — a setting that turns every tap into a failure is worse than no setting.

**The app's only network use is fetching a model you asked for.** Translation itself is on-device
and nothing you read leaves the phone; `INTERNET` exists solely so Settings can download a 1.0 GB
or 2.5 GB model file. The models are ordinary files in the app's external storage, deleted from
Settings — or with the app.

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

**Each sentence gets its own line, and nothing is drawn behind it.** One composable per sentence,
stacked 12dp apart (less than the 20dp between paragraphs, so a paragraph break reads as the bigger
one). The layout IS the unit — the page had been a grid of frames when sentences were outlined, and a
tint that replaced it was removed again in favour of a cleaner page. What marks a unit as
translatable is now the **EN** chip at the end of it. The price of the layout is a ragged right edge,
which is the point for a reader comparing sentence structures.

**A word tap needs offsets; a sentence tap must not use them.** One composable per word throws away
the real inter-word spacing, so prose stays a single flowing `Text` and the word comes from
`getOffsetForPosition` plus `Words.at` — an approximation only inside whitespace, where a tap that
resolves to no word at all asks for the unit rather than doing nothing. The unit itself stays exact
by construction: one composable per sentence, with the chip appended to that composable.

**The chip is inline content, not a sibling.** `appendInlineContent` lays it out inside the text, so
it sits at the end of the *last line* the sentence occupies and wraps with it. A `Row` or `FlowRow`
sibling cannot do that: a sentence that fills the width measures as a full-width item, so the chip
would drop onto a line of its own and read as a stray bullet. It is found by geometry (the tap's
offset, or the placeholder's bounding box) rather than by its own `clickable`, because a nested
clickable inside inline content competes with the text's tap handler for the same gesture.

It spells **EN** rather than drawing a glyph on purpose: `material-icons-extended` is a ~5 MB
dependency this app has no other use for and the core icon set has no translate symbol, and naming
the target says what the tap will produce rather than merely that something language-shaped will
happen.

**Translation runs on device.** ML Kit's models (~30 MB per pair) download on first use and
inference is offline and free, so translation can be lazy and automatic with no per-sentence
cost decision. The price is that it translates rather than explains.

**The engine has to be part of the cache key.** Two engines return different translations for the
same sentence, so a cache keyed on the text alone would hand ML Kit's answer back after switching
to a local model — and that reads as the new model being bad, not as the cache being wrong. The
key is therefore `<engine>·<text>`, plus the surrounding sentence for an engine that reads context
(a word in two sentences really is two translations; ML Kit's API only ever sees the text, so for
it the context would only duplicate entries). `EnginesTest` pins all of that.

**No Readium.** `readium-streamer` 3.4.0 requires `compileSdk 37` plus core library
desugaring (3.2.0+ requires 36). Version 3.1.2 would work — it declares `minCompileSdk=1` —
but the spec coverage was not worth the dependency for plain EPUB3.

**`buildToolsVersion` is pinned** to 35.0.1 because AGP 8.6.1 defaults to 34.0.0, which is
often not installed, and without `cmdline-tools` AGP cannot fetch it.

## Known gaps

- **The Qwen engines can be downloaded and deleted but not used yet.** Running them needs the
  LiteRT-LM runtime, and every published `litertlm-android` is built with a newer Kotlin than this
  project's 2.0.21 pin (0.16.1 is metadata 2.3.0, 0.17+ is 2.4.0; class-file major 65, against JVM
  target 17). Adopting it therefore means a toolchain bump — Kotlin at minimum, likely AGP and
  compileSdk with it — which is the same tower that ruled out Readium 3.4.0. Undecided, not done.
- **A word's translation is not a dictionary entry.** ML Kit returns one rendering of the form you
  tapped — no lemma, no part of speech, no alternative senses — so an inflected or polysemous word
  can come back with a sense that does not fit the sentence. The sentence the word sits in is shown
  under it for exactly that reason.
- Translation is offered for German books only, and only for a block that is not already English.
  Widening it means checking the word splitter against a language that does not separate words with
  spaces, which it cannot handle: `Words.kt` treats a run of Han as a single token.
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
