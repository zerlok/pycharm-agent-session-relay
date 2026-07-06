## MODIFIED Requirements

### Requirement: Capture a multi-line body with submit and cancel

The comment box SHALL provide a multi-line editable field and SHALL route focus into it when the box
opens. While the field is focused, it SHALL own **all** text-editing keystrokes — character entry,
newline (Enter), caret movement, selection extension (e.g. Shift+Arrow), word navigation and word
deletion (e.g. Ctrl+Arrow, Ctrl+W, Ctrl+Backspace), Backspace/Delete, and clipboard (cut/copy/paste)
— such that each acts on the comment body and never on the underlying editor. In addition the box
SHALL handle Ctrl+Enter or Cmd+Enter (or an "Add review comment" button) to submit, and Esc (or a
"Cancel" button) to cancel.

#### Scenario: Enter inserts a newline in the box

- **WHEN** the field is focused and the user presses Enter
- **THEN** a newline is inserted in the comment body and the underlying editor text is unchanged

#### Scenario: Editing shortcuts act on the box, not the editor

- **WHEN** the field is focused and the user presses any text-editing shortcut — Shift+Arrow,
  Ctrl+Arrow, Ctrl+W, Backspace, Ctrl+Backspace, Delete, or a clipboard cut/copy/paste
- **THEN** the action applies to the comment body (moving/selecting/deleting/pasting within it) and
  the underlying editor's text and selection are unchanged

#### Scenario: Submit with the keyboard

- **WHEN** the field is focused and the user presses Ctrl+Enter (or Cmd+Enter)
- **THEN** the comment is submitted

#### Scenario: Cancel with Esc

- **WHEN** the field is focused and the user presses Esc
- **THEN** the box closes, no comment is captured, and the editor is unchanged

## ADDED Requirements

### Requirement: Move editing focus between the box and the editor

The user SHALL be able to move input focus out of the comment box and into the underlying editor, and
back, without the box being dismissed. Clicking a code line outside the box SHALL move focus to the
editor so that subsequent editing keystrokes act on the code; the comment box SHALL remain open with
its typed body intact. Clicking within the box SHALL return focus to the box so that subsequent
editing keystrokes again act on the comment body. The box SHALL be dismissed only by submit, cancel,
or opening another box — never by losing focus.

#### Scenario: Clicking the editor moves editing to the code, box stays

- **WHEN** the comment box is open and focused and the user clicks a line in the underlying editor
- **THEN** the box remains open with its body unchanged, and subsequent editing keystrokes act on the
  editor's code rather than the comment body

#### Scenario: Clicking the box returns editing to it

- **WHEN** focus has moved to the editor while the box is open and the user then clicks within the box
- **THEN** focus returns to the box and subsequent editing keystrokes act on the comment body again

#### Scenario: Losing focus does not dismiss the box

- **WHEN** the comment box loses input focus to the editor
- **THEN** the box remains open and its typed body is preserved
