## MODIFIED Requirements

### Requirement: Render stored comments as an inline card

The plugin SHALL render each stored comment as an always-expanded, read-only inline card placed
under its commented line range in every open editor showing that file, displaying the comment body.
The card SHALL be sized no wider than the editor's configured right margin (its vertical guide
column); when the editor has no right margin configured (guide disabled or a non-positive column),
the card SHALL fall back to spanning the full editor width. The card's height SHALL fit its body so a
short comment reserves little vertical space. Each card SHALL offer an Edit affordance and a Delete
affordance, revealed on hover (a hover toolbar) rather than shown as a permanent row, so a resting
card is only as tall as its body. The cards SHALL be derived from the store and reconciled when the
batch changes (add, update, delete, clear), the same single-source-of-truth rule the gutter markers
follow. A card SHALL NOT be rendered for a comment while that comment is open in an edit box.

#### Scenario: Card appears under a commented range

- **WHEN** a comment exists for a line range in a file open in the editor
- **THEN** a read-only inline card showing that comment's body is rendered under the range

#### Scenario: Card updates when its comment changes

- **WHEN** a comment's body is updated in the store
- **THEN** the inline card for that comment re-renders with the new body

#### Scenario: Card removed when its comment is deleted

- **WHEN** the user deletes a comment
- **THEN** its inline card is removed from every editor showing the file

#### Scenario: Actions are revealed on hover

- **WHEN** the pointer is not over a resting card
- **THEN** the card shows only its body (no permanent button row), and the Edit and Delete
  affordances appear when the pointer hovers the card

#### Scenario: Card width is capped at the right margin

- **WHEN** the editor has a configured right margin
- **THEN** the card is no wider than the right-margin column, regardless of the editor's full width

#### Scenario: Card falls back to full width without a right margin

- **WHEN** the editor has no configured right margin (guide disabled or a non-positive column)
- **THEN** the card spans the full editor width, as before this change

#### Scenario: Short comment keeps the card compact

- **WHEN** a stored comment's body is a single line
- **THEN** the resting card is only as tall as that line plus its padding, with no button row
  reserving extra height
