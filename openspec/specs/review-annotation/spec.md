# review-annotation Specification

## Purpose
The annotation surface — the first relay stage (`Comment`). Reveal an add-comment affordance on
hover and author a line-anchored comment in an inline box over any open file. Collecting, displaying,
exporting, and delivering those comments are the later stages (`review-batch`, `review-export`,
`review-delivery`).
## Requirements
### Requirement: Reveal an add-comment affordance on hover

The plugin SHALL display a single "add review comment" gutter icon on the editor line under the
mouse pointer, and SHALL move or remove it as the pointer moves. At most one such affordance is
shown within the project at a time.

#### Scenario: Icon appears on the hovered line

- **WHEN** the mouse pointer rests over a line in an open editor
- **THEN** an add-comment "+" gutter icon is shown on that line

#### Scenario: Only one affordance at a time

- **WHEN** the pointer moves from one line to another
- **THEN** the icon is shown on the new line and removed from the previous line

#### Scenario: Affordance removed on exit

- **WHEN** the pointer leaves the editor area
- **THEN** the add-comment icon is removed

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a full-width comment box rendered inline as a block
below the target line range, with the target lines visually highlighted. The target range SHALL be
the current selection when one exists, otherwise the single clicked line. When the selection ends
exactly at the start of a line, that trailing line SHALL NOT be included in the range.

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

### Requirement: Comment on any open file

Authoring SHALL work in any file open in an editor within the project, regardless of the file's
VCS change status. Authoring SHALL NOT depend on the change view or any other capture mode.

#### Scenario: Comment on an unchanged file

- **WHEN** the user authors a comment in a file that has no pending VCS changes
- **THEN** the comment box opens and captures the comment the same as for a changed file

### Requirement: Capture a multi-line body with submit and cancel

The comment box SHALL provide a multi-line text area and SHALL route focus into it when the box
opens. While the text area is focused, the box SHALL handle: Enter and Shift+Enter to insert a
newline, Ctrl+Enter or Cmd+Enter (or an "Add review comment" button) to submit, and Esc (or a
"Cancel" button) to cancel — each acting on the box rather than the underlying editor.

#### Scenario: Enter inserts a newline in the box

- **WHEN** the text area is focused and the user presses Enter
- **THEN** a newline is inserted in the comment body and the underlying editor text is unchanged

#### Scenario: Submit with the keyboard

- **WHEN** the text area is focused and the user presses Ctrl+Enter (or Cmd+Enter)
- **THEN** the comment is submitted

#### Scenario: Cancel with Esc

- **WHEN** the text area is focused and the user presses Esc
- **THEN** the box closes, no comment is captured, and the editor is unchanged

### Requirement: A single active comment box

At most one comment box SHALL be open at a time. Opening a new box SHALL close any box that is
already open.

#### Scenario: Opening a second box closes the first

- **WHEN** a comment box is open and the user opens another via the add-comment affordance
- **THEN** the previous box is closed and only the new box remains

### Requirement: Surface the captured comment on submit

On submit, the box SHALL close and the captured comment SHALL be added to the pending batch as a
`ReviewComment` — carrying the file path, the target line range, the body, a live `RangeMarker`
created from the range, the anchor text, and a context hash. A stored-comment gutter marker SHALL
then appear on the commented line range and the comment SHALL appear in the tool window. (The
baseline's report-only behavior — a confirmation notification plus a log entry — is superseded by
this persistence.)

#### Scenario: Submitting adds the comment to the batch

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes, a `ReviewComment` for that range is added to the store, and a
  stored-comment gutter marker appears on the range

#### Scenario: Submitted comment appears in the tool window

- **WHEN** a comment is submitted
- **THEN** it is listed in the tool window under its file, showing the line range and a body snippet

### Requirement: Refresh synced files before review

The plugin SHALL provide a "Refresh & review" action that triggers an asynchronous VFS refresh so
edits written to disk (by a local agent, or synced in from a remote sandbox) become visible before
the user reviews them.

#### Scenario: Refresh surfaces synced edits

- **WHEN** the agent has edited files on disk (locally, or synced from the sandbox) and the user
  invokes "Refresh & review"
- **THEN** the IDE reloads those files from disk so their current content and change status are shown

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

