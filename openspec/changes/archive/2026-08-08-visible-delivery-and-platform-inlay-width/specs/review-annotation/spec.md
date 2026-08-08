## MODIFIED Requirements

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a comment box rendered inline as a block below the
target line range, with the target lines visually highlighted in both the code area and the
line-number gutter (a colored bar in the gutter alongside the wash over the code). The box SHALL be
sized to the width the editor actually has, by the same rule as the stored comment's card (see
`review-batch` "Render stored comments as an inline card"): sizing delegated to the platform's own
viewport-fitting inline component placement, never wider than the editor's available content width
less any part of it covered by the editor's floating inspections widget, and additionally capped at a
comfortable reading measure. The box SHALL NOT be capped at the editor's
configured right margin. That sizing SHALL **track** the editor rather than being fixed when the box
is opened, so a split or a window resize while the box is open re-sizes the box instead of leaving it
at the width it opened with. The box height SHALL start from a compact minimum and grow
with the typed body rather than reserving a fixed multi-row floor. That height change SHALL be
applied on the edit that changes the body: when the body gains or loses a visual line, the box SHALL
re-measure and the code below it SHALL reflow as part of handling that edit, without waiting for an
unrelated repaint, resize, or layout pass. This SHALL hold whether the extra line came from a typed
newline or from soft-wrapping a long line in which no newline was typed. The target range SHALL be the
current selection **only when the target line falls within the selection's line span**; otherwise it
SHALL be that single line alone, and the selection SHALL be ignored. When the selection ends exactly
at the start of a line, that trailing line SHALL NOT be included in the resulting range — but it
SHALL still count as part of the span tested for containment, so a target line resting there resolves
to the selection.

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

#### Scenario: Box is capped at a reading measure

- **WHEN** the editor is wider than the reading measure and the comment box opens
- **THEN** the box is no wider than the reading measure rather than spanning the full editor width

#### Scenario: Box stays clear of the inspections widget

- **WHEN** the editor is narrower than the reading measure, so the box fills the available width, and
  the editor's inspections widget floats over the trailing edge of the viewport
- **THEN** the box ends where that widget begins, so its action row is neither covered by it nor
  pushed out of reach

#### Scenario: A narrow right margin does not narrow the box

- **WHEN** the editor has a right margin configured well below the reading measure, and the editor is
  wider than the reading measure
- **THEN** the box opens at the reading measure — the right-margin column does not cap it

#### Scenario: Open box follows the editor when it narrows

- **WHEN** the comment box is open and the editor's available width shrinks below the box's current
  width — for example the user splits the editor to the right
- **THEN** the box is re-sized to the narrower width, its body re-wraps to fit, and its action row
  stays inside the visible editor area

#### Scenario: Short body keeps the box compact

- **WHEN** the box is opened and the body is empty or a single line
- **THEN** the box is only as tall as its compact minimum, leaving more surrounding code visible than
  a fixed multi-row floor would

#### Scenario: Box grows on the keystroke that adds a line

- **WHEN** the user presses Enter in the comment body
- **THEN** the box is one line taller and the code below it has moved down as part of that keystroke,
  not after the user stops typing or after some unrelated interaction

#### Scenario: Box grows when a long line soft-wraps

- **WHEN** the user keeps typing one long line with no newline until it soft-wraps in the body
- **THEN** the box grows to show the wrapped line immediately, the same as if a newline had been typed

#### Scenario: Box shrinks when body lines are removed

- **WHEN** the user deletes body text so the body occupies fewer visual lines
- **THEN** the box shrinks immediately, down to (but not below) its compact minimum, and the code
  below moves back up

#### Scenario: Live resize survives a range resize

- **WHEN** the user drags a range edge to resize (which hides and rebuilds the box), releases, and
  then types a newline in the rebuilt box
- **THEN** the rebuilt box grows on that keystroke exactly as it did before the drag
