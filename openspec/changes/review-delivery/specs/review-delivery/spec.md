## ADDED Requirements

### Requirement: Write the exported review to REVIEW.md

On submit, the plugin SHALL serialize the pending comments (via the Exporter) and write the result
to `REVIEW.md` at the project base path, on the local filesystem. The write SHALL run off the EDT so
the IDE does not freeze.

#### Scenario: REVIEW.md written with line-anchored references

- **WHEN** the user submits a review containing a comment on lines 10–15 of `src/app.py` with body
  "extract this"
- **THEN** `REVIEW.md` is written at the project root containing `@src/app.py#L10-15` and the body
  "extract this"

#### Scenario: Submit does not block the UI

- **WHEN** the user invokes submit
- **THEN** the file write executes on a background thread and the UI remains responsive

### Requirement: Empty submit writes nothing and informs the user

The plugin SHALL treat a submit with no pending comments as a no-op: no `REVIEW.md` is written and
the user is informed there is nothing to submit.

#### Scenario: No comments to submit

- **WHEN** the user invokes submit with no pending comments
- **THEN** no `REVIEW.md` is written and the user is told there is nothing to submit

### Requirement: Notify the user that the review is ready

After `REVIEW.md` is written, the plugin SHALL notify the user that the file is ready at the project
root, so the user can return to their idle session and ask the agent to read it. The plugin SHALL
NOT open a connection to the agent or type into any terminal in this change.

#### Scenario: Notification on successful write

- **WHEN** the user submits a review with at least one pending comment
- **THEN** `REVIEW.md` is written at the project root and the user is shown a notification that it is
  ready to hand to the agent

### Requirement: Clear pending comments after a successful submit

After a successful write, the plugin SHALL clear the pending comment batch — removing gutter markers
and tool-window entries — so the next review starts clean. If the write fails, the batch SHALL be
left intact.

#### Scenario: Batch cleared on success

- **WHEN** a submit completes successfully
- **THEN** the pending comments are cleared from the store, gutter, and tool window

#### Scenario: Batch preserved on failure

- **WHEN** the `REVIEW.md` write fails
- **THEN** the pending comments remain so the user can retry
