## Context

Third relay stage (**Exporter**), consuming the `comment-batch` model. The whole point is a small,
swappable, side-effect-free seam.

## Decisions

### D5 — Exporter is a pure function `ReviewBatch → text`
Serialization (Claude `@path#Lstart-end` + body; markdown) is isolated from delivery. *Why:* the
same function feeds the written `REVIEW.md` today and the deferred live preview later, so they can
never diverge; non-Claude exporters slot in behind the same seam. Being pure also makes it fully
unit-testable without an editor, VFS, or filesystem.

### D-fmt — Pin the single-line reference form to `@path#L<n>`
A single-line comment renders as `@path#L<n>` (not `@path#L<n>-<n>`); a range renders as
`@path#L<start>-<end>`. *Why:* the earlier spec left this open (`@path#L10` vs `@path#L10-10`),
which would make the exporter's unit tests non-deterministic. Pinning `@path#L<n>` for the
single-line case gives one canonical output; Claude resolves it natively. Reading the line range
from the comment's `RangeMarker` at export time (per D3) keeps it accurate after in-IDE edits.

### D-order — Deterministic ordering
The export orders comments by file path, then by start line. *Why:* deterministic output is
testable and gives the agent a stable, top-to-bottom reading order.

## Risks / Trade-offs

- **Path form** — paths are project-relative with forward slashes so `@path#L` resolves regardless
  of host OS; absolute paths would leak the local layout to the agent.
- **Body escaping** — bodies are free text; the exporter must not let a body break the markdown
  structure (e.g. a leading `@` or code fence). Handle at implementation; covered by a test.
