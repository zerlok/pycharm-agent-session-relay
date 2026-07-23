## Why

Three defects make the draft comment target the wrong lines, or draw its range in the wrong place.

1. **The gutter "+" ignores the line it was clicked on.** `CommentDraftController.rangeFor`
   (`CommentDraftController.kt:72-82`) returns the selection whenever `selectionModel.hasSelection()`
   is true and never looks at `clickedLine`. With text selected anywhere in the file, clicking the "+"
   on an unrelated line silently attaches the comment to the selection — the box opens far from where
   the user clicked ("where is my comment box?").

2. **A soft-wrapped line is treated as its first visual row only.** Every Y in `CommentDraft` is
   derived from logical-line geometry — `topEdgeY()` / `bottomEdgeY()` (`CommentDraft.kt:223-226`) and
   the duplicated copy inside `paintEdges()` (`CommentDraft.kt:324-325`) both use
   `logicalPositionToXY(LogicalPosition(line, 0)).y` (`+ editor.lineHeight` for the bottom). When a
   logical line spans N visual rows, `+ lineHeight` lands after row 1 of N, so the bottom edge is
   painted through the middle of the range and edge hit-testing (`edgeAt`, `CommentDraft.kt:229-238`)
   grabs there too. The drag-back mapping `lineAtY` (`CommentDraft.kt:241-242`) uses the opposite
   convention, so resizing snaps to either the first wrapped row or the line after all of them.

3. **The comment box covers the range's bottom edge.** The box is a block inlay anchored at
   `document.getLineEndOffset(end)` with `relatesToPrecedingText = true` / `showAbove = false`
   (`CommentDraft.kt:353-362`), i.e. it starts at exactly the Y where `paintEdge` centres the bottom
   stroke (`CommentDraft.kt:330-334`, `fillRect(0, y - thickness / 2, …)`). The box's Swing component
   paints over it, so the highlighted region reads as open-ended.

## What Changes

- Make the target range obey **containment**: the selection is used only when the clicked line falls
  inside the selection's line span; otherwise the comment targets the clicked line alone. The
  right-click entry point passes the caret line, which always lies inside that span, so its behavior
  is unchanged and the two entry points stay in parity.
- Derive all draft range geometry from **visual-line** APIs (`offsetToVisualLine`, `visualLineToY`,
  `visualLineToYRange`, `yToVisualLine`) so a soft-wrapped logical line is one line occupying all of
  its visual rows — for the painted edges, for edge hit-testing, and for the drag-to-line mapping.
- Paint both edges **inside** the highlighted range rather than centred on its boundary, so the bottom
  edge stays visible above the comment box instead of being covered by it.
- De-duplicate the edge geometry: `paintEdges` calls the same `topEdgeY()` / `bottomEdgeY()` helpers
  that hit-testing uses, so painting and hit-testing can no longer drift apart.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `review-annotation`: the target range rule gains a containment condition (the selection wins only
  when the clicked line is inside it); the range highlight and its draggable edges are specified in
  visual rows so soft-wrapped lines are covered end to end; the bottom edge remains visible while the
  comment box is open beneath it.

## Impact

- `CommentDraftController.rangeFor` — containment test against the selection's line span.
- `CommentDraft` — `topEdgeY` / `bottomEdgeY` / `edgeAt` / `lineAtY` / `paintEdges` / `paintEdge`
  (geometry and painting only).
- `AddReviewCommentActionTest` — extended with the containment cases; a new controller-level test for
  the gutter-click cases.
- View-only change: no domain, storage, logic, export or delivery change. `CommentDraft`'s body
  field, data context and inlay-revalidation concerns are untouched (owned by a sibling change), as
  are `StoredCommentCard` and `EditorReviewOverlay`.
