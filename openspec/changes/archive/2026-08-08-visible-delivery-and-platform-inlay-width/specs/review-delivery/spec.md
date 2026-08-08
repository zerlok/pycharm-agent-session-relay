## MODIFIED Requirements

### Requirement: Write the exported review to REVIEW.md

On submit, the plugin SHALL serialize the pending comments (via the Exporter) and write the result
to `REVIEW.md` at the project base path.

The write SHALL go through the platform's in-memory document for that file, so that the exported
content is what the user sees. When `REVIEW.md` is already open in an editor, that editor SHALL show
the new export as part of the submit — not after an unrelated refresh, reopen, or restart. The
plugin SHALL NOT write `REVIEW.md` by a filesystem write that bypasses the platform's document
layer: such a write leaves the open editor showing the previous export, which the plugin cannot
distinguish from a successful one.

Submit SHALL NOT freeze the IDE. The work that can block — reading each commented file's content to
verify anchors — SHALL run off the EDT.

#### Scenario: REVIEW.md written with line-anchored references

- **WHEN** the user submits a review containing a comment on lines 10–15 of `src/app.py` with body
  "extract this"
- **THEN** `REVIEW.md` is written at the project root containing `@src/app.py#L10-15` and the body
  "extract this"

#### Scenario: Export reaches an already-open REVIEW.md

- **WHEN** `REVIEW.md` is open in an editor and the user submits a review with a new comment
- **THEN** that editor shows the new export, containing the new comment, without the user reopening
  the file or refreshing anything

#### Scenario: Submit does not block the UI

- **WHEN** the user invokes submit
- **THEN** the per-file content reads that verify anchors execute on a background thread and the UI
  remains responsive

### Requirement: Clear pending comments after a successful submit

After a submit whose export has reached the platform's document for `REVIEW.md`, the plugin SHALL
clear the pending comment batch — removing gutter markers and tool-window entries — so the next
review starts clean.

Clearing the batch destroys the user's only copy of those comments and cannot be undone, so it SHALL
be authorized only by evidence that the export is visible to the user. A filesystem write returning
without error is not that evidence. If the write fails, or the exported content has not reached the
document, the batch SHALL be left intact.

#### Scenario: Batch cleared once the export is visible

- **WHEN** a submit completes and the exported content is in the document for `REVIEW.md`
- **THEN** the pending comments are cleared from the store, gutter, and tool window

#### Scenario: Batch preserved on failure

- **WHEN** the `REVIEW.md` write fails
- **THEN** the pending comments remain so the user can retry

#### Scenario: Submitting against an open REVIEW.md does not lose comments

- **WHEN** `REVIEW.md` is open in an editor and the user submits
- **THEN** the open editor shows the new export and the batch is cleared, or the batch is left
  intact — the comments are never cleared while the user is looking at the previous export
