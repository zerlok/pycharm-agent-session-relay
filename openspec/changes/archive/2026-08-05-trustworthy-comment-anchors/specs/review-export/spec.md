## ADDED Requirements

### Requirement: Flag a comment whose anchor could not be verified

A comment marked `STALE` SHALL still be exported — the user's feedback is never silently dropped —
and its block SHALL carry a visible flag telling the agent that the reference's line numbers are
unverified and SHALL be checked before being acted on.

The flag SHALL be rendered as part of the comment's own block, adjacent to its reference line, and
SHALL NOT alter the reference syntax itself: the `@<path>#L<start>-<end>` form stays exactly as it is
for an unflagged comment, so the agent can still resolve it. The body SHALL remain blockquoted and
otherwise unchanged, and the ordering of blocks SHALL be unaffected by any comment's status.

An `ACTIVE` comment SHALL render exactly as before this change, so a batch with no drift produces a
byte-identical export.

The exporter SHALL determine this from the comment's stored status alone. It SHALL remain a pure
function of the batch, performing no I/O and reading no document or marker.

#### Scenario: A stale comment is exported with a flag

- **WHEN** the batch contains a comment on lines 40–42 of `src/app.py` whose status is `STALE`
- **THEN** the export contains that comment's `@src/app.py#L40-42` reference, its blockquoted body, and
  an adjacent flag stating that the anchor is unverified and should be checked

#### Scenario: An active comment is unflagged

- **WHEN** the batch contains only `ACTIVE` comments
- **THEN** the export is byte-identical to what the same batch produced before this change

#### Scenario: Status does not reorder the export

- **WHEN** the batch contains a mix of `ACTIVE` and `STALE` comments across two files
- **THEN** blocks remain ordered by file path, then by start line, regardless of status

#### Scenario: Flagging requires no I/O

- **WHEN** the exporter renders a stale comment
- **THEN** it reads only the comment record's status; it opens no file and reads no marker

## MODIFIED Requirements

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
