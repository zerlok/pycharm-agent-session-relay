## MODIFIED Requirements

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a comment box rendered inline as a block below the
target line range, with the target lines visually highlighted. The box SHALL be sized no wider than
the editor's configured right margin (its vertical guide column); when the editor has no right margin
configured (guide disabled or a non-positive column), the box SHALL fall back to spanning the full
editor width. The box height SHALL start from a compact minimum and grow with the typed body rather
than reserving a fixed multi-row floor. The target range SHALL be the current selection when one
exists, otherwise the single clicked line. When the selection ends exactly at the start of a line,
that trailing line SHALL NOT be included in the range.

#### Scenario: Comment on a single clicked line

- **WHEN** there is no selection and the user clicks the add-comment icon on a line
- **THEN** an inline comment box opens anchored to that single line (start line == end line) with
  the line highlighted

#### Scenario: Comment on a multi-line selection

- **WHEN** the user selects lines 10–15 and clicks the add-comment icon
- **THEN** an inline comment box opens anchored to lines 10–15 with those lines highlighted

#### Scenario: Selection ending at a line start excludes the trailing line

- **WHEN** the selection ends exactly at the start offset of a line below its first line
- **THEN** that trailing line is excluded from the anchored range

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
