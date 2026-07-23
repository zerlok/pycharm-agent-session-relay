## MODIFIED Requirements

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a comment box rendered inline as a block below the
target line range, with the target lines visually highlighted in both the code area and the
line-number gutter (a colored bar in the gutter alongside the wash over the code). The box SHALL be
sized no wider than the editor's configured right margin (its vertical guide column); when the
editor has no right margin configured (guide disabled or a non-positive column), the box SHALL fall
back to spanning the full editor width. The box height SHALL start from a compact minimum and grow
with the typed body rather than reserving a fixed multi-row floor. The target range SHALL be the
current selection **only when the target line falls within the selection's line span**; otherwise it
SHALL be that single line alone, and the selection SHALL be ignored. When the selection ends exactly
at the start of a line, that trailing line SHALL NOT be included in the resulting range — but it
SHALL still count as part of the span tested for containment, so a target line resting there resolves
to the selection. A soft-wrapped target line SHALL be highlighted across all of its visual rows, not
only its first.

#### Scenario: Comment on a single clicked line

- **WHEN** there is no selection and the user clicks the add-comment icon on a line
- **THEN** an inline comment box opens anchored to that single line (start line == end line) with
  the line highlighted in the code area and the line-number gutter

#### Scenario: Comment on a multi-line selection

- **WHEN** the user selects lines 10–15 and clicks the add-comment icon on a line within 10–15
- **THEN** an inline comment box opens anchored to lines 10–15 with those lines highlighted in the
  code area and the line-number gutter

#### Scenario: Clicking outside the selection comments the clicked line

- **WHEN** the user has lines 10–15 selected and clicks the add-comment icon on line 40 (or line 3)
- **THEN** the selection is ignored and an inline comment box opens anchored to that clicked line
  alone (start line == end line == 40), so the box appears where the user clicked

#### Scenario: Range highlight covers the gutter

- **WHEN** a comment box is open over a line range
- **THEN** the line-number gutter beside that range is highlighted with a colored bar, in addition
  to the wash over the code, so the commented lines are identifiable from the gutter

#### Scenario: Selection ending at a line start excludes the trailing line

- **WHEN** the selection ends exactly at the start offset of a line below its first line, and the
  target line is within the selection
- **THEN** that trailing line is excluded from the anchored range

#### Scenario: The trailing line still counts for containment

- **WHEN** the selection ends exactly at the start offset of line 16 (so the trimmed range is 10–15)
  and the target line is 16
- **THEN** the range resolves to the selection's trimmed range 10–15 rather than to line 16 alone

#### Scenario: A soft-wrapped target line is highlighted end to end

- **WHEN** the target line is soft-wrapped across several visual rows and the comment box opens
- **THEN** the highlight covers every visual row of that line, not only its first row

#### Scenario: Box width is capped at the right margin

- **WHEN** the editor has a configured right margin and the comment box opens
- **THEN** the box is no wider than the right-margin column, regardless of the editor's full width

#### Scenario: Box falls back to full width without a right margin

- **WHEN** the editor has no configured right margin (guide disabled or a non-positive column)
- **THEN** the comment box spans the full editor width, as before this change

#### Scenario: Short body keeps the box compact

- **WHEN** the box is opened and the body is empty or a single line
- **THEN** the box is only as tall as its compact minimum, leaving more surrounding code visible than
  a fixed multi-row floor would

### Requirement: Add a comment from the editor context menu

The plugin SHALL provide an editor context-menu (right-click) action, "Add review comment", that
opens the inline comment box for a target line range. The target range SHALL be resolved from the
caret's line by the same containment rule as the gutter "+" affordance: the current selection when
the caret's line falls within the selection's line span, otherwise the single line under the caret.
Because a caret rests at one end of its own selection, a right-click with a selection SHALL always
resolve to that selection — the two entry points stay in parity. The action SHALL be available in any
file open in an editor within the project, independent of the file's VCS change status.

#### Scenario: Right-click on a line with no selection

- **WHEN** there is no selection and the user invokes "Add review comment" from the editor
  context menu with the caret on a line
- **THEN** an inline comment box opens anchored to that single line (start line == end line)

#### Scenario: Right-click with a multi-line selection

- **WHEN** the user selects lines 10–15 and invokes "Add review comment" from the editor context menu
- **THEN** an inline comment box opens anchored to lines 10–15

#### Scenario: Right-click with the caret on the excluded trailing line

- **WHEN** the user drag-selects down to the start offset of line 16 (leaving the caret on line 16 and
  the trimmed range at 10–15) and invokes "Add review comment"
- **THEN** an inline comment box opens anchored to lines 10–15, unchanged from the gutter "+" result
  for the same selection

### Requirement: Resize the comment range by dragging its edges

While a comment box is open, the top and bottom borders of the highlighted line range SHALL be
draggable resize grips. Each edge SHALL be signalled by an N-S resize cursor when the pointer is
within a small grab zone of it. Dragging the bottom edge SHALL move the range's end line and
dragging the top edge SHALL move its start line, growing or shrinking that side live as the pointer
moves. The two edges SHALL NOT cross — the range SHALL be clamped to a minimum of one line and to
the document bounds. A press that begins on an edge SHALL claim the gesture so the editor does not
also start a text selection.

The edges SHALL be positioned by visual-row geometry: the top edge at the top of the range's first
visual row and the bottom edge at the bottom of the range's **last** visual row, so a soft-wrapped
logical line is treated as one line occupying all of its visual rows. The same geometry SHALL govern
edge hit-testing and the mapping from a drag position back to a line, so that pointing anywhere in any
visual row of a soft-wrapped line resolves to that one logical line and releasing on a row makes that
row's line the range's new boundary. Both edges SHALL be drawn inside the highlighted range rather
than centred on its boundary, so the bottom edge remains visible while the comment box is open
directly beneath it.

#### Scenario: Grow the range from the bottom edge

- **WHEN** a comment box is open on line 10 and the user drags the bottom edge down to line 12
- **THEN** the highlighted range becomes lines 10–12

#### Scenario: Shrink the range from the top edge

- **WHEN** a comment box is open on lines 8–12 and the user drags the top edge down to line 10
- **THEN** the highlighted range becomes lines 10–12

#### Scenario: Bottom edge sits below all rows of a wrapped line

- **WHEN** the range's last line is soft-wrapped across several visual rows
- **THEN** the bottom edge is drawn at the bottom of its last visual row, not after its first, so the
  edge closes the highlighted region rather than crossing it

#### Scenario: Dragging over a wrapped line resolves to that one line

- **WHEN** the user drags an edge onto any visual row of a soft-wrapped logical line
- **THEN** the range boundary becomes that logical line — it neither stops at the line's first row nor
  skips to the line after all of its rows

#### Scenario: Bottom edge stays visible under the open box

- **WHEN** a comment box is open below the range
- **THEN** the range's bottom edge is still visible above the box, so the highlighted region reads as
  closed rather than open-ended

#### Scenario: Range cannot collapse below one line

- **WHEN** the user drags an edge past the opposite edge
- **THEN** the range is clamped to a single line rather than inverting or disappearing

#### Scenario: Edge drag does not select editor text

- **WHEN** the user presses a range edge and drags to resize
- **THEN** the range resizes and no editor text selection is created by the drag
