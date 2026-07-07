## Context

All five changes are presentation-layer only (the `ui` package); no domain, logic, or storage
concept changes. The relevant surfaces already exist and are described in `docs/ARCHITECTURE.md`:

- `RelayHoverListener` owns the shared editor mouse channel — it renders the hover "+" today and
  routes edge-drag events to the active `CommentDraft`.
- `CommentDraft` owns the live draft wash (a `RangeHighlighter` with a `backgroundColor`) plus a
  `CustomHighlighterRenderer` that paints the draggable edge lines.
- `EditorReviewOverlay` owns, per stored comment, a `RangeHighlighter` (currently `null`
  attributes, so invisible — it is only a position source + gutter-icon host) and a read-only
  `StoredCommentCard` block inlay. The store is the single source of truth; the overlay reconciles
  by diff.
- `StoredCommentCard` already tracks pointer enter/exit (via `getMousePosition(true)`) to reveal its
  hover toolbar without reflowing the inlay.

The existing gutter-bar mechanism to reuse is IntelliJ's `LineMarkerRenderer`, attached to a
`RangeHighlighter` via `setLineMarkerRenderer` — the same hook VCS change bars use to paint a
colored stripe in the left gutter for a line range.

## Goals / Non-Goals

**Goals:**

- Confine the hover "+" to the left gutter; suppress it over code content.
- Add a right-click "Add review comment" editor action.
- Make the commented range visible in the line-number gutter (draft: always; stored: on card hover).
- Remove the redundant stored-comment gutter icon while keeping its renderer class for later reuse.
- Keep one shared "range + gutter" highlight visual so draft and stored-hover look identical.

**Non-Goals:**

- A "hide/collapse comments in editor view" toggle. That is a separate future change; it is what
  will re-wire `StoredCommentGutterIconRenderer` (as the collapsed indicator and edit entry point).
- Any change to how comments are stored, exported, or delivered.
- Fully tinting the line-number column *background*; the gutter signal is a colored bar (see D3).

## Decisions

### D1 — Gate the "+" on `EditorMouseEventArea`, spanning gutter sub-areas

`RelayHoverListener.mouseMoved` will add the "+" highlighter only when `e.area` is a **gutter**
area and clear it when the pointer is over `EDITING_AREA`. "Gutter" is defined as *not*
`EDITING_AREA` — i.e. `LINE_NUMBERS_AREA`, `LINE_MARKERS_AREA`, `ANNOTATIONS_AREA`,
`FOLDING_OUTLINE_AREA`.

- *Why span all gutter sub-areas rather than only `LINE_NUMBERS_AREA`:* the "+" icon renders in the
  gutter's marker column, which reports as `LINE_MARKERS_AREA`. If the trigger were the number
  column alone, moving the pointer onto the icon to click it would leave the trigger zone and clear
  the icon first. Spanning the gutter keeps it clickable — and matches the user's intent ("the left
  gutter, where line numbers appear").
- The existing draft edge-affordance branch (which suppresses the "+" on a range edge) is unchanged
  and still runs first.
- `lineAt` already maps the pointer to a document line from `xyToLogicalPosition`, which is valid for
  gutter coordinates, so line resolution needs no change — only the area gate is added.

*Alternative considered:* strict `LINE_NUMBERS_AREA` only — rejected for the click-target problem
above.

### D2 — Right-click action registered in `EditorPopupMenu`

A new `AnAction` (e.g. `AddReviewCommentAction`) registered in `plugin.xml` under group
`EditorPopupMenu`. `actionPerformed` resolves the editor from `CommonDataKeys.EDITOR`, computes the
range from the selection or the caret line, and opens the draft through
`CommentDraftController.open`.

- Reuse `CommentDraftController.rangeFor` for the selection/caret logic. `rangeFor(editor, line)`
  already returns the selection range when there is a selection; for the no-selection case the
  action passes the **caret** line (`editor.caretModel.logicalPosition.line`) as the clicked line,
  yielding a single-line range — identical semantics to a gutter click on the caret line.
- `update` disables/hides the action when there is no editor, so it does not appear in non-editor
  popups.
- This is the first `<actions>` block in `plugin.xml`.

### D3 — One shared "range + gutter" highlight; gutter signal is a `LineMarkerRenderer` bar

Factor the range highlight into a small reusable helper (e.g. `RangeHighlight`) that, given an
editor + line range + color, creates a `RangeHighlighter` with the wash `backgroundColor` **and**
attaches a `LineMarkerRenderer` that fills a colored bar in the left gutter for the range. Both the
draft and the stored-hover highlight use it, so they are visually identical by construction.

- *Draft:* `CommentDraft.createHighlighter` keeps its existing wash + `CustomHighlighterRenderer`
  edge lines, and additionally attaches the gutter `LineMarkerRenderer`. The wash color
  (`RANGE_BACKGROUND`) is reused for the bar so they read as one highlight.
- *Gutter bar, not number-column tint:* a `LineMarkerRenderer` paints reliably in the gutter's free
  paint area beside the numbers. Tinting the line-number *background* would require intercepting the
  editor's own gutter painting and risks fighting it — out of scope (see Non-Goals). The bar is the
  standard, low-risk signal (VCS-change-bar style) and satisfies "identify the commented lines from
  the gutter."

*Alternative considered:* a full line-number background tint — rejected as high-risk / non-standard.

### D4 — Stored-comment range highlight is transient, driven by card hover

`EditorReviewOverlay` gains a single transient highlight (at most one at a time) that it shows for
the comment whose card is currently hovered and disposes on exit. `StoredCommentCard.build` already
computes hover enter/exit; extend its `setToolbarVisible` hover path (or add a parallel callback) to
also call back into the overlay: `onHover(commentId, true/false)`. The overlay looks up the
comment's **live** range via its existing marker (the same source as `currentPositions()`), builds
the shared `RangeHighlight` (D3), and disposes it on `onHover(..., false)`.

- Reuses the card's proven `getMousePosition(true)` exit test so crossing into a child (body,
  toolbar button) does not flicker the highlight off.
- The transient highlight is view-only and never touches the store — consistent with the overlay's
  retained-mode, store-is-truth rule.
- At rest the stored marker stays invisible (its `null`/position-only role is unchanged apart from
  dropping the icon), so there is no permanent wash — matching the spec's "no range wash at rest."

### D5 — Drop `StoredCommentGutterIconRenderer` wiring, keep the class

`EditorReviewOverlay.addMarker` stops setting `highlighter.gutterIconRenderer`. The
`StoredCommentGutterIconRenderer` class stays in the tree, unreferenced, so the deferred
hide-comments change re-wires it as the collapsed indicator + edit entry point. Editing a stored
comment now flows only through the card's Edit action (already implemented), so no edit entry point
is lost in practice — the card is always visible.

## Risks / Trade-offs

- **[Gutter area reported differently across IDE versions/themes]** The set of `EditorMouseEventArea`
  values the platform reports for the gutter is stable API, but the exact column the "+" icon lands
  in could vary. → Spanning all non-editing gutter sub-areas (D1) is robust to that; verify in a
  running IDE that moving onto the icon keeps it shown.
- **[`LineMarkerRenderer` paint rectangle is the gutter free-area, not the number column]** The bar
  sits beside the numbers, not behind them. → Accepted; this is the standard signal and the stated
  Non-Goal covers the stronger tint. Choose a bar width/color that reads clearly in light and dark.
- **[Hover-highlight churn]** Rapid card enter/exit could thrash the transient highlighter. → It is a
  single create/dispose gated by the same `getMousePosition(true)` test the toolbar already uses; the
  cost is one `RangeHighlighter` per hover, disposed on exit.
- **[Right-click action range vs. gutter-click range parity]** Using the caret line for the
  no-selection case must match gutter-click behavior. → Both funnel through `rangeFor` semantics, so
  they stay in parity.
