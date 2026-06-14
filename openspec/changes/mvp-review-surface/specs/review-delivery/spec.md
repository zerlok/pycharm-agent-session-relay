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

On submit, the plugin SHALL serialize the pending comments into a single markdown document using Claude Code's native reference syntax — `@<project-relative-path>#L<start>-<end>` followed by the comment body — and write it to `REVIEW.md` at the project (worktree) root.

#### Scenario: REVIEW.md written with line-anchored references

- **WHEN** the user submits a review containing a comment on lines 10–15 of `src/app.py` with body "extract this"
- **THEN** `REVIEW.md` is written at the project root containing a reference `@src/app.py#L10-15` and the body "extract this"

#### Scenario: Single-line reference format

- **WHEN** a comment is anchored to a single line N
- **THEN** its reference is written as `@<path>#L<N>` (or an equivalent single-line form Claude resolves)

#### Scenario: No comments to submit

- **WHEN** the user invokes submit with no pending comments
- **THEN** no `REVIEW.md` is written and the user is informed there is nothing to submit

### Requirement: Relay the review into the active agent terminal

After `REVIEW.md` is written, the plugin SHALL type an instruction line that references the file (e.g. `read REVIEW.md and address the comments`) into the active agent terminal widget, so the idle agent session resumes and acts on the review. The plugin SHALL NOT open a second SSH connection for this; delivery uses the existing terminal widget.

#### Scenario: Instruction typed into the terminal

- **WHEN** the user submits a review and an agent terminal widget is available
- **THEN** the instruction line referencing `REVIEW.md` is sent to that terminal widget and submitted (Enter)

#### Scenario: No terminal available

- **WHEN** the user submits a review and no agent terminal widget can be targeted
- **THEN** `REVIEW.md` is still written and the user is told to read it manually (delivery is not silently dropped)

### Requirement: Clear pending comments after a successful submit

After a successful submit, the plugin SHALL clear the pending comment batch (removing gutter markers and tool-window entries) so the next review starts clean.

#### Scenario: Batch cleared on submit

- **WHEN** a submit completes successfully
- **THEN** the pending comments are cleared from the store, gutter, and tool window

### Requirement: Long-running submit work runs off the EDT

The plugin SHALL perform file writes and terminal interaction off the UI thread so the IDE does not freeze during submit.

#### Scenario: Submit does not block the UI

- **WHEN** the user invokes submit
- **THEN** the file write and terminal send execute on a background thread and the UI remains responsive
