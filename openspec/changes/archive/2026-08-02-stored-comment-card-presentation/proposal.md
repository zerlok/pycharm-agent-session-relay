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

**Review revision (PR #9, 2026-08-01).** A manual pass over the shipped card confirmed the message shape
but reported three defects, all of which this proposal now also covers: *"i see 2 blue lines: one on
gutter area — highlights how many lines are commented; second — left border of the card. let's remove
second line, it just distracts"*; *"the new look of the left comment card is better, but while editing
the comment — it has old style. also note that buttons cancel and save has tiny gray background around
them"*; *"better rename save to comment action and fill it with appropriate color and the same color use
for text area of editing comment; try our plugin's style"*. The first retracts one of this change's own
decisions (the card's left accent bar); the other two extend the change to the **authoring box**, which
was originally declared out of scope — so a card and the box that edits it stopped matching the moment
the card was restyled.

## What Changes

- Restyle `StoredCommentCard` to read as a **message**: an elevated card background distinct from the
  editor's and a 1px outline — instead of "editor background behind a hairline". *(Revised: the card's
  left accent bar is dropped. One commented range now carries exactly one accent mark — the gutter bar —
  because two parallel blue lines a few pixels apart read as noise, not as one object.)*
- Give the **authoring/edit box** the same presentation as the card — the same UI-surface fill, the same
  1px outline, the same padding — so opening a stored comment for editing is visibly the *same object*
  in its editing state rather than a differently-styled panel.
- Style the box's actions in the plugin's own accent: the primary action is labeled **"Comment"** in both
  the new-comment and the edit case (never "Save"), is filled with the accent, and the body field is
  framed in that same accent. Neither button paints a stray background patch behind its rounded shape.
- Collect every Relay color into one `RelayStyle` object — the accent, its filled-surface variant, the
  range wash and the draft's edge colors — so the card, the box, the gutter bar and the wash cannot drift
  apart. This retires the "unify the accent with the draft's edge blue later" follow-up this change
  originally deferred (the two constants were already the same values in two files).
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

- `review-batch`: the stored-comment card gains a distinct card background, a 1px outline, and an
  always-present header row carrying the author label and hosting the hover-revealed Edit/Delete
  actions; its rest-vs-hover height stays identical and its width invariants are unchanged. The card
  carries **no** accent bar of its own — the accent marks the range in the gutter and nowhere else.
- `review-annotation`: a stored comment gains a *resting* gutter bar over its range (the code-area wash
  and the full range highlight remain hover-only), amending "no persistent range highlight at rest"; and
  the authoring box gains the card's presentation plus an accent-filled primary action labeled
  "Comment" in both the new and the edit case.

## Impact

- `RelayStyle.kt` (new): the single home for every Relay color — accent, accent fill + its label color,
  range wash, draft edge idle/active — and for the shared card/box surface fill.
- `StoredCommentCard.kt`: background, 1px outline (no accent bar), new header row, header-aware
  `getPreferredSize`/`doLayout`, hover listener attached to the new children.
- `EditorReviewOverlay.kt`: `addMarker` attaches `RangeHighlight.gutterBar(...)` to the position marker;
  its D5 comment is amended. `StoredCommentGutterIconRenderer` stays unwired.
- `RangeHighlight.kt`: keeps `gutterBar`/`create`; its color constant moves to `RelayStyle`.
- `CommentDraft.kt`: box panel fill + outline matched to the card, accent-framed body field,
  accent-filled primary action renamed to "Comment" in both modes, no stray button background.
  **View-only edits** — the draft's range/anchoring/submit logic is untouched.
- `EditorReviewOverlayTest.kt`: a new headless assertion that the stored marker carries a
  `lineMarkerRenderer` while still carrying no `gutterIconRenderer`.
- `StoredCommentCardTest.kt` / `CommentDraftPresentationTest.kt`: the card's accent-bar assertion is
  replaced by "no accent bar, one outline", and the box's fill/frame/action styling is asserted.
- No domain, storage, logic, export or delivery change. `CommentDraftController.kt` is **not** touched.
  `CommentDraft.kt` now *is* in scope for its presentation only — see the design's revised Non-Goals and
  the conflict note for the sibling `comment-box-editing-fidelity` change (PR #10), which edits the same
  file's behavior.
