## MODIFIED Requirements

### Requirement: Render stored comments as an inline card

The plugin SHALL render each stored comment as an always-expanded, read-only inline card placed
under its commented line range in every open editor showing that file, displaying the comment body.

The card SHALL be visually distinct from the code it floats over: it SHALL be filled with a
background other than the editor's own text background, and SHALL be framed by a single-weight
outline on every edge, so it reads as a UI surface over code rather than as more code.

The card SHALL NOT carry an accent bar or any other accent-colored edge of its own. A commented
range SHALL be marked in the accent color in exactly one place — the gutter bar over its lines — so
that a card and the lines it annotates are tied by one mark rather than by two parallel lines.

Each card SHALL carry an always-present header row above its body, showing an author label and
hosting the card's Edit and Delete affordances. The author label SHALL be a presentation-level value
in this capability; no author field is added to the stored comment record. The Edit and Delete
affordances SHALL remain revealed on hover rather than shown permanently, and SHALL be revealed
**inside** the reserved header row, so that the card's rendered height is identical whether or not
the pointer is over it and revealing the affordances never reflows the code below the card.

The card SHALL be sized no wider than the editor's configured right margin (its vertical guide
column); when the editor has no right margin configured (guide disabled or a non-positive column),
the card SHALL fall back to spanning the full editor width. The card's height SHALL fit its header
row plus its body, so a short comment still reserves little vertical space. The cards SHALL be
derived from the store and reconciled when the batch changes (add, update, delete, clear), following
the same single-source-of-truth rule as the tool window. A card SHALL NOT be rendered for a comment
while that comment is open in an edit box.

#### Scenario: Card appears under a commented range

- **WHEN** a comment exists for a line range in a file open in the editor
- **THEN** a read-only inline card showing that comment's body is rendered under the range

#### Scenario: Card is distinguishable from surrounding code

- **WHEN** a stored comment's card is rendered inside a large file
- **THEN** the card is filled with a background other than the editor's text background and is framed
  by an outline, so it reads as a message about the lines above it rather than as more text in the file

#### Scenario: One accent mark per commented range

- **WHEN** a stored comment's card is rendered under its commented lines
- **THEN** the accent color appears only in the gutter bar over those lines, and the card's own frame
  carries no accent-colored edge

#### Scenario: Header row is always present

- **WHEN** a stored comment's card is at rest, with the pointer elsewhere
- **THEN** the card shows a header row above its body carrying an author label, and that row occupies
  the same height it will occupy once the Edit and Delete affordances appear in it

#### Scenario: Actions are revealed on hover inside the header row

- **WHEN** the pointer moves onto a resting card
- **THEN** the Edit and Delete affordances appear within the already-present header row, and they
  disappear again when the pointer leaves the card

#### Scenario: Card height is identical at rest and on hover

- **WHEN** the pointer enters or leaves a stored comment's card
- **THEN** the card's rendered height does not change and the code below it does not move

#### Scenario: Card updates when its comment changes

- **WHEN** a comment's body is updated in the store
- **THEN** the inline card for that comment re-renders with the new body

#### Scenario: Card removed when its comment is deleted

- **WHEN** the user deletes a comment
- **THEN** its inline card is removed from every editor showing the file

#### Scenario: Card width is capped at the right margin

- **WHEN** the editor has a configured right margin
- **THEN** the card is no wider than the right-margin column, regardless of the editor's full width

#### Scenario: Card falls back to full width without a right margin

- **WHEN** the editor has no configured right margin (guide disabled or a non-positive column)
- **THEN** the card spans the full editor width, as before this change

#### Scenario: Short comment keeps the card compact

- **WHEN** a stored comment's body is a single line
- **THEN** the resting card is only as tall as that line plus its header row and padding, with no
  additional button row reserving extra height

#### Scenario: Card presentation adds no stored data

- **WHEN** a card renders its author label
- **THEN** the label comes from the presentation layer and the stored comment record is unchanged — no
  author field, message list, or conversation-state value is stored
