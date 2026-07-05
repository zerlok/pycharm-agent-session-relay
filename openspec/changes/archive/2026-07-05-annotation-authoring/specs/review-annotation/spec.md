## ADDED Requirements

### Requirement: Reveal an add-comment affordance on hover

The plugin SHALL display a single "add review comment" gutter icon on the editor line under the
mouse pointer, and SHALL move or remove it as the pointer moves. At most one such affordance is
shown across all editors at a time.

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

On submit, the box SHALL close and the captured comment — the file path, the (1-based) target line
range, and the body — SHALL be surfaced to the user. (Persisting the comment into a review batch
and exporting it are later relay stages; in the baseline the captured comment is reported via a
confirmation notification and a log entry.)

#### Scenario: Submitting reports the captured comment

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes and a confirmation identifying the file and line range is shown to the user
