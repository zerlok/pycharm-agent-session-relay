# review-annotation Specification

## Purpose
The annotation surface — the first relay stage (`Comment`). Reveal an add-comment affordance on
hover and author a line-anchored comment in an inline box over any open file. Collecting, displaying,
exporting, and delivering those comments are the later stages (`review-batch`, `review-export`,
`review-delivery`).
## Requirements
### Requirement: Reveal an add-comment affordance on hover

The plugin SHALL display a single "add review comment" gutter icon on the editor line under the
mouse pointer, but ONLY while the pointer is over the editor's left gutter (the line-number strip
and its adjacent gutter sub-areas), and SHALL NOT display it while the pointer is over the code
content (editing) area. The plugin SHALL move or remove the icon as the pointer moves. The trigger
zone SHALL span the gutter's sub-areas (line numbers, markers, folding) rather than the
line-number column alone, so that moving the pointer onto the icon itself does not dismiss it before
it can be clicked. At most one such affordance is shown within the project at a time.

#### Scenario: Icon appears when hovering the gutter

- **WHEN** the mouse pointer rests over the left gutter of a line in an open editor
- **THEN** an add-comment "+" gutter icon is shown on that line

#### Scenario: Icon suppressed over code content

- **WHEN** the mouse pointer is over the code content (editing) area of a line
- **THEN** no add-comment "+" icon is shown

#### Scenario: Icon stays reachable when moving onto it

- **WHEN** the pointer moves from the line-number strip onto the "+" icon in the gutter
- **THEN** the icon remains shown and can be clicked

#### Scenario: Only one affordance at a time

- **WHEN** the pointer moves from one line's gutter to another line's gutter
- **THEN** the icon is shown on the new line and removed from the previous line

#### Scenario: Affordance removed on exit

- **WHEN** the pointer leaves the editor area
- **THEN** the add-comment icon is removed

### Requirement: Author a line-anchored comment in an inline box

Clicking the add-comment affordance SHALL open a comment box rendered inline as a block below the
target line range, with the target lines visually highlighted in both the code area and the
line-number gutter (a colored bar in the gutter alongside the wash over the code). The box SHALL be
sized no wider than the editor's configured right margin (its vertical guide column); when the
editor has no right margin configured (guide disabled or a non-positive column), the box SHALL fall
back to spanning the full editor width. The box height SHALL start from a compact minimum and grow
with the typed body rather than reserving a fixed multi-row floor. The target range SHALL be the
current selection when one exists, otherwise the single clicked line. When the selection ends
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

### Requirement: Comment on any open file

Authoring SHALL work in any file open in an editor within the project, regardless of the file's
VCS change status. Authoring SHALL NOT depend on the change view or any other capture mode.

#### Scenario: Comment on an unchanged file

- **WHEN** the user authors a comment in a file that has no pending VCS changes
- **THEN** the comment box opens and captures the comment the same as for a changed file

### Requirement: Add a comment from the editor context menu

The plugin SHALL provide an editor context-menu (right-click) action, "Add review comment", that
opens the inline comment box for a target line range. The target range SHALL be the current
selection when one exists, otherwise the single line under the caret — the same range rule as the
gutter "+" affordance. The action SHALL be available in any file open in an editor within the
project, independent of the file's VCS change status.

#### Scenario: Right-click on a line with no selection

- **WHEN** there is no selection and the user invokes "Add review comment" from the editor
  context menu with the caret on a line
- **THEN** an inline comment box opens anchored to that single line (start line == end line)

#### Scenario: Right-click with a multi-line selection

- **WHEN** the user selects lines 10–15 and invokes "Add review comment" from the editor context menu
- **THEN** an inline comment box opens anchored to lines 10–15

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

### Requirement: A single active comment box

At most one comment box SHALL be open at a time. Opening a new box — whether to author a new comment
or to edit an existing one — SHALL close any box that is already open.

#### Scenario: Opening a second box closes the first

- **WHEN** a comment box is open and the user opens another via the add-comment affordance
- **THEN** the previous box is closed and only the new box remains

#### Scenario: Opening an edit box closes an open authoring box

- **WHEN** a comment box is open and the user opens an existing comment for editing
- **THEN** the previously open box is closed and only the edit box remains

### Requirement: Surface the captured comment on submit

On submit, the box SHALL close and the captured comment SHALL be added to the pending batch as a
`ReviewComment` — carrying the file path, the target line range, the body, a live `RangeMarker`
created from the range, the anchor text, and a context hash. A stored-comment position marker SHALL
then be maintained on the commented line range as the live position source, **without** a visible
gutter icon; an always-expanded read-only inline card carrying the comment body SHALL appear under
that range; the comment SHALL appear in the tool window; and the comment's range SHALL be revealed
on hover of its card (per "Highlight a stored comment's range on card hover"). (The baseline's
report-only behavior — a confirmation notification plus a log entry — is superseded by this
persistence.)

#### Scenario: Submitting adds the comment to the batch

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes and a `ReviewComment` for that range is added to the store

#### Scenario: No stored-comment gutter icon appears

- **WHEN** a comment is submitted
- **THEN** no persistent add/marker gutter icon is shown for the stored comment; the inline card is
  its resting indicator and hovering the card reveals its range

#### Scenario: Submitted comment stays visible as an inline card

- **WHEN** the user submits a comment
- **THEN** the authored body remains visible in the editor as an always-expanded read-only inline
  card rendered under the commented line range

#### Scenario: Submitted comment appears in the tool window

- **WHEN** a comment is submitted
- **THEN** it is listed in the tool window under its file, showing the line range and a body snippet

### Requirement: Highlight a stored comment's range on card hover

A stored comment SHALL NOT display a persistent range highlight at rest. While the pointer is over a
stored comment's read-only inline card, the plugin SHALL highlight that comment's current line range
in the editor using the same visual as the draft range highlight — the wash over the code area plus
the colored bar in the line-number gutter — and SHALL remove that highlight when the pointer leaves
the card. The highlighted range SHALL reflect the comment's current (live) position.

#### Scenario: Hovering the card reveals the commented lines

- **WHEN** the pointer moves over a stored comment's inline card
- **THEN** that comment's line range is highlighted in the editor, in both the code area and the
  line-number gutter

#### Scenario: Leaving the card clears the highlight

- **WHEN** the pointer leaves the stored comment's card
- **THEN** the range highlight is removed and the lines return to their normal appearance

#### Scenario: No range wash at rest

- **WHEN** a stored comment is displayed and its card is not hovered
- **THEN** no range wash is shown over its lines (the card is the only resting indicator)

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

### Requirement: Edit an existing comment

The plugin SHALL let the user re-open a stored comment for editing from its inline card (via the
card's Edit action). Editing SHALL open the same authoring box seeded with the comment's current
body and its current line range, with the range remaining adjustable (per
`adjustable-comment-range`). While the edit box is open, the edited comment's read-only inline card
SHALL be suppressed so the card and its edit box never overlap. Submitting SHALL update the existing
comment in place — replacing its body, and its position with the (possibly resized) range — rather
than creating a second comment; the stored comment's identity SHALL be preserved. Cancelling SHALL
leave the comment unchanged. On either submit or cancel, the read-only card SHALL reappear.

#### Scenario: Editing re-opens the box seeded with the current body

- **WHEN** the user chooses Edit on a stored comment's card
- **THEN** the authoring box opens over that comment's line range pre-filled with its current body,
  and its read-only card is hidden while the box is open

#### Scenario: Submitting an edit updates the comment in place

- **WHEN** the user edits the body (and/or resizes the range) and submits
- **THEN** the same comment's body and position are updated, no additional comment is created, and
  the refreshed read-only card reappears with the new body

#### Scenario: Cancelling an edit preserves the original

- **WHEN** the user opens a comment for editing and then cancels
- **THEN** the comment's stored body and range are unchanged and its read-only card reappears as it was

