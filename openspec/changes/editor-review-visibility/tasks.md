## 1. Shared range + gutter highlight (D3)

- [x] 1.1 Add a small `ui` helper (e.g. `RangeHighlight`) that, given an editor, an inclusive line
  range, and a color, creates a `RangeHighlighter` with the wash `backgroundColor` and attaches a
  `LineMarkerRenderer` painting a colored bar in the left gutter for the range; exposes dispose.
- [x] 1.2 Choose a gutter-bar width and reuse `CommentDraft.RANGE_BACKGROUND` (or a matching
  `JBColor`) so the bar and wash read as one highlight in light and dark themes.

## 2. Confine the hover "+" to the gutter (R1 / D1)

- [x] 2.1 In `RelayHoverListener.mouseMoved`, gate adding the "+" highlighter on `e.area` being a
  gutter area (not `EditorMouseEventArea.EDITING_AREA`); clear the hover when the pointer is over the
  editing area. Leave the existing draft edge-affordance branch first and unchanged.
- [ ] 2.2 Verify in a running IDE that the "+" is hidden over code content, appears over the
  line-number gutter, and stays clickable when the pointer moves onto the icon (marker sub-area).

## 3. Right-click "Add review comment" action (R2 / D2)

- [x] 3.1 Add `AddReviewCommentAction : AnAction` that resolves the editor from
  `CommonDataKeys.EDITOR`, computes the range from the selection (via `CommentDraftController.rangeFor`)
  or the caret line when there is no selection, and opens the draft via `CommentDraftController.open`.
- [x] 3.2 Implement `update` to disable/hide the action when no editor is present.
- [x] 3.3 Register the action in `plugin.xml` under the `EditorPopupMenu` group (first `<actions>`
  block).

## 4. Draft range highlight covers the gutter (R3 / D3)

- [x] 4.1 Update `CommentDraft.createHighlighter` to attach the shared gutter `LineMarkerRenderer`
  (task 1.1) alongside the existing wash and edge-line `CustomHighlighterRenderer`; ensure the bar is
  recreated with the wash on each `resize`.

## 5. Stored-comment range highlight on card hover (R4 / D4)

- [x] 5.1 Extend `StoredCommentCard.build` to notify an `onHover(visible)` callback on pointer
  enter/exit, reusing the existing `getMousePosition(true)` exit test so crossing into a child does
  not flicker.
- [x] 5.2 In `EditorReviewOverlay`, keep a single transient highlight: on card hover-in, build the
  shared `RangeHighlight` (task 1.1) from the comment's live marker range (same source as
  `currentPositions()`); on hover-out, dispose it. Ensure only one is shown at a time and it disposes
  with the overlay.

## 6. Remove the stored-comment gutter icon (R5 / D5)

- [x] 6.1 In `EditorReviewOverlay.addMarker`, stop setting `highlighter.gutterIconRenderer`; leave
  the marker as the (now invisible) live position source.
- [x] 6.2 Keep `StoredCommentGutterIconRenderer` in the tree, unreferenced (reserved for the deferred
  hide-comments change). Confirm nothing else references it and the build stays clean.

## 7. Tests & verification

- [x] 7.1 Update/extend `EditorReviewOverlayTest` for the no-gutter-icon marker and (where testable)
  the transient hover highlight lifecycle.
- [x] 7.2 Add coverage for `AddReviewCommentAction`'s range resolution (selection vs. caret line),
  mirroring the existing `rangeFor` expectations.
- [ ] 7.3 Compile offline and run the test suite per the build/test env notes; smoke-test the five
  behaviors in a running IDE (gutter-only "+", right-click add, draft gutter bar, card-hover range,
  no stored icon).
