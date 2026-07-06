## ADDED Requirements

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
