## ADDED Requirements

### Requirement: Verify each comment's anchor before an export

At the export sync point, after live positions have been flushed, the plugin SHALL verify each
line-anchored comment whose file is open by comparing the comment's recorded anchor text against the
text its live marker currently spans. A comment whose anchor text still matches SHALL be `ACTIVE`. A
comment whose anchor text no longer matches SHALL be marked `STALE`. Verification SHALL be a pure
comparison — it SHALL NOT search for the anchor elsewhere in the file and SHALL NOT move the
comment.

A comment SHALL NOT be marked `STALE` merely because its line numbers changed: in-IDE edits above a
comment shift its marker and its anchor text is unchanged, so it stays `ACTIVE`. Only a change to the
text *under* the comment makes it stale.

A comment that cannot be verified — its file is not open, or it carries no recorded anchor text —
SHALL keep its current status rather than being marked stale by default, so that absence of evidence
is not reported to the agent as evidence of drift.

#### Scenario: Untouched comment stays active

- **WHEN** a review is exported and a commented range's text is unchanged since the comment was written
- **THEN** the comment's status is `ACTIVE` and its export is unflagged

#### Scenario: Rewritten lines make a comment stale

- **WHEN** the text within a commented range is replaced (for example by an agent rewriting the file
  on disk, followed by a refresh) and a review is then exported
- **THEN** that comment is marked `STALE`

#### Scenario: A shift above the comment does not make it stale

- **WHEN** lines are inserted above a commented range, shifting its marker, but the commented lines
  themselves are unchanged, and a review is exported
- **THEN** the comment remains `ACTIVE` and its exported reference uses the shifted line range

#### Scenario: An unopened file is not reported as drifted

- **WHEN** a review is exported and a comment's file is not open in any editor
- **THEN** the comment keeps its current status and is not marked `STALE`

#### Scenario: Verification never relocates a comment

- **WHEN** a comment is marked `STALE` at export
- **THEN** its stored line range is unchanged; the plugin does not search for its anchor text
  elsewhere in the file

## MODIFIED Requirements

### Requirement: Represent a comment with an open subject and anchoring data

The plugin SHALL represent a pending comment as a `ReviewComment` carrying an id, a `subject`, a
free-text body, a status, and — for line-anchored subjects — the anchor text and a context hash of
the surrounding lines. The record SHALL hold only inert, serializable data: it SHALL NOT carry a live
`RangeMarker`, `Document`, or `VirtualFile`. While a file is open, the live position of a comment is
held by the presentation layer as a `RangeHighlighter` keyed by comment id, and the stored line
numbers are the last-known anchor, refreshed at the defined sync points.

The `subject` SHALL be an open type covering `Line`, `LineRange`, `File`, `Files`, and `Project`;
only the `Line` and `LineRange` scopes are authored, with the remaining scopes modeled but not yet
authored.

A comment's status SHALL be one of `ACTIVE`, `STALE`, or `ORPHANED`, meaning:

- `ACTIVE` — the comment's anchor is either verified to still match, or has not been checked.
- `STALE` — the text under the comment changed after it was written, so its line range is no longer
  known to be correct. The comment is still deliverable and is exported with a flag.
- `ORPHANED` — the comment's recorded range does not exist in the current document at all. It is
  retained with its recorded position, is listed in the tool window, and renders no editor marker.

#### Scenario: Line-range comment carries anchoring data

- **WHEN** a comment is created for lines 10–15 of a file
- **THEN** the `ReviewComment` has a `LineRange` subject with that path and range, the selected anchor
  text, and a context hash

#### Scenario: The stored record holds no live platform object

- **WHEN** a comment is stored
- **THEN** its record carries line numbers and a file url, and carries no `RangeMarker`, `Document`,
  or `VirtualFile`

#### Scenario: Non-line subjects are modeled but not authored

- **WHEN** a comment is authored
- **THEN** its subject is `Line` or `LineRange`, and the `File` / `Files` / `Project` cases exist in
  the type without an authoring entry point

### Requirement: Sync live comment positions into the store at defined sync points

The plugin SHALL treat the live `RangeHighlighter` as the source of truth for a comment's position
while its file is open, and SHALL flush each comment's current line range back into the store (via the
in-place position-update seam) at three defined sync points: before an export, when an editor is
closed, and when the comment's file is saved. Each flush SHALL be idempotent — a comment whose live
range equals its stored subject produces no change and no listener notification. A save flush SHALL be
scoped to comments whose file is the saved document; comments in other open files are not touched.

A flush SHALL report only positions that are genuinely live. A comment whose marker has been
invalidated, and a comment whose recorded range does not exist in the current document and is
therefore displayed at a clamped position, SHALL NOT have that substitute position written into the
store. The recorded position is authoritative until the comment is deliberately re-placed by the
user; a display-time fallback SHALL NOT silently become the recorded anchor.

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
- **THEN** each affected comment's stored subject is updated from its live marker

#### Scenario: A clamped display position is never written back

- **WHEN** a comment recorded at lines 300–301 is restored into a document that now has 50 lines, and
  a sync point then fires
- **THEN** the comment's stored subject still records lines 300–301; the clamped position it may have
  been displayed at is not written into the store

### Requirement: Persist the pending batch across IDE restart

The plugin SHALL persist the pending review batch to durable per-user storage so that a batch of comments spanning one or more files survives closing the IDE and rebooting the OS, and SHALL restore it when the same project is reopened. Persistence SHALL live entirely behind the existing project-scoped store interface — the logic API, the listeners, and the rendering surfaces are unchanged. Restored comments SHALL keep their identity, subject, body, status, and anchoring data, and SHALL be restored at their recorded line ranges. The plugin SHALL NOT re-anchor comments against out-of-IDE edits during restore; repositioning across such edits is out of scope for this capability.

When a restored comment's recorded line range does not exist in the document that is later opened,
the plugin SHALL retain the comment at its recorded range, mark it `ORPHANED`, and render no editor
marker or card for it. It SHALL remain listed in the tool window so the user can read, edit, or
delete it. This SHALL happen when the file's editor opens — never on the `loadState` path.

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
- **THEN** comments are loaded at their recorded positions without resolving files or re-anchoring on the load path, and no comment is marked stale or orphaned as a result of restore alone

#### Scenario: A comment beyond the end of its file is orphaned, not moved

- **WHEN** a comment recorded at lines 300–301 is restored and its file, now 50 lines long, is opened
- **THEN** the comment is marked `ORPHANED`, keeps its recorded range of 300–301, renders no gutter
  bar or card, and is still listed in the tool window
