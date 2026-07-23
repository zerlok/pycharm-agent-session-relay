## MODIFIED Requirements

### Requirement: Highlight a stored comment's range on card hover

A stored comment SHALL display a resting signal for its current line range in the line-number gutter:
a colored bar, in the same accent color as its inline card's accent frame, so its commented lines are
identifiable at a glance without hovering. That resting signal SHALL be gutter-only — a stored comment
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
