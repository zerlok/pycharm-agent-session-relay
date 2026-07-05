## MODIFIED Requirements

### Requirement: Surface the captured comment on submit

On submit, the box SHALL close and the captured comment SHALL be added to the pending batch as a
`ReviewComment` — carrying the file path, the target line range, the body, a live `RangeMarker`
created from the range, the anchor text, and a context hash. A stored-comment gutter marker SHALL
then appear on the commented line range and the comment SHALL appear in the tool window. (The
baseline's report-only behavior — a confirmation notification plus a log entry — is superseded by
this persistence.)

#### Scenario: Submitting adds the comment to the batch

- **WHEN** the user submits a comment on a line range with a non-empty body
- **THEN** the box closes, a `ReviewComment` for that range is added to the store, and a
  stored-comment gutter marker appears on the range

#### Scenario: Submitted comment appears in the tool window

- **WHEN** a comment is submitted
- **THEN** it is listed in the tool window under its file, showing the line range and a body snippet

## ADDED Requirements

### Requirement: Refresh synced files before review

The plugin SHALL provide a "Refresh & review" action that triggers an asynchronous VFS refresh so
edits written to disk (by a local agent, or synced in from a remote sandbox) become visible before
the user reviews them.

#### Scenario: Refresh surfaces synced edits

- **WHEN** the agent has edited files on disk (locally, or synced from the sandbox) and the user
  invokes "Refresh & review"
- **THEN** the IDE reloads those files from disk so their current content and change status are shown
