## Why

Submitting a review while `REVIEW.md` is open in an editor **destroys the user's comments**. The
batch is cleared on the strength of a raw `Files.writeString` returning, but that write goes behind
the platform's Document layer, and the Document is what the editor renders — so the file the user is
looking at still shows the previous export while the comments are erased from the store with no undo.
The clear is irreversible and the batch is the user's only copy, so the signal that authorizes it has
to mean "the export is visible", not "bytes reached the disk".

Separately, the width rule those inline surfaces are laid out by is a hand-derived reimplementation of
a rule the platform already ships — including the same two constants — and hand-deriving it has now
been wrong twice.

## What Changes

- **`REVIEW.md` is written through the platform's `Document`** rather than with a raw nio write plus a
  VFS refresh. The object the user sees is the object written, so "written" and "visible" cannot come
  apart, and the batch is cleared against an export the user can actually read.
- **BREAKING (spec):** the `review-delivery` requirement that the write run **off the EDT** is
  replaced. It was written against nio I/O plus a synchronous VFS refresh, both genuinely illegal on
  the EDT; a Document write is EDT-plus-write-action by platform contract. The requirement it protects
  — submit does not freeze the IDE — is kept and re-expressed against the work that can actually block
  (reading files to verify anchors), which stays off the EDT.
- **Inline surfaces are sized by the platform**, via `Editor.addComponentInlay` with
  `ComponentInlayAlignment.FIT_VIEWPORT_WIDTH` plus an inner width-restricted layout — the same
  arrangement `CodeReviewComponentInlayRenderer` uses, whose reading-measure constants (42 characters
  at the default system font size, plus 52dp of chrome) `InlineWidth` already copies. Live tracking on
  resize comes from the platform's own listeners.
- `InlineWidthWatcher` is deleted, along with `InlineWidth.availableWidthPx`, `baseWidthPx`,
  `currentWidthPx`, `rightMarginPx`, `columnsPx`, and `pinLeading`. Only the reading measure survives.
- **BREAKING (spec):** the right-margin cap is dropped from the surface width rule, which becomes
  `min(viewport, readingMeasure)`. The platform's rule has no right-margin term, and keeping it would
  mean keeping the hand-derived path this change exists to delete.

- **Inline surfaces stay clear of the editor's inspections widget.** `FIT_VIEWPORT_WIDTH` subtracts
  only the vertical scrollbar, and the widget floats over the viewport without being in its layout,
  so a surface on a narrow editor was laid out underneath it and the card's trailing Edit/Delete
  icons became unreachable. The row now reserves the part of the viewport the widget covers, measured
  from laid-out bounds via the public `JBScrollPane.getStatusComponent()`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `review-delivery`: the write is defined against what the user can see rather than against the
  filesystem; the batch may be cleared only once the export is visible; the off-EDT constraint moves
  from the write to the file reads.
- `review-annotation`: the authoring box's width rule loses its right-margin cap.
- `review-batch`: the stored card's width rule loses its right-margin cap.

## Impact

- `delivery/ReviewDeliveryService` — the write and the clear-or-preserve decision; the stage split
  changes shape.
- `ui/InlineWidth` reduced to the reading measure plus the floating-widget reserve; `ui/InlineWidthWatcher` deleted;
  `ui/EditorReviewOverlay`, `ui/CommentDraft`, `ui/StoredCommentCard` move off
  `EditorEmbeddedComponentManager.addComponent` and off their own `getMaximumSize` capping.
- `ui/EditorReviewOverlayService` no longer installs or disposes a width watcher.
- Tests: `ReviewDeliveryServiceTest` gains open-file coverage asserting **Document text** — the
  existing byte-level assertions pass against the unfixed code and cannot be the regression guard.
  `InlineWidthWatcherTest` is deleted and `InlineWidthTest` shrinks with the object.
- `docs/ARCHITECTURE.md` §5.3 (the threading split) and §8 (platform API charter — this adds
  `com.intellij.openapi.editor.ComponentInlay*`, present in the 2024.2.5 target).
- **Ordering:** `responsive-inline-comment-surfaces` is implemented but not archived, and this
  change's deltas are written against *its* version of the two width requirements. It must be
  archived before this change is.
