## MODIFIED Requirements

### Requirement: Surface the captured comment on submit

On submit, the box SHALL close and the captured comment SHALL be added to the pending batch as a
`ReviewComment` — carrying the file path, the target line range, the body, a live `RangeMarker`
created from the range, the anchor text, and a context hash. A stored-comment gutter marker SHALL
then appear on the commented line range, an always-expanded read-only inline card carrying the
comment body SHALL appear under that range, and the comment SHALL appear in the tool window. (The
baseline's report-only behavior — a confirmation notification plus a log entry — is superseded by
this persistence.)

#### Scenario: Submitting adds the comment to the batch

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes, a `ReviewComment` for that range is added to the store, and a
  stored-comment gutter marker appears on the range

#### Scenario: Submitted comment stays visible as an inline card

- **WHEN** the user submits a comment
- **THEN** the authored body remains visible in the editor as an always-expanded read-only inline
  card rendered under the commented line range, rather than the editor showing only a gutter marker

#### Scenario: Submitted comment appears in the tool window

- **WHEN** a comment is submitted
- **THEN** it is listed in the tool window under its file, showing the line range and a body snippet

### Requirement: A single active comment box

At most one comment box SHALL be open at a time. Opening a new box — whether to author a new comment
or to edit an existing one — SHALL close any box that is already open.

#### Scenario: Opening a second box closes the first

- **WHEN** a comment box is open and the user opens another via the add-comment affordance
- **THEN** the previous box is closed and only the new box remains

#### Scenario: Opening an edit box closes an open authoring box

- **WHEN** a comment box is open and the user opens an existing comment for editing
- **THEN** the previously open box is closed and only the edit box remains

## ADDED Requirements

### Requirement: Edit an existing comment

The plugin SHALL let the user re-open a stored comment for editing from either its inline card or its
gutter marker. Editing SHALL open the same authoring box seeded with the comment's current body and
its current line range, with the range remaining adjustable (per `adjustable-comment-range`). While
the edit box is open, the edited comment's read-only inline card SHALL be suppressed so the card and
its edit box never overlap. Submitting SHALL update the existing comment in place — replacing its
body, and its position with the (possibly resized) range — rather than creating a second comment;
the stored comment's identity SHALL be preserved. Cancelling SHALL leave the comment unchanged. On
either submit or cancel, the read-only card SHALL reappear.

#### Scenario: Editing re-opens the box seeded with the current body

- **WHEN** the user chooses Edit on a stored comment
- **THEN** the authoring box opens over that comment's line range pre-filled with its current body,
  and its read-only card is hidden while the box is open

#### Scenario: Submitting an edit updates the comment in place

- **WHEN** the user edits the body (and/or resizes the range) and submits
- **THEN** the same comment's body and position are updated, no additional comment is created, and
  the refreshed read-only card reappears with the new body

#### Scenario: Cancelling an edit preserves the original

- **WHEN** the user opens a comment for editing and then cancels
- **THEN** the comment's stored body and range are unchanged and its read-only card reappears as it was
