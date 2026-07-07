## Why

A stored comment's live `RangeHighlighter` tracks in-IDE edits, so its card and gutter marker slide
up and down as lines are inserted or removed. But the persisted `ReviewComment.subject` only gets
rewritten at two of the three sync points ARCHITECTURE §3.2 names — **export** and **editor close**.
The third, **document save**, is never wired up. So a comment authored, shifted by edits, then the
file saved (but left open) keeps stale line numbers in the store until the editor is finally closed —
and anything reading the store meanwhile (a reopen, an out-of-IDE reader) sees the wrong lines.

## What Changes

- Add **document save** as a position-sync point: when a file is saved, flush the current
  live-marker line ranges of that file's comments into the store (via the existing
  `updatePosition` seam), so persisted positions match what the user sees.
- The existing export-time and editor-close sync points are unchanged; the new save hook uses the
  same idempotent `currentPositions()` → `updatePosition` path they already use.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `review-batch`: add a requirement that the store's persisted comment positions are synced from the
  live markers at the defined sync points — export, editor close, and (newly) document save.

## Impact

- New app-level `FileDocumentManagerListener` (document-save hook), owned by the project-scoped
  `EditorReviewOverlayService` and parented to it.
- `EditorReviewOverlayService`: a per-document flush that pushes affected overlays'
  `currentPositions()` through `ReviewBatchService.updatePosition`.
- No domain, storage, or export changes — `updatePosition` and `currentPositions()` already exist and
  are idempotent (no-op when a subject is unchanged).
