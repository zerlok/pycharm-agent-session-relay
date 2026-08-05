## MODIFIED Requirements

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
