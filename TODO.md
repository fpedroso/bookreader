# TODO

Ideas and known gaps, roughly in the order they'd be worth doing. Nothing here is
committed to; it's a parking lot. See the *Design notes* and *Known gaps* sections of
[README.md](README.md) for the reasoning behind the constraints mentioned below.

## Pagination

Currently the reader is a continuous `LazyColumn` scroll. Page-turn pagination is what a
book is supposed to feel like.

- Measure how many sentences fit the viewport and break at that boundary.
- Deliberately **not** about canonical, device-independent pages — font size changing the
  page count is normal and fine. This is just viewport fitting.
- Keep it behind an interface so scroll mode stays available; dyslexic/accessibility
  readers often prefer scroll.

## Translation settings

Right now translation is effectively fixed: German → English, one model, no configuration.

- target language, and a per-book override.
- whether to show the source sentence alongside the translation.
- an opt-in "explain this sentence" mode (see below) with its own model/key settings.

## LLM gloss for sentences you're stuck on

On-device translation answers "what does this say"; it does not answer "what is this
sentence doing" — word order, separable verbs, particle usage.

- On demand per sentence, prompted as a **tutor** (literal gloss + structure) rather than
  as a translation engine. Fluent output is the enemy here: it hides the structure the
  learner is trying to see.
- Cache by block hash, same as the translation cache, so re-reading costs nothing.
- Needs an API key, so it must be strictly opt-in and off by default.

## Smaller known gaps

Carried from README so they aren't lost:

- The sentence splitter is German-focused; other Latin-script languages fall back to a plain
  punctuation split.
- Hyphenated German compounds (`Guck-in-die-Luft`, `Zappel-Philipp`) arrive as one token and
  translate as a single word.
- No bookmarks, no highlights, no Anki export.
- Only EPUB3 XHTML text: no fixed-layout, no PDF, no cover thumbnails.
- Library metadata is a JSON manifest; it'll want a real database eventually.
