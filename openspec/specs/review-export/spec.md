# review-export Specification

## Purpose
TBD - created by archiving change review-export. Update Purpose after archive.
## Requirements
### Requirement: Export comments as Claude-format markdown

The plugin SHALL serialize the pending comments into a single markdown document using Claude Code's
native reference syntax, as a pure function of the batch (no I/O). A line-anchored comment SHALL
render as `@<project-relative-path>#L<start>-<end>` followed by its body. Paths SHALL be
project-relative with forward slashes.

#### Scenario: Range reference

- **WHEN** the batch contains a comment on lines 10–15 of `src/app.py` with body "extract this"
- **THEN** the export contains a reference `@src/app.py#L10-15` followed by "extract this"

#### Scenario: Deterministic ordering

- **WHEN** the batch contains comments across two files
- **THEN** the export lists them ordered by file path, then by start line

### Requirement: Render a single-line reference as `@path#L<n>`

A comment anchored to a single line N SHALL render its reference as `@<project-relative-path>#L<N>`
(not `@path#L<N>-<N>`).

#### Scenario: Single-line form

- **WHEN** a comment is anchored to a single line 42 of `src/app.py`
- **THEN** its reference is written as `@src/app.py#L42`

### Requirement: Export uses the current anchored range

The exporter SHALL read each comment's current line range from its `RangeMarker` at export time, so
in-IDE edits made after authoring are reflected in the exported references.

#### Scenario: Range reflects an in-IDE edit

- **WHEN** lines are inserted above a commented range after the comment was authored, shifting it
- **THEN** the exported reference uses the comment's current (shifted) line range

### Requirement: Handle the empty batch

Exporting an empty batch SHALL yield a well-defined empty result that the delivery stage can treat
as "nothing to submit".

#### Scenario: Empty batch

- **WHEN** the export runs with no pending comments
- **THEN** it returns the defined empty result rather than a partial or malformed document

