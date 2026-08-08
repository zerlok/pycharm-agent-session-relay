## MODIFIED Requirements

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a comment box rendered inline as a block below the
target line range, with the target lines visually highlighted in both the code area and the
line-number gutter (a colored bar in the gutter alongside the wash over the code). The box SHALL be
sized to the width the editor actually has, by the same rule as the stored comment's card (see
`review-batch` "Render stored comments as an inline card"): never wider than the editor's available
content width, and additionally capped at a comfortable reading measure and at the editor's
configured right margin when one is set. That sizing SHALL **track** the editor rather than being
fixed when the box is opened, so a split or a window resize while the box is open re-sizes the box
instead of leaving it at the width it opened with. The box height SHALL start from a compact minimum and grow
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

#### Scenario: Box width is capped at the right margin

- **WHEN** the editor has a configured right margin and the comment box opens
- **THEN** the box is no wider than the right-margin column, regardless of the editor's full width

#### Scenario: Box is capped at a reading measure without a right margin

- **WHEN** the editor has no configured right margin (guide disabled or a non-positive column) and is
  wider than the reading measure
- **THEN** the box is no wider than the reading measure rather than spanning the full editor width

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

### Requirement: Present the authoring box as the stored card's editing state

The authoring box SHALL be presented as the editing state of the stored comment's inline card, not as
a separate kind of panel: it SHALL be filled with the same background as the card, framed by the same
single-weight outline, and padded to the same inner spacing, so that opening a comment for editing
reads as the same object changing state rather than as one panel being swapped for a different one.
Like the card, the box SHALL carry no accent-colored edge of its own.

The box's body field SHALL be framed in the plugin's accent color, marking where the user types. That
frame SHALL sit directly against the field's own background: the field SHALL paint its background
across its whole area, in the same color as the text area inside it, so that no band of the box's
fill appears between the frame and the text area.

The box SHALL offer exactly two actions: a primary action labeled **"Comment"** and a secondary
action labeled **"Cancel"**. The primary action's label SHALL be the same whether the box is
authoring a new comment or editing an existing one. The primary action SHALL be filled with the
plugin's accent, SHALL carry a label color legible against that fill, and SHALL carry an outline in
that same fill rather than the theme's plain-button border, so that it reads as one solid shape; the
secondary action SHALL remain unfilled. Neither action SHALL paint a background patch behind its own
shape — the box's fill SHALL show through around both. The action row SHALL end at the body field's
trailing edge, so the actions and the field share one right-hand alignment.

The box's body and the card's body SHALL be rendered in the **same font**, so that a comment does not
change typeface when the user opens it for editing. Neither surface SHALL take that font from the
default its Swing component class happens to inherit, because those defaults differ between the two
component kinds and would silently reintroduce the mismatch.

The plugin's accent, its filled-surface variant, the range wash, the shared surface fill and the
shared body font SHALL each be defined in exactly one place, so that the card, the box, the range
wash and the gutter bar cannot drift apart.

This requirement governs presentation only. The box's range anchoring, its width and height behavior,
its focus and keystroke ownership, and its submit and cancel semantics are governed elsewhere and are
unchanged by *this* requirement.

#### Scenario: Editing a comment keeps the card's appearance

- **WHEN** the user chooses Edit on a stored comment's card and the authoring box replaces it
- **THEN** the box is filled with the same background as the card, carries the same outline, and shows
  no accent-colored edge

#### Scenario: The body field is framed in the accent

- **WHEN** the comment box is open
- **THEN** its editable body field is framed in the plugin's accent color

#### Scenario: The accent frame hugs the input

- **WHEN** the comment box is open
- **THEN** the field's background is painted right up to its accent frame, with no band of the box's
  fill showing between the frame and the text area

#### Scenario: The primary action reads as one solid shape

- **WHEN** the comment box is open
- **THEN** the primary action's outline is the same color as its fill, not the theme's plain-button
  border

#### Scenario: The actions line up with the body field

- **WHEN** the comment box is open
- **THEN** the action row ends at the body field's trailing edge

#### Scenario: The primary action is "Comment" in both modes

- **WHEN** the box is opened to author a new comment, and when it is opened to edit an existing one
- **THEN** the primary action reads "Comment" in both cases, and never "Save"

#### Scenario: The primary action is filled in the plugin's accent

- **WHEN** the comment box is open
- **THEN** its primary action is filled with the plugin's accent color and its label is legible against
  that fill, while "Cancel" stays unfilled

#### Scenario: No background patch behind the actions

- **WHEN** the comment box is open
- **THEN** neither action paints a background rectangle behind its own shape; the box's fill shows
  through around both

#### Scenario: Submitting from the renamed action is unchanged

- **WHEN** the user presses the "Comment" action while editing an existing comment
- **THEN** that comment is updated in place, exactly as before the action was renamed

#### Scenario: The body font does not change between card and box

- **WHEN** the user reads a stored comment's card and then chooses Edit on it
- **THEN** the body text is shown in the same font in both, so the box reads as the same object in a
  different state rather than as a different panel
