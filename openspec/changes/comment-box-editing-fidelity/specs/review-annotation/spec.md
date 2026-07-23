## MODIFIED Requirements

### Requirement: Capture a multi-line body with submit and cancel

The comment box SHALL provide a multi-line editable field and SHALL route focus into it when the box
opens. While the field is focused, it SHALL own **all** text-editing keystrokes — character entry,
newline (Enter), caret movement, selection extension (e.g. Shift+Arrow), word navigation and word
deletion (e.g. Ctrl+Arrow, Ctrl+W, Ctrl+Backspace), Backspace/Delete, clipboard (cut/copy/paste), and
undo/redo (e.g. Ctrl+Z, Ctrl+Shift+Z, Cmd+Z) — such that each acts on the comment body and never on
the underlying editor. Undo and redo SHALL be scoped to the comment body specifically: while the
field is focused, an undo or redo SHALL NOT modify the underlying source file's text and SHALL NOT
consume, replay, or reorder the underlying file's undo history. When input focus moves back to the
underlying editor, undo and redo SHALL again act on that file as normal. This scoping SHALL hold for
every binding of undo/redo the IDE offers — any keymap, the Edit menu, the editor context menu — not
only for a fixed list of key strokes. In addition the box SHALL handle Ctrl+Enter or Cmd+Enter (or an
"Add review comment" button) to submit, and Esc (or a "Cancel" button) to cancel.

#### Scenario: Enter inserts a newline in the box

- **WHEN** the field is focused and the user presses Enter
- **THEN** a newline is inserted in the comment body and the underlying editor text is unchanged

#### Scenario: Editing shortcuts act on the box, not the editor

- **WHEN** the field is focused and the user presses any text-editing shortcut — Shift+Arrow,
  Ctrl+Arrow, Ctrl+W, Backspace, Ctrl+Backspace, Delete, or a clipboard cut/copy/paste
- **THEN** the action applies to the comment body (moving/selecting/deleting/pasting within it) and
  the underlying editor's text and selection are unchanged

#### Scenario: Undo in the box never edits the source file

- **WHEN** the field is focused, the user has typed body text, and the user invokes undo (Ctrl+Z, or
  any other binding of the undo action)
- **THEN** the underlying source file's text is unchanged and its own undo history is not consumed

#### Scenario: Undo in the box reverts the comment body

- **WHEN** the field is focused, the user has typed body text, and the user invokes undo
- **THEN** the last edit to the comment body is reverted, and a subsequent redo restores it

#### Scenario: Undo returns to the file when focus does

- **WHEN** the comment box is open, the user clicks a line in the underlying editor to move focus
  there, and then invokes undo
- **THEN** undo acts on the underlying file as it normally would, and the comment box stays open with
  its body intact

#### Scenario: Undo scoping survives a range resize

- **WHEN** the user types body text, drags a range edge to resize (which hides and rebuilds the box),
  releases, and then invokes undo with the rebuilt box focused
- **THEN** undo still acts on the comment body and the underlying source file is unchanged

#### Scenario: Submit with the keyboard

- **WHEN** the field is focused and the user presses Ctrl+Enter (or Cmd+Enter)
- **THEN** the comment is submitted

#### Scenario: Cancel with Esc

- **WHEN** the field is focused and the user presses Esc
- **THEN** the box closes, no comment is captured, and the editor is unchanged

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a comment box rendered inline as a block below the
target line range, with the target lines visually highlighted in both the code area and the
line-number gutter (a colored bar in the gutter alongside the wash over the code). The box SHALL be
sized no wider than the editor's configured right margin (its vertical guide column); when the
editor has no right margin configured (guide disabled or a non-positive column), the box SHALL fall
back to spanning the full editor width. The box height SHALL start from a compact minimum and grow
with the typed body rather than reserving a fixed multi-row floor. That height change SHALL be
applied on the edit that changes the body: when the body gains or loses a visual line, the box SHALL
re-measure and the code below it SHALL reflow as part of handling that edit, without waiting for an
unrelated repaint, resize, or layout pass. This SHALL hold whether the extra line came from a typed
newline or from soft-wrapping a long line in which no newline was typed. The target range SHALL be
the current selection when one exists, otherwise the single clicked line. When the selection ends
exactly at the start of a line, that trailing line SHALL NOT be included in the range.

#### Scenario: Comment on a single clicked line

- **WHEN** there is no selection and the user clicks the add-comment icon on a line
- **THEN** an inline comment box opens anchored to that single line (start line == end line) with
  the line highlighted in the code area and the line-number gutter

#### Scenario: Comment on a multi-line selection

- **WHEN** the user selects lines 10–15 and clicks the add-comment icon
- **THEN** an inline comment box opens anchored to lines 10–15 with those lines highlighted in the
  code area and the line-number gutter

#### Scenario: Range highlight covers the gutter

- **WHEN** a comment box is open over a line range
- **THEN** the line-number gutter beside that range is highlighted with a colored bar, in addition
  to the wash over the code, so the commented lines are identifiable from the gutter

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
