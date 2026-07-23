## Context

Two files own the draft's "which lines, drawn where" behavior.

`CommentDraftController.rangeFor(editor, clickedLine)` (`CommentDraftController.kt:72-82`) is the one
shared range rule for both entry points: the gutter "+"
(`AddCommentGutterIconRenderer.getClickAction`, `AddCommentGutterIconRenderer.kt:30`, passing the
hovered line) and the context menu (`AddReviewCommentAction.actionPerformed`,
`AddReviewCommentAction.kt:26`, passing `editor.caretModel.logicalPosition.line`). Its body ignores
`clickedLine` entirely once `hasSelection()` is true.

`CommentDraft` owns the wash and its edges. The wash is a `LINES_IN_RANGE` background
`RangeHighlighter` over `getLineStartOffset(start)`..`getLineEndOffset(end)` plus a
`RangeHighlight.gutterBar` line-marker renderer (`CommentDraft.kt:183-198`). The edges are painted by
a `CustomHighlighterRenderer` (`CommentDraft.kt:91`, `322-334`). Both edge Ys are computed from
`logicalPositionToXY(LogicalPosition(line, 0))` + `editor.lineHeight`, in two places that duplicate
the same expression (`CommentDraft.kt:223-226` and `CommentDraft.kt:324-325`). Hit-testing (`edgeAt`)
and the drag mapping (`lineAtY`, via `xyToLogicalPosition`) sit on top of that geometry. The box
itself is an `EditorEmbeddedComponentManager` block inlay anchored at `getLineEndOffset(end)` with
`relatesToPrecedingText = true` / `showAbove = false` (`CommentDraft.kt:353-362`).

The 2024.2 `com.intellij.openapi.editor.Editor` on the compile classpath
(`pycharm-community-2024.2.5/lib/app-client.jar`) declares the visual-row API this change needs:
`offsetToVisualLine(int, boolean)`, `visualLineToY(int)`, `yToVisualLine(int)`,
`visualLineToYRange(int)`, `visualToLogicalPosition(VisualPosition)`. `visualLineToYRange`'s default
body is `[visualLineToY(vl), visualLineToY(vl) + lineHeight]` (custom fold regions substitute their
own height) — it is a *row* range and does not include block-inlay height, which is exactly what the
edges need.

## Goals / Non-Goals

**Goals:**

- The "+" comments the line it was clicked on unless that line is part of the selection.
- A soft-wrapped logical line behaves as one line spanning all of its visual rows — painted edges,
  edge hit-testing, and drag-to-line mapping alike.
- The range's bottom edge stays visible while the comment box is open below it.
- One expression of the edge geometry, shared by painting and hit-testing.

**Non-Goals:**

- Any change to `CommentDraft`'s body field, key handling, focus routing, data context or inlay
  revalidation — a sibling change owns the box's editing behavior.
- Any change to `StoredCommentCard` or `EditorReviewOverlay` (including the stored-comment hover
  highlight, which has no draggable edges).
- Folding, block inlays *inside* the range, and multi-caret selections — out of scope; the geometry
  helpers inherit whatever the platform's visual-line mapping already does for them.
- Any domain / storage / logic change: line numbers stay 0-based logical lines in the domain. Visual
  rows are a pure view concern and never reach `Subject`.

## Decisions

**Containment decides whether the selection wins.** `rangeFor` computes the selection's line span
first, then uses it only when `clickedLine` falls inside it; otherwise it returns
`clickedLine to clickedLine`. This is the smallest rule that fixes the reported confusion: the "+"
always comments where it was clicked, and a click *inside* the selection still comments the whole
selection (the familiar PR-review gesture).

**Containment is tested against the untrimmed line span; the trim still applies to the result.** The
existing rule "a selection ending exactly at the start of a line does not include that trailing line"
(`CommentDraftController.kt:79-80`) shrinks `last` by one. The containment test must run against the
*untrimmed* span `first..rawLast`, and the trim must be applied to the range that is returned. The
reason is right-click parity: after a top-down drag-select of lines 10–15 that ends at the start
offset of line 16, the caret is on line 16 while the trimmed range is 10–15.
`AddReviewCommentAction` passes the caret line, so a trimmed-span test would resolve that right-click
to line 16 alone — a regression against the current spec requirement "Right-click with a multi-line
selection" and against `AddReviewCommentActionTest`. With the untrimmed span the caret line is inside
by construction (the caret sits at one end of the selection), so every right-click with a selection
behaves exactly as today. The cost is that a gutter "+" clicked on that trailing line also resolves
to the selection — acceptable, because that line is where the user's own selection caret is resting,
not the "unrelated line" the defect is about.

- _Alternative — test containment against the trimmed span:_ marginally more literal ("the highlighted
  lines"), but breaks the right-click parity the spec asserts and would need a second, different rule
  in `AddReviewCommentAction`. Rejected; parity through one shared `rangeFor` is the existing design
  (archive `2026-07-07-editor-review-visibility`, D2).
- _Alternative — test containment by offset intersection with `selectionStart..selectionEnd`:_ same
  parity break as above (line 16 starts exactly at `selectionEnd`, so a half-open intersection is
  empty) plus more arithmetic. Rejected.
- _Alternative — keep selection-wins and instead move the caret/selection on a "+" click:_ mutates the
  user's editor state as a side effect of an annotation gesture. Rejected.

**All draft range Ys come from visual lines, via two private helpers.** Replace the logical-line math
with:

- `topEdgeY()` = `editor.visualLineToY(editor.offsetToVisualLine(document.getLineStartOffset(start), false))`
- `bottomEdgeY()` = `editor.visualLineToYRange(editor.offsetToVisualLine(document.getLineEndOffset(end), false))[1]`

`getLineStartOffset(start)` is never a soft-wrap position, so its visual line is the range's first
row; `getLineEndOffset(end)` is the last offset of the logical line, so with `beforeSoftWrap = false`
its visual line is the range's *last* row, and `visualLineToYRange(...)[1]` is that row's bottom. With
soft wraps off both expressions collapse to today's values, so unwrapped editors are unaffected.
`topEdgeY` is in fact already correct today (`logicalPositionToXY(LogicalPosition(start, 0))` resolves
to the first visual row); it is re-expressed through the same helper anyway so both edges derive from
one convention and cannot drift.

- _Alternative — keep `logicalPositionToXY` and add `softWrapModel.getSoftWrapsForLine(end).size *
  lineHeight`:_ reimplements the platform's own row accounting and silently breaks for custom fold
  regions with a non-`lineHeight` height. Rejected.

**`paintEdges` calls the helpers instead of restating them.** `paintEdges` (`CommentDraft.kt:322-328`)
currently recomputes both Ys inline. It takes the `Editor` handed to the `CustomHighlighterRenderer`,
which is the same editor the draft holds (the renderer is attached to this draft's own highlighter),
so calling `topEdgeY()` / `bottomEdgeY()` is behavior-preserving and removes the duplication that let
painting and hit-testing disagree in the first place (Single Source of Truth).

**Both edges are painted inside the range, not centred on its boundary.** Change `paintEdge` so the
stroke occupies the `thickness` pixels *inward* from the given boundary: the top edge at
`topY .. topY + thickness`, the bottom edge at `bottomY - thickness .. bottomY`. The box inlay's
component starts at `bottomEdgeY()` (it is anchored below the range's last row), so a stroke that ends
at `bottomEdgeY()` is the last thing drawn before the box and stays fully visible; the wash then reads
as a closed rectangle rather than an open-ended region. Hit-testing keeps using the *boundary* Ys, so
the grab zones and the drag feel are unchanged.

- _Alternative — give the box panel a top empty border so the edge shows through:_ the box content
  panel is opaque and filled with `editor.colorsScheme.defaultBackground` (`CommentDraft.kt:498-499`),
  so an inset would show the box's own background, not the wash — the edge would still be invisible
  unless the wrapper were also made non-opaque, which touches the box's layout (sibling-change
  territory). Rejected.
- _Alternative — anchor the box one line lower (`getLineEndOffset(end + 1)`):_ inserts a blank code
  line between the range and its box and changes the inlay's anchoring semantics, which the
  edge-drag rebuild and the `adjustable-comment-range` "box reappears under the range's bottom line"
  requirement both depend on. Rejected.
- _Alternative — paint the bottom edge at `bottomY - 1` only (leave the top edge centred):_ fixes the
  occlusion but leaves the two edges with different conventions, which is how the current
  duplicated-geometry bug arose. Rejected.

**The drag mapping is direction-aware and routed through visual lines.** `lineAtY` becomes
`editor.visualToLogicalPosition(VisualPosition(editor.yToVisualLine(y), 0)).line`, clamped to the
document — so any Y inside any visual row of a wrapped logical line resolves to that one logical line.
On top of that, the two edges use opposite boundary conventions and the mapping must match: the top
edge Y is the *inclusive* top of the first row, so `TOP` maps `y` directly; the bottom edge Y is the
*exclusive* bottom of the last row (equal to the top of the next line's first row), so `BOTTOM` maps
`y - 1`. Without the `- 1`, pressing exactly on the bottom edge and moving one pixel would already
grow the range by a line — an off-by-one that exists today and would become more visible once the edge
is drawn at the true bottom of a wrapped line.

- _Alternative — keep `xyToLogicalPosition(Point(0, y))`:_ it happens to resolve wrapped rows to their
  logical line too, but it also runs column resolution off an arbitrary `x = 0` and reads as logical
  geometry in a method whose whole point is that logical geometry is wrong here. The explicit
  visual-line form states the intent. (Behaviorally equivalent for the `.line` component; this is a
  clarity call, not a fix.)
- _Alternative — shift the bottom edge's grab zone entirely above the boundary:_ the half of the
  centred zone that lies below `bottomEdgeY()` is over the box's Swing component, which consumes its
  own mouse events, so that half is unreachable and the usable band is `GRAB_ZONE_DP` rather than
  `2 × GRAB_ZONE_DP`. Left as-is for now — it is the behavior users already have, and whether the
  band feels big enough is a visual judgement (see Open Questions).

**The wash and the gutter bar are not touched, and this change therefore asserts nothing about them.**
The wash is a `HighlighterTargetArea.LINES_IN_RANGE` background attribute (`CommentDraft.kt:190`) and
the gutter bar is a `LineMarkerRenderer` given a platform-computed rectangle (`RangeHighlight.kt:42-45`);
both are expressed in logical lines and expanded to pixels by the platform. Nothing in this change's
diff reaches either of them — the diff touches only `topEdgeY` / `bottomEdgeY` / `lineAtY` /
`paintEdges` / `paintEdge` / `onMouseDragged` and `rangeFor`. So the wash's wrap behavior is
*inherited platform behavior*, not behavior this change delivers, and the delta spec must not phrase
it as a `SHALL` (review finding M1).

Static reading of the 2024.2.5 bytecode corroborates the assumption without settling it.
`RangeHighlighterImpl.getAffectedAreaEndOffset()` expands a `LINES_IN_RANGE` highlighter to
`min(getLineEndOffset(getLineNumber(endOffset)) + 1, textLength)`, and
`IterationState.getAlignedStartOffset/getAlignedEndOffset` feed exactly those whole-line offsets into
the attribute sweep; `EditorPainter$Session.paintBackground()` then walks a `VisualLinesIterator` row
by row and paints each row's fragments from that offset-keyed attribute state. Every visual row of the
logical line is therefore inside the highlighter's offset span and gets the background attribute. What
this does *not* establish is the pixel residue — the fill past the last glyph of a wrapped row, and
the gutter bar's rectangle, are painting decisions no static read settles. Those stay open questions.

- _Alternative — keep asserting "a soft-wrapped target line SHALL be highlighted across all of its
  visual rows" in the delta spec (as originally written):_ it commits the capability to behavior no
  line of this diff produces, and it contradicts this change's own open question listing the same
  claim as unverified. A spec is the record of what a change is accountable for; inheriting a platform
  behavior is not delivering it. Rejected — the requirement was narrowed to the edges, their
  hit-testing and the drag mapping, which is exactly what the diff controls. If the wash ever needs a
  normative guarantee, it belongs to a change that owns the wash and can verify it in a running IDE.

## Risks / Trade-offs

- **[Containment changes a behavior some users may rely on]** → the old behavior is reachable in one
  click: click the "+" on any line inside the selection. The change only removes the case where the
  box opened somewhere the user did not click.
- **[Visual-line APIs are `default` methods on `Editor`]** → `EditorImpl` overrides them with its
  view-model implementations; the defaults are only a fallback for non-standard editors and are
  consistent with them. No behavior difference for the `EditorEx` this code requires
  (`CommentDraft.create` returns null for anything else, `CommentDraft.kt:460`).
- **[Painting the edge inward overlaps the last row's text descenders]** → that is how a border reads;
  the stroke is 1px idle / `JBUI.scale(2)` active, the same weight as today.
- **[The `- 1` in the bottom drag mapping changes the current (sloppy) resize feel]** → intended: it
  makes "release on the row you want to be last" resolve to that row instead of the one below it.

## Open Questions

_None of these can be settled here — this machine has no display and `runIde` is impossible. Each
needs a visual check in a running IDE._

- Does the `LINES_IN_RANGE` wash actually *look* continuous over every visual row of a soft-wrapped
  line? The offset span and the row-by-row background sweep are confirmed statically above, so the
  remaining risk is pixel-level (trailing fill past the last glyph of a wrapped row). If it reads as
  banded, the wash needs its own treatment in a change that owns it — this one deliberately makes no
  claim about it.
- Does the gutter bar (`RangeHighlight.gutterBar`) likewise span all visual rows of a wrapped line?
  Same status: not asserted by this change's spec either way.
- With the bottom edge drawn inside the range, is it visibly separated from the box's own 1px top
  border (`JBUI.Borders.customLine(JBColor.border(), 1)`, `CommentDraft.kt:502`), or do the two lines
  read as one thick rule? If the latter, consider a 1px gap or dropping the box's top border line.
- Is the effectively `GRAB_ZONE_DP`-wide reachable band above the bottom edge comfortable to grab, or
  should the bottom grab zone be widened upward to `2 × GRAB_ZONE_DP`?
- Does an edge-drag across a soft-wrapped line now feel monotonic (one logical line per pass, no
  snapping back), including when the drag crosses the box's former position?
