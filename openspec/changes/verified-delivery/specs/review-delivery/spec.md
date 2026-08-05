## ADDED Requirements

### Requirement: Own the submit pipeline in the delivery layer

The delivery layer SHALL own the whole submit pipeline: flushing live positions, verifying anchors,
exporting, writing `REVIEW.md`, refreshing the VFS, and clearing or preserving the batch. A
presentation-layer action SHALL NOT own any stage of it; the editor action SHALL do no more than
invoke the pipeline and render the resulting user-facing messages.

The pipeline SHALL be invocable without an editor action event, so that its outcome — in particular
the clear-versus-preserve decision — is reachable from a test.

#### Scenario: Submit is driven from the delivery layer

- **WHEN** the user invokes submit
- **THEN** the flush, verification, export, write, refresh, and clear/preserve decision are performed
  by the delivery layer, and the action contributes only the invocation and the notifications

#### Scenario: The outcome is testable without an action event

- **WHEN** a test drives the submit pipeline directly
- **THEN** it can observe whether the batch was cleared or preserved without constructing an editor
  action event

## MODIFIED Requirements

### Requirement: Clear pending comments after a successful submit

After a successful write, the plugin SHALL clear the pending comment batch — removing gutter markers
and tool-window entries — so the next review starts clean. If the write fails, the batch SHALL be
left intact: the comments are the user's only copy, there is no undo, and a cleared batch is also
erased from persistent storage at the next save.

A failed write SHALL additionally be recorded in the IDE log with the underlying cause, so a failure
reported from the field can be diagnosed from `idea.log` rather than only from a transient balloon.

#### Scenario: Batch cleared on success

- **WHEN** a submit completes successfully
- **THEN** the pending comments are cleared from the store, gutter, and tool window

#### Scenario: Batch preserved on failure

- **WHEN** the `REVIEW.md` write fails
- **THEN** the pending comments remain so the user can retry

#### Scenario: Failure is logged

- **WHEN** the `REVIEW.md` write fails
- **THEN** the failure and its cause are written to the IDE log in addition to being shown to the user
