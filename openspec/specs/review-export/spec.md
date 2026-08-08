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

The exporter SHALL render each comment's stored line range as-is, and the export sync point SHALL
ensure those stored ranges are current: before the export runs, each open file's live positions are
flushed into the store and each comment's anchor is verified (see `review-batch`). In-IDE edits made
after authoring are therefore reflected in the exported references, and a reference whose underlying
text changed is flagged rather than presented as verified.

The exporter itself SHALL NOT read a `RangeMarker`, a document, or the filesystem; keeping it a pure
function of the batch is what makes the export trivially testable.

#### Scenario: Range reflects an in-IDE edit

- **WHEN** lines are inserted above a commented range after the comment was authored, shifting it
- **THEN** the exported reference uses the comment's current (shifted) line range

#### Scenario: The exporter reads only the batch

- **WHEN** the export runs
- **THEN** it derives every reference from the comment records it was handed, without consulting an
  editor, a marker, or the filesystem

### Requirement: Handle the empty batch

Exporting an empty batch SHALL yield a well-defined empty result that the delivery stage can treat
as "nothing to submit".

#### Scenario: Empty batch

- **WHEN** the export runs with no pending comments
- **THEN** it returns the defined empty result rather than a partial or malformed document

### Requirement: Flag a comment whose anchor could not be verified

A comment marked `STALE` or `ORPHANED` SHALL still be exported — the user's feedback is never
silently dropped — and its block SHALL carry a visible flag telling the agent that the reference is
not trustworthy.

The two cases SHALL be flagged **distinguishably**, because they call for different action from the
agent: a `STALE` comment's code may have moved or been edited and is worth locating, whereas an
`ORPHANED` comment's lines no longer exist at all and the feedback may be obsolete.

Each flag SHALL be **concise** — a single short marker appended to the reference line, with no
explanatory sentence and no additional lines. The flag SHALL NOT alter the reference syntax itself:
the `@<path>#L<start>-<end>` token SHALL remain first on the line and byte-identical to the unflagged
form, so anything resolving that syntax still resolves it. The body SHALL remain blockquoted and
otherwise unchanged, and the ordering of blocks SHALL be unaffected by any comment's status.

An `ACTIVE` comment SHALL render with no flag, so a batch with no drift produces an export
byte-identical to one produced before flagging existed.

The exporter SHALL determine all of this from the comment's stored status alone. It SHALL remain a
pure function of the batch, performing no I/O and reading no document or marker.

#### Scenario: A stale comment carries a concise flag

- **WHEN** the batch contains a comment on lines 40–42 of `src/app.py` whose status is `STALE`
- **THEN** its block's first line is the unchanged `@src/app.py#L40-42` token followed by a short
  marker identifying the anchor as unverified, and the block carries no explanatory sentence

#### Scenario: An orphaned comment is flagged differently

- **WHEN** the batch contains a comment whose status is `ORPHANED`
- **THEN** its block carries a short marker identifying the anchored lines as deleted, distinct from
  the marker used for a `STALE` comment

#### Scenario: An active comment is unflagged

- **WHEN** the batch contains only `ACTIVE` comments
- **THEN** the export is byte-identical to what the same batch produced before flagging existed

#### Scenario: Status does not reorder the export

- **WHEN** the batch contains a mix of `ACTIVE`, `STALE`, and `ORPHANED` comments across two files
- **THEN** blocks remain ordered by file path, then by start line, regardless of status

#### Scenario: Flagging requires no I/O

- **WHEN** the exporter renders a flagged comment
- **THEN** it reads only the comment record's status; it opens no file and reads no marker

