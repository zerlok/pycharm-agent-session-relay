## MODIFIED Requirements

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

### Requirement: Surface the captured comment on submit

On submit, the box SHALL close and the captured comment SHALL be added to the pending batch as a
`ReviewComment` — carrying the file path, the target line range, the body, a live `RangeMarker`
created from the range, the anchor text, and a context hash. A stored-comment position marker SHALL
then be maintained on the commented line range as the live position source and as the host of the
resting gutter bar, **without** a visible gutter icon; an always-expanded read-only inline card
carrying the comment body SHALL appear under that range; the comment SHALL appear in the tool window;
and the comment's range SHALL be revealed on hover of its card (per "Highlight a stored comment's
range on card hover"). (The baseline's report-only behavior — a confirmation notification plus a log
entry — is superseded by this persistence.)

#### Scenario: Submitting adds the comment to the batch

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes and a `ReviewComment` for that range is added to the store

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

### Requirement: Capture a multi-line body with submit and cancel

The comment box SHALL provide a multi-line editable field and SHALL route focus into it when the box
opens. While the field is focused, it SHALL own **all** text-editing keystrokes — character entry,
newline (Enter), caret movement, selection extension (e.g. Shift+Arrow), word navigation and word
deletion (e.g. Ctrl+Arrow, Ctrl+W, Ctrl+Backspace), Backspace/Delete, and clipboard (cut/copy/paste)
— such that each acts on the comment body and never on the underlying editor. In addition the box
SHALL handle Ctrl+Enter or Cmd+Enter (or the box's primary action button) to submit, and Esc (or a
"Cancel" button) to cancel. The primary action's label and styling are specified by "Present the
authoring box as the stored card's editing state".

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

The plugin's accent, its filled-surface variant, the range wash and the shared surface fill SHALL
each be defined in exactly one place, so that the card, the box, the range wash and the gutter bar
cannot drift apart.

This requirement governs presentation only. The box's range anchoring, its width and height behavior,
its focus and keystroke ownership, and its submit and cancel semantics are unchanged.

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
