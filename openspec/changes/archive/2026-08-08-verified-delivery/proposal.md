## Why

`trustworthy-comment-anchors` armed anchor verification, and maintainer QA on 2026-08-06 found it
covers less than the proposal claimed. Two holes, both of which put an unflagged, wrong reference in
front of the agent:

1. **A closed file is never verified.** `validateAnchors` reads current anchor text off the live
   `RangeHighlighter`, so a comment whose file has no open editor is skipped and exports `ACTIVE`.
   Reproduced: comment on line 9 → close the file → external edit removing lines 2–5 → export yields
   `@file#L9`, unflagged, now pointing at what used to be line 13. This was accepted in that change's
   Risks on the reasoning that "the common loop has the file open" — wrong for Relay's premise, where
   the agent edits files the user is *not* looking at.
2. **An orphaned comment exports unflagged.** `ORPHANED` — the status that means "these lines no
   longer exist" — is fully defined for the editor and the tool window and then ignored by the one
   surface that talks to the agent. The exporter flags `STALE` only, so a comment recorded at
   `L300-301` of a since-truncated file exports as a clean-looking `@file#L300-301`. The
   `review-export` delta in that change never mentioned `ORPHANED` either, so the code matched an
   incomplete spec.

Verification does not actually need a marker — it needs the recorded range and the file's content.
The reason it was written against markers is that reading a file is I/O, illegal on the EDT where
`SubmitReviewAction` runs today. That constraint is exactly what the deferred delivery-layer refactor
removes, so the fix and the refactor are one piece of work.

That refactor is also still owed from the original review: the whole delivery pipeline lives in an
`AnAction` in `ui/` while `delivery/` holds only a pure planner, and it has **zero tests** —
including for the invariant that a failed write must preserve the user's batch.

## What Changes

- **Verification reads file content, not markers.** After the position flush, each line-anchored
  comment is verified by comparing its recorded `anchorText` against the text at its recorded range,
  read through `FileDocumentManager` (the in-memory document when the file is open, loaded from disk
  when it is not). This *removes* the "no live marker → skip" special case rather than adding a
  branch, and makes "unverifiable" mean what it should: the file is gone, or the comment has no
  recorded anchor — not merely that a tab is closed.
- **A comment whose recorded range no longer exists is marked `ORPHANED` at export**, not only when
  its editor happens to be open.
- **Both non-`ACTIVE` statuses are flagged in the export, concisely** — a single short marker on the
  reference line, no explanatory sentence: `⚠️ unverified anchor` for `STALE`, `⚠️ anchor deleted`
  for `ORPHANED`. **BREAKING** relative to the previous change only in export text: the multi-line
  `STALE` note is replaced by the short marker.
- **The delivery pipeline moves into `delivery/`.** A service owns flush → verify → export → write
  `REVIEW.md` off-EDT → refresh VFS → clear-on-success/preserve-on-failure. `SubmitReviewAction`
  becomes an `AnAction` that calls it and renders notifications.
- **The write-failure path gains a log entry.** Relay currently has no logging anywhere in
  `src/main`, so a field failure leaves nothing in `idea.log`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `review-export`: defines the concise flag for both `STALE` and `ORPHANED`, replacing the
  sentence-form `STALE` note.
- `review-batch`: anchor verification is defined against file content rather than a live marker, so
  it covers comments whose file is not open; `ORPHANED` is reachable at the export sync point.
- `review-delivery`: the submit pipeline is a testable service in the delivery layer; the
  preserve-the-batch-on-failure invariant becomes a stated requirement rather than an implementation
  detail, and a failed write is logged.

## Impact

**Code**

- `delivery/ReviewDeliveryService` (new) — owns the pipeline; `delivery/ReviewDelivery` stays pure.
- `ui/SubmitReviewAction.kt` — reduced to invoking the service and rendering notifications.
- `ui/EditorReviewOverlayService.kt` — `validateAnchors()` moves out; the marker-based path is deleted,
  not kept alongside.
- `export/ReviewExporter.kt` — flag rendering for both statuses, shortened. Stays pure.
- `logic/ReviewBatchService.kt` — unchanged API; `updateStatus` is now also called from the delivery
  layer.

**Docs**

- `docs/ARCHITECTURE.md` §5.2 — the tier table's "out-of-IDE" row becomes shipped-for-detection.
  Note the QA finding that the platform's reload-from-disk is **diff-based**, so an *open* file's
  markers remap correctly by themselves; that materially lowers the priority of the deferred
  `DocumentTracker` work and should be recorded rather than left as folklore.

**Not in scope**

- Fuzzy re-anchoring — still deferred, and still the reason a moved comment is flagged rather than
  relocated.
- Any change to how comments render in the editor or the tool window.
