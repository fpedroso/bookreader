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
6. **Translate.** Into a modal (`TranslationDialog`) that names the pair (`German → English`) and the
   engine — and the model, when the engine does not pin one — that produced the answer. If the text
   is already English it says so and never calls a model. `ML Kit` translates on the device, free,
   after a ~30 MB language pack. `OpenRouter` is a cloud call with the reader's own key, and that is
   the one engine where the text leaves the device.
7. **Cache.** Translations are stored per book in `translations.json` beside the book, keyed by
   a hash of the source text itself — never by position, so a sentence cannot inherit another
   sentence's translation when the splitter changes, and re-tapping is free. A word and a sentence
   share the one map; different text cannot collide.
8. **Settings** (`Engines.kt`, `Models.kt`, `OpenRouter.kt`) choose the translation engine, manage
   its model, and hold the cloud engine's API key, model id and endpoint: per-pack state, download
   with progress, and delete; a Test button that makes a real call, because a key is only worth
   saving if it works. The engine is part of every cache key, and the translation modal names the
   engine that answered, because two engines fail differently and the reader is the one weighing
   the output.

**Opening a book never blocks on any per-language preparation.** Any EPUB in any language
imports and renders as plain text. Translation is offered for **German books only** for now — the
gate is `translationOffered` in `Translate.kt`, one line to widen once the tap has been read on
more than one language. A row with no translation offered renders inert: no chip, no word tap.

**The engine is a setting, and it decides who can read what you read.** ML Kit is the on-device
default: free, offline once its ~30 MB pack is in place. OpenRouter is the cloud engine, and it is
the only one where a tapped word or sentence leaves the phone — the Settings section says so in its
first line. The modal stays out of the way: it says nothing about the engine when the chosen one
answered, and says which engine did answer only when it was not the one chosen.

**Why the cloud model is the default.** 49 real German sentences from the two fixtures were sent to
four models in exactly the request shape the app uses, then ranked blind by two judges from other
families (gemini-2.5-flash-lite over 49 sentences, claude-haiku-5.5 over 44), with the model names
stripped and the options shuffled per sentence. Both produced the same ordering —
`deepseek/deepseek-v4.1-flash`, then `tencent/hy-mt2-7b`, then `mistral-small-24b`, then
`hy-mt2-1.8b` — with 21 wins to 8 against the previous default. Cost is about four cents a book.
The probe that measured all this is in `~/bookreader-probe`.

**The app's network use depends on the engine you choose.** With ML Kit, nothing you read leaves the
phone; `INTERNET` is then only for the ~30 MB language pack, fetched when a translation first needs
it. With OpenRouter, the word or sentence you tap is sent to the endpoint you configured — the
Settings section states it in the first line. If a translation comes from an engine other than the one
chosen — ML Kit answering because no key is saved, say — the modal says so; otherwise it does not
mention the engine at all.

**Key hygiene, and any endpoint you like.** The API key lives in this app's private `settings.json`,
which is excluded from cloud backup and device transfer (`res/xml/backup_rules.xml`,
`res/xml/data_extraction_rules.xml`) so a key cannot ride into a backup. Any OpenAI-compatible
endpoint can be used, including a server on your own machine; cleartext HTTP is permitted only for
`localhost`/`127.0.0.1`/`10.0.2.2` (`res/xml/network_security_config.xml`), so the rest of the app
still cannot be downgraded to plain HTTP.

- **A word tap is a different question, so it is asked differently.** For a word the modal shows the
  German word, the `German → English` pair, and the English word — nothing else. The sentence it came
  from and the engine that answered earn their place on a sentence or a passage, where the reader is
  weighing prose; on one word they are noise in the way of the answer. The request carries a
  word-specific instruction for the same reason: the sentence instruction described *what* to
  translate but not *which unit*, and models answered with the whole sentence, a bare word with the
  context ignored, or an echo of the input label. The payload sends the word **and** the sentence it
  sits in, labelled (`Word: Bank`), because a bare word is the thing that translates ambiguously —
  `Bank` is a bench in *Struwwelpeter*, not a bank. That sentence is part of the word's cache key, so
  the same word in two sentences is two translations.
- **Sentence splitting takes a list, because only a list can tell `Mr.` from a full stop.**
  `Mr. Smith` and `Er ging. Beispiel kam.` are lexically identical to a splitter, so Settings holds
  the words after which a dot does not end a sentence — `Mr`, `Mrs`, `Ms`, `Dr`, `Prof`, `St`, `Nr`,
  `Bd`, `Abb` by default, and the reader can add anything else. Dotted forms like `z.B.` need no
  entry: the initial-chain rule already keeps them together. Adding a word that also *ends* sentences
  (`usw.`, `etc.`) merges those sentences into one translation unit — the reader's call, and the tests
  pin both sides of it.

- **Light and dark are the same design, twice.** Every colour the reader uses is a MaterialTheme
  role — page, sentence borders, inline chips, dialogs — so a theme change is a scheme swap and
  nothing else. `Theme.kt` holds stock `darkColorScheme()` and `lightColorScheme()`, picked by a
  `ThemeMode` (Dark, Light, Follow the system) stored as a string id in `settings.json`; an unknown
  or missing id reads back as the default rather than failing, so a settings file written before the
  setting existed keeps its key, model and endpoint. The choice lives in `Appearance`, above the
  theme call — which is where it has to be, because Settings renders *inside* `App`, below that
  call, so the value needs a home over both. It is applied from disk before the first frame, so
  startup never flashes the wrong palette, and again whenever Settings saves, so switching
  recolours the app without a restart. Two hand-rolled palettes were rejected before this one; both
  stock schemes are the answer to that, not a third guess.

- **Word shading is book-local, and it fades the tail rather than tinting the core.** Counting is
  done per book, from that book alone: no word list, no download, no word excluded — so a character
  name that recurs is shaded like anything else, because "you will meet this again here" is then
  exactly true. The five bands come from a ladder anchored on the book's own length
  (`t1 = max(12, tokens/250)`, then ÷2.5, ÷2.5, and ÷5 for the last step). Fixed counts were measured
  first and put **77% of an 80,000-word novel's page in the top band** — one flat wash; anchored to
  length the page mix holds at 37 / 10 / 12 / 20 / 21 percent of the running text on that novel and
  38 / 16 / 12 / 21 / 14 on an 11,000-word book. The last step is tighter deliberately: at ÷2.5 the
  two bottom bands come out lopsided (28% of the page against 12%) and a word read eighteen times
  shares a tone with one read twice. The **recurring** words keep the ordinary text colour and the
  rarer bands fade, because the recurring bands are 56–66% of the running text: tinting *those* would
  colour the majority of every page and leave the untinted minority as the thing that stands out,
  which is backwards. The four fade steps are evenly spaced per channel (~23 of 255), so the ramp is
  gentle but legible, and the faintest stays above 4:1 against its page — that band is a fifth of the
  text and has to be read, not merely noticed. Off by default; the word modal states the raw count —
  *"appears 23× in this book"* — which is what gives five tones meaning on a single word. Counted once
  when the book opens, off the main thread, over the blocks the reader would translate, so a German
  novel's English front matter cannot pollute the bands.

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

- **A `Test the key` result is not persisted**, so the success line disappears when Settings is
  reopened. The saved key is what matters; the line is only feedback for the call just made.
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
