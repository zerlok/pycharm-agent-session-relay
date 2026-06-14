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

### Requirement: Comments may be anchored to any file

The plugin SHALL allow comments on any file in the project, not only files reported as changed. Opening the change view SHALL be a convenient entry point but SHALL NOT be a precondition for authoring a comment.

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

The plugin SHALL provide a tool window listing all pending comments grouped by file, each entry showing the line range and a snippet of the body, with an action to navigate to the comment's location in the editor.

#### Scenario: Navigate from the tool window

- **WHEN** the user double-clicks a comment entry in the tool window
- **THEN** the corresponding file opens (or is focused) with the caret at the comment's start line

#### Scenario: Grouping by file

- **WHEN** comments exist across two files
- **THEN** the tool window shows two file groups, each listing its comments in line order

### Requirement: Edit and delete pending comments

The plugin SHALL allow the user to edit the body of, or delete, any pending comment from either the gutter or the tool window.

#### Scenario: Edit a comment body

- **WHEN** the user edits a comment and saves new body text
- **THEN** the stored comment's body is updated and the tool window reflects the new text

#### Scenario: Delete a comment

- **WHEN** the user deletes a comment
- **THEN** it is removed from the store, the tool window list, and the gutter

### Requirement: Refresh synced files before review

The plugin SHALL provide an action that refreshes the IDE's virtual file system view so edits synced from the sandbox (via mutagen) are visible before the user reviews them.

#### Scenario: Refresh surfaces synced edits

- **WHEN** the agent has edited files remotely and mutagen has written them to disk locally, and the user invokes "Refresh & review"
- **THEN** the IDE reloads those files from disk so their current content and change status are shown
