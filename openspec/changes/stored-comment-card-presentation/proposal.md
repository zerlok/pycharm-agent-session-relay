## Why

A stored comment's inline card is invisible as an object. `StoredCommentCard.kt:124` paints the card in
`editor.colorsScheme.defaultBackground` — the *code's own* background — behind
`JBUI.Borders.customLine(JBColor.border(), 1)` (`StoredCommentCard.kt:126`), the faintest line most
themes own. There is no header, no author, no accent. Inside a large file the card reads as a stray
block of unhighlighted text rather than as a message about the lines above it. The user's words: *"when
comment added in huge text file - it is so thin, so it can't be distinguished from the text. better
improve borders, make it more like a card / message."*

Compounding it, `EditorReviewOverlay.addMarker()` deliberately gives a stored comment no wash and no
gutter icon (`EditorReviewOverlay.kt:184-186`, editor-review-visibility D5). At rest nothing ties a card
to the lines it annotates — the range only appears while the card is hovered
(`EditorReviewOverlay.kt:257-264`). So the one surface that *should* say "these lines have a comment"
says nothing until the pointer is already on it.

## What Changes

- Restyle `StoredCommentCard` to read as a **message**: an elevated card background distinct from the
  editor's, a left accent bar in the Relay range accent color, and a 1px outline — instead of
  "editor background behind a hairline".
- Give the card an **always-present header row** carrying an author label (a view-level `"You"` in this
  change) and hosting the Edit/Delete icon buttons. The icons stay hover-revealed *inside* the reserved
  row, so revealing them still cannot change the card's height or reflow the code below.
- Wire a **resting gutter bar** in the accent color onto the stored comment's existing position marker
  (`RangeHighlight.gutterBar`), so a commented range is identifiable from the gutter at rest, not only
  while its card is hovered. The code-area wash stays hover-only.
- Shape the card as *one message* (author header + body) so a future discussion thread can stack N of
  them inside one frame. Design-note only — **no** domain, storage, logic, export or delivery change.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `review-batch`: the stored-comment card gains a distinct card background, a left accent frame, and an
  always-present header row carrying the author label and hosting the hover-revealed Edit/Delete
  actions; its rest-vs-hover height stays identical and its width invariants are unchanged.
- `review-annotation`: a stored comment gains a *resting* gutter bar over its range (the code-area wash
  and the full range highlight remain hover-only), amending "no persistent range highlight at rest".

## Impact

- `StoredCommentCard.kt`: background, border/accent, new header row, header-aware
  `getPreferredSize`/`doLayout`, hover listener attached to the new children.
- `EditorReviewOverlay.kt`: `addMarker` attaches `RangeHighlight.gutterBar(...)` to the position marker;
  its D5 comment is amended. `StoredCommentGutterIconRenderer` stays unwired.
- `RangeHighlight.kt`: hosts the shared stored-comment accent color used by both the card's left bar and
  the resting gutter bar.
- `EditorReviewOverlayTest.kt`: a new headless assertion that the stored marker carries a
  `lineMarkerRenderer` while still carrying no `gutterIconRenderer`.
- View-only: no domain, storage, logic, export or delivery change. `CommentDraft.kt` /
  `CommentDraftController.kt` are **not** touched (sibling changes own them).
