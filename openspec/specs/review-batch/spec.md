# review-batch Specification

## Purpose
TBD - created by archiving change comment-batch. Update Purpose after archive.
## Requirements
### Requirement: Represent a comment with an open subject and anchoring data

The plugin SHALL represent a pending comment as a `ReviewComment` carrying an id, a `subject`, a
free-text body, a status, and — for line-anchored subjects — a live `RangeMarker`, the anchor text,
and a context hash of the surrounding lines. The `subject` SHALL be an open type covering `Line`,
`LineRange`, `File`, `Files`, and `Project`; this change authors only the `Line` and `LineRange`
scopes, with the remaining scopes modeled but not yet authored.

#### Scenario: Line-range comment carries anchoring data

- **WHEN** a comment is created for lines 10–15 of a file
- **THEN** the `ReviewComment` has a `LineRange` subject with that path and range, a `RangeMarker`,
  the selected anchor text, and a context hash

#### Scenario: Non-line subjects are modeled but not authored

- **WHEN** the MVP authors a comment
- **THEN** its subject is `Line` or `LineRange`, and the `File` / `Files` / `Project` cases exist in
  the type without an authoring entry point

### Requirement: Persist pending comments in a project-scoped batch

The plugin SHALL hold the pending comments in a project-scoped store service supporting add, delete,
and clear, and SHALL notify registered listeners whenever the set changes. The store is the single
source of truth that the gutter markers and the tool window render from.

#### Scenario: Adding a comment notifies listeners

- **WHEN** a comment is added to the store
- **THEN** registered listeners are notified and the comment is part of the pending batch

#### Scenario: Store is the single source for all surfaces

- **WHEN** the batch changes (add, delete, or clear)
- **THEN** the gutter markers and the tool window both update from the store, without holding a
  separate copy

### Requirement: Display stored-comment markers in the gutter

The plugin SHALL render a persistent gutter marker on each line range that has a pending comment,
distinct from the transient hover add-comment affordance, and SHALL update the markers when the
batch changes.

#### Scenario: Marker appears on a commented line range

- **WHEN** a comment exists for lines 10–15 of a file open in the editor
- **THEN** a stored-comment gutter marker is shown against that line range

#### Scenario: Marker removed when its comment is deleted

- **WHEN** the user deletes a comment
- **THEN** its stored-comment gutter marker is removed from the editor

### Requirement: List pending comments in a tool window

The plugin SHALL provide a tool window listing all pending comments grouped by file, each entry
showing the line range and a snippet of the body, and SHALL navigate to a comment's location in the
editor on request.

#### Scenario: Grouping by file

- **WHEN** comments exist across two files
- **THEN** the tool window shows two file groups, each listing its comments in line order

#### Scenario: Navigate from the tool window

- **WHEN** the user double-clicks a line-anchored comment entry
- **THEN** the corresponding file opens (or is focused) with the caret at the comment's start line

### Requirement: Delete pending comments

The plugin SHALL allow the user to delete any pending comment from either the gutter or the tool
window, keeping the store, gutter, and tool window in sync.

#### Scenario: Delete keeps all surfaces in sync

- **WHEN** the user deletes a comment from the gutter or the tool window
- **THEN** it is removed from the store, its gutter marker disappears, and its tool-window entry is
  removed

