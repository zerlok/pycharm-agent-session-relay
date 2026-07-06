## Why

Early users report that once a comment is submitted it vanishes from the editor — only a gutter
balloon remains — so they can neither re-read its body in place nor fix a typo or rewrite it. The
comment box is unreachable after submit, and no edit path exists. Reviewing is iterative; a comment
you can't revisit is a comment you can't trust.

## What Changes

- After submit, the comment body STAYS visible in the editor as an always-expanded, read-only inline
  card rendered under its line range (below the code, GitHub/GitLab style), replacing the current
  "box disappears, only a gutter icon remains" behavior.
- Each card carries **Edit** and **Delete** affordances; the gutter balloon's popup gains a matching
  **Edit comment** entry alongside the existing **Delete comment**.
- Choosing **Edit** re-opens the authoring box seeded with the comment's current body and line range;
  submitting updates the existing comment in place (body and position) rather than creating a new one.
  The box's range stays adjustable during an edit (reuses `adjustable-comment-range`).
- The store gains an `updateBody` command (parallel to `removeComment`) so an edit is a single
  in-place mutation that every surface reconciles from — no delete-and-re-add.
- The single-active-box rule extends to editing: opening an edit box closes any open box, and the
  edited comment's read-only card is suppressed while its edit box is open, so the two never overlap.

## Capabilities

### New Capabilities
<!-- None — this extends the existing annotation/batch surfaces rather than introducing a new capability. -->

### Modified Capabilities
- `review-annotation`: the submit outcome now leaves a persistent inline card (supersedes the
  box-disappears behavior); a new authoring flow re-opens the box seeded from an existing comment and
  resubmits as an in-place update; the single-active-box rule covers edit boxes.
- `review-batch`: the store gains an in-place body-update command; stored comments render as an
  always-expanded inline card (a new display surface alongside the gutter marker and tool window),
  offering Edit and Delete.

## Impact

- **Code**: `CommentDraft` (seed from an existing comment; route submit to update vs. add),
  `CommentDraftController` (an `openForEdit` entry point), `EditorReviewOverlay` (own a persistent
  card inlay per stored comment, suppressed while that comment is being edited),
  `ReviewBatchService` + `ReviewBatchStorage` (add `updateBody`), `StoredCommentGutterIconRenderer`
  (add an Edit action). A new small read-only card component.
- **APIs**: new `ReviewBatchService.updateBody(id, body)`; reuses the existing `commentUpdated` event.
- **No new dependencies.** No persistence-format change (batch is still in-memory).
- **Interactions**: composes with `adjustable-comment-range` (edit box is resizable) and the
  position-flush in `review-delivery` (unchanged).
