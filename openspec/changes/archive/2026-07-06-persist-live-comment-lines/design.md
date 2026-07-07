## Context

Stored comments carry a live `RangeHighlighter` (`EditorReviewOverlay.markers`) that tracks document
edits, so the card/gutter move as the code shifts. The persisted `ReviewComment.subject`, however, is
only rewritten through `ReviewBatchService.updatePosition`. Two callers do that today:

- `SubmitReviewAction` — flushes `EditorReviewOverlayService.currentPositions()` before export.
- `EditorReviewOverlayService.release()` — flushes on `editorReleased` (editor close).

`EditorReviewOverlayService.release()`'s own comment quotes the intended set of sync points —
"export, save, editor close" — but **save is not implemented**. This change adds it, reusing the
existing seams; there is no new position-computation logic.

## Goals / Non-Goals

**Goals:**

- On document save, persist the current live line ranges of that document's comments into the store.
- Keep the marker as the live source of truth while the file is open (no per-keystroke writeback).
- Reuse the idempotent `currentPositions()` → `updatePosition` path; add no new position math.

**Non-Goals:**

- Continuous (debounced, per-edit) writeback — explicitly rejected in favor of discrete sync points.
- Changing export or editor-close behavior.
- Any storage-format or domain-model change.

## Decisions

**Hook document save via `FileDocumentManagerListener.beforeDocumentSaving`.** The listener is
subscribed on the application message bus and parented to the project-scoped
`EditorReviewOverlayService` (the existing owner of all overlays and of `currentPositions()`), so it
disposes with the service and with plugin unload — mirroring how the service already owns the
`EditorFactoryListener`. `beforeDocumentSaving(document)` fires on the EDT just before the write, the
same threading contract the export flush relies on, so `updatePosition` runs safely.

- _Alternative — `AsyncFileListener` / `BulkFileListener` on VFS events:_ fires after the write and
  off a different document identity; more plumbing to map a `VirtualFile` back to the open document
  and overlay. Rejected — `beforeDocumentSaving` gives the `Document` directly.

**Scope the flush to the saved document.** The service adds `syncPositions(document)` that iterates
only the overlays whose `editor.document === document`, pushing each overlay's `currentPositions()`
through `updatePosition`. This avoids re-touching every open file on an unrelated save. `updatePosition`
is already a no-op when the subject is unchanged, so even an over-broad call would be harmless — but
scoping keeps the event cheap.

- _Alternative — reuse `release()`'s existing loop by flushing all overlays:_ simpler but chatty; a
  save of file A would walk file B's and C's markers too. Rejected on cost, not correctness.

**No new tests of position math; test the wiring.** The line-shift → new-`Subject` computation is
already covered where `currentPositions()` is tested. The new coverage is that a save triggers the
flush and that it is document-scoped.

## Risks / Trade-offs

- **[`beforeDocumentSaving` fires for documents with no comments]** → `syncPositions` finds no
  matching overlay (or an overlay with an empty `currentPositions()`) and does nothing; the per-save
  cost is a hash lookup.
- **[A save during an in-progress edit-box drag]** → the edited comment is temporarily represented by
  the draft's own highlighter, not a stored marker; the stored comment's marker still reflects a valid
  range, so the flush stays correct. No special-casing needed.
- **[Multiple splits of the same document]** → several overlays share one document; each yields the
  same positions and `updatePosition` collapses the duplicates idempotently.

## Open Questions

_None._ The sync-point set and threading are settled; this is a narrow wiring addition.
