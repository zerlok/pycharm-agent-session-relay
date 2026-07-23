## 1. Target range containment

- [x] 1.1 In `CommentDraftController.rangeFor` (`CommentDraftController.kt:72-82`), compute the
      selection's **untrimmed** line span `first..rawLast` from `selectionStart` / `selectionEnd`, and
      return `clickedLine to clickedLine` unless `clickedLine` is inside that span.
- [x] 1.2 Keep the existing trailing-line trim (`if (last > first && selection.selectionEnd ==
      document.getLineStartOffset(last)) last--`) applied to the *returned* range only — not to the
      span used for the containment test (design: right-click parity).
- [x] 1.3 Update the KDoc on `rangeFor` to state the containment rule and why the test uses the
      untrimmed span (reference the parity requirement rather than restating it).
- [x] 1.4 Confirm `AddReviewCommentAction.actionPerformed` (`AddReviewCommentAction.kt:26`) still needs
      no change — it passes `editor.caretModel.logicalPosition.line`, which is inside the untrimmed
      span whenever a selection exists. Do not add a second range rule there.
      (Confirmed: no behavior change. Its class KDoc *restated* the old "selection when one exists"
      rule, which the containment rule made false, so the KDoc was reduced to a reference to
      `rangeFor` plus the parity reason — doc only.)

## 2. Visual-row edge geometry

- [x] 2.1 Rewrite `CommentDraft.topEdgeY()` (`CommentDraft.kt:223`) as
      `editor.visualLineToY(editor.offsetToVisualLine(document.getLineStartOffset(start), false))`.
- [x] 2.2 Rewrite `CommentDraft.bottomEdgeY()` (`CommentDraft.kt:226`) as
      `editor.visualLineToYRange(editor.offsetToVisualLine(document.getLineEndOffset(end), false))[1]`
      — the bottom of the range's **last** visual row.
- [x] 2.3 Delete the duplicated inline geometry in `paintEdges` (`CommentDraft.kt:324-325`) and call
      `topEdgeY()` / `bottomEdgeY()` instead, so painting and hit-testing share one expression.
      (`paintEdges` also dropped its `Editor` parameter — the helpers read the draft's own `editor`
      field, and keeping the parameter would have shadowed it for no benefit.)
- [x] 2.4 Leave `edgeAt` (`CommentDraft.kt:229-238`) structurally unchanged — it now inherits the
      corrected Ys and its grab zones stay centred on the boundaries.
- [x] 2.5 KDoc both helpers with *why* visual rows are used (a soft-wrapped logical line is one line
      occupying all of its rows), matching the surrounding comment density.

## 3. Drag-to-line mapping

- [x] 3.1 Rewrite `lineAtY` (`CommentDraft.kt:241-242`) as
      `editor.visualToLogicalPosition(VisualPosition(editor.yToVisualLine(y), 0)).line`, clamped to
      `0..document.lineCount - 1` as today.
- [x] 3.2 In `onMouseDragged` (`CommentDraft.kt:296-304`), make the mapping direction-aware: `TOP` maps
      `y`, `BOTTOM` maps `y - 1` (the bottom edge Y is the exclusive bottom of the last row, i.e. the
      top of the next line). Keep the existing `coerceAtMost(end)` / `coerceAtLeast(start)` clamps.
- [x] 3.3 Drop the now-unused `LogicalPosition` / `Point` imports if nothing else in the file uses them,
      and add `VisualPosition`.

## 4. Bottom edge visible under the box

- [x] 4.1 Change `paintEdge` (`CommentDraft.kt:330-334`) so the stroke is drawn *inward* from the given
      boundary rather than centred on it: pass which edge is being drawn (or the inward direction) and
      fill `topY .. topY + thickness` for the top edge and `bottomY - thickness .. bottomY` for the
      bottom edge. (Took the "which edge" form: `paintEdge` now receives the `Edge` and derives both
      the inward direction and its own `active` flag, which removed the duplicated
      `hoveredEdge == … || draggingEdge == …` test from `paintEdges`.)
- [x] 4.2 Do **not** change the box inlay's anchor offset or `EditorEmbeddedComponentManager.Properties`
      (`CommentDraft.kt:353-362`), and do not touch `buildPanel`'s borders or opacity — the box's
      layout belongs to a sibling change.
- [x] 4.3 Confirm hit-testing still uses the unmodified boundary Ys (`edgeAt`), so only the painting
      moved.

## 5. Tests

- [x] 5.1 Add a `CommentDraftControllerTest` (`BasePlatformTestCase`, alongside
      `src/test/kotlin/io/github/zerlok/agentsessionrelay/ui/AddReviewCommentActionTest.kt`) covering
      `rangeFor` for the gutter-click entry point: no selection → clicked line; clicked line inside the
      selection → the selection's trimmed range; clicked line **outside** the selection → the clicked
      line alone (both above and below the selection). (8 cases; also the excluded trailing line, which
      is outside the trimmed range but inside the containment span.)
- [x] 5.2 Add a case to `AddReviewCommentActionTest` for the parity boundary: a selection ending exactly
      at the start of the line the caret rests on still resolves to the trimmed selection range (the
      case that would regress under a trimmed-span containment test).
      (The file's existing selection cases had to gain an explicit `<caret>` marker: without one the
      fixture leaves the caret at offset 0, a state a real selection can never be in, and the new
      containment rule correctly rejected it. Also added the caret-at-selection-start case, the other
      end a real drag can leave it at.)
- [x] 5.4 (added) `CommentDraftEdgeDragTest` — the drag mapping of §3.2 against a live editor draft:
      pressing a boundary Y claims the edge, a bottom drag released on a row's own bottom boundary makes
      that row's line the end (the off-by-one), top drags, clamping, and non-claiming presses. Only the
      Y→line *mapping* is asserted; the row geometry itself is stated with the same editor calls the
      draft uses, so 5.3 still holds.
- [x] 5.3 Do not attempt a headless unit test of the visual-row geometry — soft-wrap layout needs a real
      editor viewport width. It is covered by the manual checks below.

(5.1 / 5.2 are left for the test stage that owns them; the implementation stage was instructed not to
write tests.)

## 6. Verify

- [x] 6.1 Compile gate: `./gradlew compileKotlin --offline` from the worktree root. (BUILD SUCCESSFUL.)
- [x] 6.2 Tests: `./gradlew test` (needs network). Do not run gradle invocations in parallel.
      (BUILD SUCCESSFUL — 95 tests, 0 failures. The 6 new cases that pin this change were also run
      against the pre-change implementation and all 6 failed there, so they are not vacuous.)
- [x] 6.3 Record the outcome of each design.md open question that a running IDE would settle; if none can
      be checked, leave them open — do not mark them verified. (None could be checked: no display,
      `runIde` impossible. All five open questions remain open, unchanged.)
