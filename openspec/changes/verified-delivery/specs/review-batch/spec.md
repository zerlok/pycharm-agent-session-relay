## MODIFIED Requirements

### Requirement: Verify each comment's anchor before an export

At the export sync point, after live positions have been flushed, the plugin SHALL verify **every**
line-anchored comment that carries a recorded anchor text, by comparing that text against the text
found at the comment's recorded line range in its file's **current content**. Verification SHALL
consult the file's content, not a live editor marker, so a comment is verified whether or not its
file happens to be open. When the file is open its in-memory document is the current content; when it
is not, the content is loaded from disk.

A comment whose anchor text still matches SHALL be `ACTIVE`. A comment whose anchor text no longer
matches SHALL be marked `STALE`. A comment whose recorded line range does not exist in the current
content at all SHALL be marked `ORPHANED`. Verification SHALL be a pure comparison — it SHALL NOT
search for the anchor elsewhere in the file and SHALL NOT move the comment.

A comment SHALL NOT be marked `STALE` merely because its line numbers changed: an edit above a
comment shifts its range and its anchor text is unchanged, so it stays `ACTIVE`. Only a change to the
text *under* the comment makes it stale.

A comment that genuinely cannot be verified — it carries no recorded anchor text, or its file cannot
be read — SHALL keep its current status rather than being marked stale by default, so that absence of
evidence is not reported to the agent as evidence of drift. A closed file SHALL NOT count as
unverifiable.

Reading file content is I/O and SHALL NOT run on the EDT.

#### Scenario: Untouched comment stays active

- **WHEN** a review is exported and a commented range's text is unchanged since the comment was written
- **THEN** the comment's status is `ACTIVE` and its export is unflagged

#### Scenario: Rewritten lines make a comment stale

- **WHEN** the text within a commented range is replaced (for example by an agent rewriting the file
  on disk, followed by a refresh) and a review is then exported
- **THEN** that comment is marked `STALE`

#### Scenario: A shift above the comment does not make it stale

- **WHEN** lines are inserted above a commented range, shifting it, but the commented lines
  themselves are unchanged, and a review is exported
- **THEN** the comment remains `ACTIVE` and its exported reference uses the shifted line range

#### Scenario: A closed file's comment is still verified

- **WHEN** a comment is authored, its file is closed, the file is then changed on disk so that
  different text occupies the comment's recorded lines, and a review is exported
- **THEN** the comment is marked `STALE`, exactly as it would be had the file stayed open

#### Scenario: A comment whose lines no longer exist is orphaned at export

- **WHEN** a comment recorded at lines 300–301 is exported and its file is now 50 lines long
- **THEN** the comment is marked `ORPHANED` and its stored line range is unchanged

#### Scenario: A comment with no recorded anchor keeps its status

- **WHEN** a review is exported and a comment carries no recorded anchor text
- **THEN** it keeps its current status and is not marked `STALE`

#### Scenario: An unreadable file does not drift its comments

- **WHEN** a review is exported and a comment's file cannot be read
- **THEN** the comment keeps its current status

#### Scenario: Verification never relocates a comment

- **WHEN** a comment is marked `STALE` or `ORPHANED` at export
- **THEN** its stored line range is unchanged; the plugin does not search for its anchor text
  elsewhere in the file
