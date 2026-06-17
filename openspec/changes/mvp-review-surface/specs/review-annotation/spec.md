## ADDED Requirements

### Requirement: Author a line-anchored comment from a selection

The plugin SHALL provide a keybound editor action that creates a comment anchored to the active file and the current selection (or caret line when there is no selection). The comment SHALL capture the project-relative file path, the start and end line numbers, the selected anchor text, a context hash of the surrounding lines, and a free-text body entered by the user.

#### Scenario: Comment created from a multi-line selection

- **WHEN** the user selects lines 10–15 in a file and invokes the "Add review comment" action and enters body text
- **THEN** a comment is stored with that file's project-relative path, startLine 10, endLine 15, the selected text as anchor text, a context hash, and the entered body

#### Scenario: Comment created with no selection

- **WHEN** the user places the caret on a line with no selection and invokes the action and enters body text
- **THEN** a comment is stored anchored to that single line (startLine == endLine)

#### Scenario: Authoring cancelled

- **WHEN** the user invokes the action but dismisses the popup without entering text
- **THEN** no comment is stored and the editor is unchanged

### Requirement: Author a whole-file or batch-level comment

The plugin SHALL allow a comment to be anchored to a **whole file** (a project-relative path with no line range) or to the **batch/project** (detached from any file — general feedback on the whole review). A whole-file comment SHALL store the path with no start/end line; a batch-level comment SHALL store neither a path nor a line range. Both SHALL capture a free-text body.

#### Scenario: Whole-file comment

- **WHEN** the user invokes the "Add file comment" action on the active file and enters body text
- **THEN** a comment is stored with that file's project-relative path, no line range, and the entered body

#### Scenario: Batch-level comment

- **WHEN** the user invokes the "Add review comment" action with no active file context (e.g. from the tool window) and enters body text
- **THEN** a comment is stored with no path and no line range, holding only the entered body

### Requirement: Comments may be anchored to any open file

The plugin SHALL allow comments on any open file in the project, not only files reported as changed by VCS. Authoring SHALL NOT depend on the change view or any other capture mode — opening a file and selecting is the single MVP entry point. (A change-view / diff entry point is a postponed capture mode.)

#### Scenario: Comment on an unchanged file

- **WHEN** the user authors a comment in a file that has no pending VCS changes
- **THEN** the comment is stored the same as for a changed file

### Requirement: Display comment markers in the gutter

The plugin SHALL render a gutter icon on each line range that has a pending comment, so the user can see at a glance which lines are commented.

#### Scenario: Gutter icon appears on a commented line

- **WHEN** a comment exists for lines 10–15 of a file that is open in the editor
- **THEN** a gutter icon is shown against that line range

#### Scenario: Gutter icon removed when comment deleted

- **WHEN** the user deletes a comment
- **THEN** its gutter icon is removed from the editor

### Requirement: List pending comments in a tool window

The plugin SHALL provide a tool window listing all pending comments grouped by file, each entry showing the line range (or a whole-file indicator) and a snippet of the body, with an action to navigate to the comment's location in the editor. Batch-level comments (no path) SHALL appear under a dedicated group separate from the per-file groups.

#### Scenario: Navigate from the tool window

- **WHEN** the user double-clicks a file or line-anchored comment entry in the tool window
- **THEN** the corresponding file opens (or is focused) with the caret at the comment's start line (or the file is opened at the top for a whole-file comment)

#### Scenario: Grouping by file

- **WHEN** comments exist across two files
- **THEN** the tool window shows two file groups, each listing its comments in line order

#### Scenario: Batch-level comments grouped separately

- **WHEN** a batch-level comment (no path) and a file comment both exist
- **THEN** the tool window shows the batch-level comment under its own group, distinct from the file groups

### Requirement: Edit and delete pending comments

The plugin SHALL allow the user to edit the body of, or delete, any pending comment from either the gutter or the tool window.

#### Scenario: Edit a comment body

- **WHEN** the user edits a comment and saves new body text
- **THEN** the stored comment's body is updated and the tool window reflects the new text

#### Scenario: Delete a comment

- **WHEN** the user deletes a comment
- **THEN** it is removed from the store, the tool window list, and the gutter

### Requirement: Refresh synced files before review

The plugin SHALL provide an action that refreshes the IDE's virtual file system view so edits written to disk (by a local agent, or synced in from a remote sandbox) are visible before the user reviews them.

#### Scenario: Refresh surfaces synced edits

- **WHEN** the agent has edited files and they have been written to disk locally (directly, or synced from the sandbox), and the user invokes "Refresh & review"
- **THEN** the IDE reloads those files from disk so their current content and change status are shown
