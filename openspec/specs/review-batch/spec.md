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
source of truth that the inline cards and the tool window render from.

#### Scenario: Adding a comment notifies listeners

- **WHEN** a comment is added to the store
- **THEN** registered listeners are notified and the comment is part of the pending batch

#### Scenario: Store is the single source for all surfaces

- **WHEN** the batch changes (add, delete, or clear)
- **THEN** the inline cards and the tool window both update from the store, without holding a
  separate copy

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

The plugin SHALL allow the user to delete any pending comment from the tool window or the comment's
inline card, keeping the store, inline card, and tool window in sync.

#### Scenario: Delete keeps all surfaces in sync

- **WHEN** the user deletes a comment from the tool window or its inline card
- **THEN** it is removed from the store, and its inline card and tool-window entry all disappear

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

### Requirement: Sync live comment positions into the store at defined sync points

The plugin SHALL treat the live `RangeHighlighter` as the source of truth for a comment's position
while its file is open, and SHALL flush each comment's current line range back into the store (via the
in-place position-update seam) at three defined sync points: before an export, when an editor is
closed, and when the comment's file is saved. Each flush SHALL be idempotent — a comment whose live
range equals its stored subject produces no change and no listener notification. A save flush SHALL be
scoped to comments whose file is the saved document; comments in other open files are not touched.

#### Scenario: Save flushes the file's shifted comments

- **WHEN** lines are inserted above a commented range, shifting its live marker, and the file is then
  saved
- **THEN** the store's persisted subject for that comment is updated to the current (shifted) line
  range before the save completes

#### Scenario: Save does not touch comments in other files

- **WHEN** a document is saved
- **THEN** only comments anchored to that document are synced; comments in other open files keep their
  stored positions untouched

#### Scenario: Sync is idempotent when nothing moved

- **WHEN** a sync point fires but a comment's live range already equals its stored subject
- **THEN** the store is unchanged and no listener is notified for that comment

#### Scenario: Export and editor close remain sync points

- **WHEN** a review is exported, or an editor showing commented files is closed
- **THEN** each affected comment's stored subject is updated from its live marker, exactly as before
  this change

### Requirement: Persist the pending batch across IDE restart

The plugin SHALL persist the pending review batch to durable per-user storage so that a batch of comments spanning one or more files survives closing the IDE and rebooting the OS, and SHALL restore it when the same project is reopened. Persistence SHALL live entirely behind the existing project-scoped store interface — the logic API, the listeners, and the rendering surfaces are unchanged. Restored comments SHALL keep their identity, subject, body, status, and anchoring data, and SHALL be restored at their recorded line ranges. The plugin SHALL NOT re-anchor comments against out-of-IDE edits during restore; repositioning across such edits is out of scope for this capability.

#### Scenario: Batch survives IDE close and reopen

- **WHEN** a pending batch holding comments in more than one file exists, and the IDE is closed and the same project reopened
- **THEN** the pending batch is restored with the same comments, each retaining its identity, subject, body, and anchoring data

#### Scenario: Comments render on file open after restart

- **WHEN** a document with restored comments is opened after a restart
- **THEN** its inline cards and tool-window entries render from the restored store at the recorded line ranges, exactly as if the IDE had not been restarted

#### Scenario: Empty batch persists as empty

- **WHEN** the batch is cleared (for example after a successful submit) and the IDE is restarted
- **THEN** the restored batch is empty, with no stale comments reappearing

#### Scenario: Persistence is private to the user

- **WHEN** the batch is persisted
- **THEN** it is stored per-user and not written to version-controlled project files

#### Scenario: Restore performs no re-anchoring

- **WHEN** the batch is restored on project open
- **THEN** comments are loaded at their recorded positions without resolving files or re-anchoring on the load path, and no comment is marked stale as a result of restore alone

