## Why

Once comments live in a batch (`review-export` depends on `comment-batch`), they must become
something an agent can read. This change adds the **Exporter** stage of the relay pipeline
([`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) §3): a pure function that serializes a
`ReviewBatch` to agent-readable markdown using Claude Code's native `@path#L` reference syntax. It
is deliberately isolated from *delivery* (writing/sending the file) so the same serialization feeds
today's `REVIEW.md` write and a later live in-IDE preview without diverging.

## What Changes

- **Exporter as a pure function** `ReviewBatch → String` (markdown), with no I/O and no dependency
  on where the output goes.
- **Claude reference format.** A line-anchored comment renders as `@<project-relative-path>#L<start>-<end>`
  followed by its body; a single-line comment renders in the pinned single-line form (see design D-fmt).
- **Empty-batch handling.** Exporting an empty batch yields a well-defined result the delivery stage
  can treat as "nothing to submit".

## Out of scope (later stages / changes)

- Writing the export to `REVIEW.md`, notifying the user, and clearing the batch — the **Delivery**
  stage (`review-delivery`).
- Non-Claude exporters (the seam exists; only the Claude profile ships now) and the in-tool-window
  live preview (reuses this same pure function later).
- Whole-file / multi-file / project subject rendering — only the line/range scope is authored, so
  only it is exported here.

## Capabilities

### New Capabilities
- `review-export`: Serialize a pending batch to Claude-format markdown (`@path#L` references + body)
  as a pure, I/O-free function.

## Impact

- **Code**: a pure exporter function/module taking the batch and returning a string; unit-tested in
  isolation (no editor/VFS needed).
- **Depends on**: `comment-batch` (the `ReviewComment` / `ReviewBatch` model).
