## ADDED Requirements

### Requirement: Update a pending comment's body

The store SHALL provide an in-place body-update command that replaces a stored comment's body,
preserving the comment's identity and all other fields, and SHALL notify listeners of the change so
every surface reconciles from the store. The command SHALL be a no-op when the id is unknown. This
mirrors the existing position-update seam and is distinct from delete-and-re-add.

#### Scenario: Updating a body notifies listeners

- **WHEN** an existing comment's body is updated through the store
- **THEN** the comment keeps its id, subject, and anchoring data, its body is replaced, and
  registered listeners are notified

#### Scenario: Updating an unknown comment is a no-op

- **WHEN** a body update targets an id that is not in the store
- **THEN** the store is unchanged and no listener is notified

### Requirement: Render stored comments as an inline card

The plugin SHALL render each stored comment as an always-expanded, read-only inline card placed
under its commented line range in every open editor showing that file, displaying the comment body.
Each card SHALL offer an Edit affordance and a Delete affordance. The cards SHALL be derived from the
store and reconciled when the batch changes (add, update, delete, clear), the same single-source-of-
truth rule the gutter markers follow. A card SHALL NOT be rendered for a comment while that comment
is open in an edit box.

#### Scenario: Card appears under a commented range

- **WHEN** a comment exists for a line range in a file open in the editor
- **THEN** a read-only inline card showing that comment's body is rendered under the range, offering
  Edit and Delete

#### Scenario: Card updates when its comment changes

- **WHEN** a comment's body is updated in the store
- **THEN** the inline card for that comment re-renders with the new body

#### Scenario: Card removed when its comment is deleted

- **WHEN** the user deletes a comment
- **THEN** its inline card is removed from every editor showing the file

## MODIFIED Requirements

### Requirement: Delete pending comments

The plugin SHALL allow the user to delete any pending comment from the gutter, the tool window, or
the comment's inline card, keeping the store, gutter, inline card, and tool window in sync.

#### Scenario: Delete keeps all surfaces in sync

- **WHEN** the user deletes a comment from the gutter, the tool window, or its inline card
- **THEN** it is removed from the store, and its gutter marker, inline card, and tool-window entry
  all disappear
