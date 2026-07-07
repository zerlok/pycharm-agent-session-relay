## ADDED Requirements

### Requirement: Resize the comment range by dragging its edges

While a comment box is open, the top and bottom borders of the highlighted line range SHALL be
draggable resize grips. Each edge SHALL be signalled by an N-S resize cursor when the pointer is
within a small grab zone of it. Dragging the bottom edge SHALL move the range's end line and
dragging the top edge SHALL move its start line, growing or shrinking that side live as the pointer
moves. The two edges SHALL NOT cross — the range SHALL be clamped to a minimum of one line and to
the document bounds. A press that begins on an edge SHALL claim the gesture so the editor does not
also start a text selection.

#### Scenario: Grow the range from the bottom edge

- **WHEN** a comment box is open on line 10 and the user drags the bottom edge down to line 12
- **THEN** the highlighted range becomes lines 10–12

#### Scenario: Shrink the range from the top edge

- **WHEN** a comment box is open on lines 8–12 and the user drags the top edge down to line 10
- **THEN** the highlighted range becomes lines 10–12

#### Scenario: Range cannot collapse below one line

- **WHEN** the user drags an edge past the opposite edge
- **THEN** the range is clamped to a single line rather than inverting or disappearing

#### Scenario: Edge drag does not select editor text

- **WHEN** the user presses a range edge and drags to resize
- **THEN** the range resizes and no editor text selection is created by the drag

### Requirement: Hide the comment box while resizing the range

Pressing a range edge SHALL hide the comment box for the duration of the drag so the code being
sized is unobstructed. The highlighted range SHALL update live while the box is hidden. On release,
the box SHALL reappear positioned under the range's bottom line, with any body text typed before the
drag preserved and input focus returned to the box.

#### Scenario: Box hidden during the drag

- **WHEN** the user presses a range edge to begin resizing
- **THEN** the comment box is hidden and the lines under it are fully visible while the drag continues

#### Scenario: Box reappears under the new bottom line

- **WHEN** the user releases the edge after resizing the range
- **THEN** the comment box reappears directly under the range's current bottom line

#### Scenario: In-progress body is preserved across the drag

- **WHEN** the user has typed body text, then resizes the range and releases
- **THEN** the reappeared box still contains the previously typed text and holds input focus
