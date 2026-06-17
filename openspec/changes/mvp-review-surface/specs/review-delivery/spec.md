## ADDED Requirements

### Requirement: Preview the exported review

The plugin SHALL render a live preview of the export (the same markdown that would be written to `REVIEW.md`) from the current set of pending comments, so the user can see exactly what the agent will receive before submitting.

#### Scenario: Preview reflects pending comments

- **WHEN** the user opens the preview with two pending comments
- **THEN** the preview shows a markdown document containing both comments with their file/line references and bodies

#### Scenario: Preview updates after a change

- **WHEN** the user deletes a comment while the preview is open
- **THEN** the preview no longer includes the deleted comment

### Requirement: Export comments as Claude-format markdown

On submit, the plugin SHALL serialize the pending comments into a single markdown document using Claude Code's native reference syntax and write it to `REVIEW.md` at the project root. A line-anchored comment SHALL render as `@<project-relative-path>#L<start>-<end>` followed by the body; a whole-file comment SHALL render as `@<project-relative-path>` (no line range); a batch-level comment SHALL render as a top-level note with no reference.

#### Scenario: REVIEW.md written with line-anchored references

- **WHEN** the user submits a review containing a comment on lines 10–15 of `src/app.py` with body "extract this"
- **THEN** `REVIEW.md` is written at the project root containing a reference `@src/app.py#L10-15` and the body "extract this"

#### Scenario: Single-line reference format

- **WHEN** a comment is anchored to a single line N
- **THEN** its reference is written as `@<path>#L<N>` (or an equivalent single-line form Claude resolves)

#### Scenario: Whole-file reference format

- **WHEN** a comment is anchored to a whole file `src/app.py` with no line range
- **THEN** its reference is written as `@src/app.py` (no `#L` range) followed by the body

#### Scenario: Batch-level note

- **WHEN** a batch-level comment (no path) with body "prefer smaller PRs" is submitted
- **THEN** `REVIEW.md` includes that body as a top-level note with no file reference

#### Scenario: No comments to submit

- **WHEN** the user invokes submit with no pending comments
- **THEN** no `REVIEW.md` is written and the user is informed there is nothing to submit

### Requirement: Notify the user that the review is ready

After `REVIEW.md` is written, the plugin SHALL notify the user that the file is ready at the project root, so the user can return to their idle session and ask the agent to read it. The plugin SHALL NOT open a connection to the agent or type into any terminal in this change. (Automatic relay into the terminal widget is a deferred follow-on.)

#### Scenario: Notification on successful write

- **WHEN** the user submits a review with at least one pending comment
- **THEN** `REVIEW.md` is written at the project root and the user is shown a notification that it is ready to hand to the agent

### Requirement: Clear pending comments after a successful submit

After a successful submit, the plugin SHALL clear the pending comment batch (removing gutter markers and tool-window entries) so the next review starts clean.

#### Scenario: Batch cleared on submit

- **WHEN** a submit completes successfully
- **THEN** the pending comments are cleared from the store, gutter, and tool window

### Requirement: Long-running submit work runs off the EDT

The plugin SHALL perform the `REVIEW.md` file write off the UI thread so the IDE does not freeze during submit.

#### Scenario: Submit does not block the UI

- **WHEN** the user invokes submit
- **THEN** the file write executes on a background thread and the UI remains responsive
