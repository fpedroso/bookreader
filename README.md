# bookreader

An Android EPUB reader for language learners: read any EPUB, and **tap a sentence to see it
translated into English** — on device, offline.

Built for reading German books at a level where you can mostly understand them, and checking
only the sentences you can't.

![Library screen](docs/library.png)

## What it does

1. **Import** an EPUB (single-file picker). The file is copied into app storage and recorded
   in a small JSON manifest (`Store.kt`).
2. **Read** it as prose. Blocks come from `EpubParser` in `Epub.kt` — OPF spine order, and a
   stack of block collectors so a container's own text is a block in its own right.
3. **Sentences.** For German, each paragraph is split into sentences (`Sentences.kt`, a
   rule-based splitter that knows German abbreviations and ordinals). Each sentence renders
   as its own softly bordered block, so the translation unit is visible before you tap.
4. **Translate.** Tapping a sentence translates it German → English on device with ML Kit
   (`Translate.kt`) and opens the result in a modal (`TranslationDialog`). No account, no
   network call, no cost.
5. **Cache.** Translations are stored per book, keyed `"<blockhash>#<index>"`, in
   `translations.json` beside the book, so re-tapping and re-reading are free.

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

**Sentence splitting is the hard part.** `Sentences.kt` is a rule-based splitter, not a
naive punctuation scan: `.` is also the abbreviation and ordinal marker, so it has to keep
`Dr. Heinrich Hoffmann` and `564. Auflage` together. The strongest signal is the case of the
following token. Fragments shorter than `MIN_UNIT` are joined to the next sentence, because
ML Kit translates a bare `Pfui!` badly without its referent — but the fragment is still
*shown* on its own line.

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
  punctuation split.
- Hyphenated German compounds arrive as one token and translate as one word.
- Only EPUB3 XHTML text is parsed — no fixed-layout, no PDF, no cover thumbnails.
- Library metadata is a JSON manifest; it will want a real database eventually.

## Licence

MIT — see [LICENSE](LICENSE).
