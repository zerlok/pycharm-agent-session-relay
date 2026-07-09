## ADDED Requirements

### Requirement: Persist the pending batch across IDE restart

The plugin SHALL persist the pending review batch to durable per-user storage so that a batch of comments spanning one or more files survives closing the IDE and rebooting the OS, and SHALL restore it when the same project is reopened. Persistence SHALL live entirely behind the existing project-scoped store interface — the logic API, the listeners, and the rendering surfaces are unchanged. Restored comments SHALL keep their identity, subject, body, status, and anchoring data, and SHALL be restored at their recorded line ranges. The plugin SHALL NOT re-anchor comments against out-of-IDE edits during restore; repositioning across such edits is out of scope for this capability.

#### Scenario: Batch survives IDE close and reopen

- **WHEN** a pending batch holding comments in more than one file exists, and the IDE is closed and the same project reopened
- **THEN** the pending batch is restored with the same comments, each retaining its identity, subject, body, and anchoring data

#### Scenario: Comments render on file open after restart

- **WHEN** a document with restored comments is opened after a restart
- **THEN** its gutter markers, inline cards, and tool-window entries render from the restored store at the recorded line ranges, exactly as if the IDE had not been restarted

#### Scenario: Empty batch persists as empty

- **WHEN** the batch is cleared (for example after a successful submit) and the IDE is restarted
- **THEN** the restored batch is empty, with no stale comments reappearing

#### Scenario: Persistence is private to the user

- **WHEN** the batch is persisted
- **THEN** it is stored per-user and not written to version-controlled project files

#### Scenario: Restore performs no re-anchoring

- **WHEN** the batch is restored on project open
- **THEN** comments are loaded at their recorded positions without resolving files or re-anchoring on the load path, and no comment is marked stale as a result of restore alone
