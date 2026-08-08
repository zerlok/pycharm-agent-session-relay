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

### Requirement: Comment on any open file

Authoring SHALL work in any file open in an editor within the project, regardless of the file's
VCS change status. Authoring SHALL NOT depend on the change view or any other capture mode.

#### Scenario: Comment on an unchanged file

- **WHEN** the user authors a comment in a file that has no pending VCS changes
- **THEN** the comment box opens and captures the comment the same as for a changed file

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
only for a fixed list of key strokes. Undo SHALL NOT reach past the body the box opened with: the text
the box is seeded with — empty when authoring, the stored body when editing an existing comment (see
"Edit an existing comment") — is the undo **baseline**, not an edit to be rolled back. Undo invoked in
a freshly opened box SHALL therefore do nothing, and undo after typing SHALL stop at that baseline
rather than continuing past it to an empty body. In addition the box SHALL handle Ctrl+Enter or
Cmd+Enter (or the box's primary action button) to submit, and Esc (or a "Cancel" button) to cancel.
The primary action's label and styling are specified by "Present the authoring box as the stored
card's editing state".

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

#### Scenario: Undo in a reopened comment does not clear the stored body

- **WHEN** the user submits a comment, reopens it for editing, and invokes undo before typing anything
- **THEN** nothing happens — the box still shows the stored body, which is not rolled back to empty

#### Scenario: Undo after revising a stored comment stops at the stored body

- **WHEN** the user reopens a stored comment, revises the body, and invokes undo repeatedly
- **THEN** the revisions are undone one by one down to the stored body, and further undo does not
  empty the box

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
`ReviewComment` — carrying the file path, the target line range, the body, the anchor text, and a
context hash. The record itself SHALL hold no live platform object (see `review-batch` "Represent a
comment with an open subject and anchoring data").

The target line range stored on submit SHALL be the range the box's **live range highlight** occupies
at the moment of submit, not the range that was computed when the box was opened. The highlight is
the box's position source while the box is open, so any shift it absorbs — an edit elsewhere in the
document, a refresh of the file from disk — SHALL be reflected in the stored comment. Submitting SHALL
remain safe when the document has shrunk below the box's original range: the comment SHALL be stored
at the highlight's current range rather than failing.

The anchor text and context hash SHALL be captured from that same live range, so a comment's anchoring
data always describes the lines it was actually stored against.

A stored-comment position marker SHALL then be maintained on the commented line range as the live
position source and as the host of the resting gutter bar, **without** a visible gutter icon; an
always-expanded read-only inline card carrying the comment body SHALL appear under that range; the
comment SHALL appear in the tool window; and the comment's range SHALL be revealed on hover of its
card (per "Highlight a stored comment's range on card hover").

That position marker SHALL be maintained **once per document**, not once per open editor. A file shown
in more than one editor — a split, or a second window — SHALL therefore carry exactly one marker per
comment, and the resting gutter bar SHALL be painted once in each of those editors rather than
stacked. Inline cards remain per-editor, since an inlay belongs to the editor that shows it.

#### Scenario: Submitting adds the comment to the batch

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes and a `ReviewComment` for that range is added to the store

#### Scenario: Submit stores the live range, not the opening range

- **WHEN** the box is open over lines 40–42, the document is then changed so that the box's range
  highlight moves to lines 45–47, and the user submits
- **THEN** the stored comment is anchored to lines 45–47, and its anchor text is the text of those
  lines

#### Scenario: Submit survives the document shrinking under the box

- **WHEN** the box is open over a range near the end of the file and the document is replaced by a
  shorter one before the user submits
- **THEN** the comment is stored at the range the live highlight now occupies, and no error is raised

#### Scenario: One marker per document across splits

- **WHEN** a file with a stored comment is shown in two editor splits
- **THEN** exactly one position marker exists for that comment, and each split shows a single resting
  gutter bar over the commented range

#### Scenario: No stored-comment gutter icon appears

- **WHEN** a comment is submitted
- **THEN** no persistent add/marker gutter icon is shown for the stored comment; its resting indicators
  are the inline card and the accent gutter bar, and hovering the card additionally washes its range

#### Scenario: Submitted comment stays visible as an inline card

- **WHEN** the user submits a comment
- **THEN** the authored body remains visible in the editor as an always-expanded read-only inline
  card rendered under the commented line range

#### Scenario: Submitted comment appears in the tool window

- **WHEN** a comment is submitted
- **THEN** it is listed in the tool window under its file, showing the line range and a body snippet

### Requirement: Highlight a stored comment's range on card hover

A stored comment SHALL display a resting signal for its current line range in the line-number gutter:
a colored bar in the plugin's accent color — the one place that accent marks a commented range, per
"Render stored comments as an inline card" — so its commented lines are identifiable at a glance
without hovering. That resting signal SHALL be gutter-only — a stored comment
SHALL NOT display a wash over the code area at rest, and SHALL NOT display a gutter *icon*.

While the pointer is over a stored comment's read-only inline card, the plugin SHALL additionally
highlight that comment's current line range in the editor using the same visual as the draft range
highlight — the wash over the code area plus the colored bar in the line-number gutter — and SHALL
remove that additional highlight when the pointer leaves the card, leaving the resting gutter bar in
place. The highlighted range SHALL reflect the comment's current (live) position, and the resting
gutter bar SHALL track the comment's position as the file is edited in the IDE.

A comment marked `ORPHANED` — one whose recorded range does not exist in the current document — SHALL
display neither a resting gutter bar nor a card, since it has no range to point at. It remains listed
in the tool window.

#### Scenario: Commented lines are marked in the gutter at rest

- **WHEN** a stored comment exists for a line range in an open editor and no card is hovered
- **THEN** a colored bar in the accent color marks that range in the line-number gutter

#### Scenario: Hovering the card reveals the commented lines

- **WHEN** the pointer moves over a stored comment's inline card
- **THEN** that comment's line range is highlighted in the editor, in both the code area and the
  line-number gutter

#### Scenario: Leaving the card clears the hover highlight

- **WHEN** the pointer leaves the stored comment's card
- **THEN** the code-area wash is removed and the lines return to their resting appearance, still
  marked by the accent gutter bar

#### Scenario: No range wash at rest

- **WHEN** a stored comment is displayed and its card is not hovered
- **THEN** no range wash is shown over its lines; the resting indicators are the inline card and the
  gutter bar

#### Scenario: Resting gutter bar follows in-IDE edits

- **WHEN** lines are inserted above a commented range, shifting its live position
- **THEN** the resting gutter bar moves with the range, matching the position the card's hover
  highlight would show

#### Scenario: An orphaned comment paints nothing in the editor

- **WHEN** a stored comment's recorded range does not exist in the open document
- **THEN** no resting gutter bar and no card are rendered for it, and it is still listed in the tool
  window

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

The edges SHALL be positioned by visual-row geometry: the top edge at the top of the range's first
visual row and the bottom edge at the bottom of the range's **last** visual row, so that the two
edges bracket every visual row of a soft-wrapped boundary line rather than its first row alone. The
same geometry SHALL govern edge hit-testing and the mapping from a drag position back to a line, so
that pointing anywhere in any visual row of a soft-wrapped line resolves to that one logical line and
releasing on a row makes that row's line the range's new boundary. Both edges SHALL be drawn inside
the highlighted range rather than centred on its boundary, so the bottom edge remains visible while
the comment box is open directly beneath it.

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

