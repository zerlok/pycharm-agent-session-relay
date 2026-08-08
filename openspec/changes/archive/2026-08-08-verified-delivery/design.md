## Context

`trustworthy-comment-anchors` shipped anchor verification against the **live `RangeHighlighter`**:
`EditorReviewOverlayService.validateAnchors()` collects `liveState()` from each open document's
`DocumentReviewMarkers` and skips any comment with no entry (`live[comment.id] ?: continue`).
Maintainer QA on 2026-08-06 found the two gaps this change closes, and one fact that reshapes the
roadmap:

- **Closed file ⇒ no marker ⇒ no verification.** Reproduced: comment on line 9, file closed, external
  edit removing lines 2–5, export ⇒ `@file#L9` unflagged, pointing at former line 13.
- **`ORPHANED` is never flagged.** `ReviewExporter.flagOf` returns a flag only for `STALE`, and
  nothing filters `ORPHANED` out of the export.
- **An *open* file already survives an external rewrite.** The platform's reload-from-disk is
  diff-based, so `RangeMarker`s remap correctly and the comment moves to the right lines on its own.
  The original review's "offsets are reapplied by naive offset" claim was wrong.

The pipeline that would host the fix is still in `ui/`: `SubmitReviewAction.actionPerformed` does the
flush loop, the `validateAnchors()` call, `ReviewDelivery.plan`, `Task.Backgroundable`,
`Files.writeString`, the VFS refresh, and `clear()` on success. `delivery/` holds only the pure
planner. It has no tests.

Constraints: `ui/` may depend only on `ReviewBatchService` + the listener topic; `export/` stays a
pure function of the batch; store mutations and listener callbacks are EDT-only; file I/O is not.

## Goals / Non-Goals

**Goals:**

- A comment is verified on the same terms whether or not its file is open.
- Nothing unresolvable reaches the agent unflagged.
- The clear-versus-preserve decision is reachable from a unit test.
- A field failure leaves a trace in `idea.log`.

**Non-Goals:**

- Fuzzy re-anchoring. Still deferred, and still why a moved comment is flagged rather than relocated.
- `DocumentTracker`-based line mapping. The QA finding above removes most of its motivation; it is no
  longer queued behind this change.
- Any change to editor or tool-window rendering.

## Decisions

### D1. Verify against file content, deleting the marker path rather than extending it

Verification needs the recorded range and the file's text — not a marker. Because the position flush
already runs first, an open file's recorded range **is** its live range by the time verification runs,
so one code path serves both cases:

```
for each line-anchored comment with a recorded anchorText:
    document ← FileDocumentManager.getDocument(vFile)   // in-memory if open, loaded from disk if not
    if recorded range does not fit document → ORPHANED
    else → matches(anchorText, document.text[recordedRange]) ? ACTIVE : STALE
```

`validateAnchors()` moves out of `EditorReviewOverlayService` entirely and the `liveState()`-based
implementation is **deleted**, not kept as a fast path for open files. Two verification paths that
must agree is precisely the class of bug this sequence of changes exists to remove.

*Why `FileDocumentManager.getDocument` over reading bytes:* it returns the in-memory document when one
exists, so an unsaved in-IDE edit is verified against what the user actually sees, and it applies the
file's encoding and line-separator handling rather than re-deriving them.

*Alternative rejected:* keep the marker path for open files and add a disk path for closed ones. Two
sources of truth, and the open path would silently shadow bugs in the closed one.

### D2. The pipeline moves to `delivery/`, which is what makes D1 legal

`FileDocumentManager.getDocument` and the read of its text must happen under a read action and off the
EDT. `SubmitReviewAction` runs on the EDT, which is why verification was written against markers in
the first place. A `delivery/ReviewDeliveryService` owning flush → verify → export → write → refresh →
clear/preserve puts verification inside the existing `Task.Backgroundable`, where I/O is already legal.

Ordering constraint: the flush and the `clear()`/status writes are store mutations and stay on the
EDT; only the verification read and the file write are off it. The service therefore hops: EDT flush →
background verify+write → EDT status updates and clear/preserve.

*Alternative rejected:* keep the pipeline in the action and marshal just the verification to a
background task. It leaves the untestable structure in place — the reason the preserve-on-failure
invariant has no test is that the decision lives in a `Task.Backgroundable` callback inside an
`AnAction`.

### D3. Two concise markers, no prose

| Status | Rendering |
|---|---|
| `ACTIVE` | `@src/app.py#L40-42` |
| `STALE` | `@src/app.py#L40-42  ⚠️ unverified anchor` |
| `ORPHANED` | `@src/app.py#L300-301  ⚠️ anchor deleted` |

The multi-line explanatory note shipped by the previous change is removed. The marker is a label, not
documentation: it appears once per affected comment in a file the agent parses, and a sentence
repeated per comment is noise that dilutes the signal.

The last-known line numbers are kept on an `ORPHANED` reference. They no longer resolve, but they are
the best available clue to what the comment was about, and the marker already says not to trust them.

*Why distinguishable markers:* the agent's correct next action differs — "find where this code went"
versus "this code is gone; the feedback may be obsolete".

### D4. `ORPHANED` becomes an export-time verdict, not only an editor-time one

Today `ORPHANED` is set by `DocumentReviewMarkers` when a restored comment does not fit its document —
which requires the file to be opened. Under D1 the same verdict is reached at export from file
content, so a comment orphaned in a file never opened this session is still flagged. The editor-time
path stays: it is what suppresses the marker and card, and it keeps the tool window honest before a
submit ever happens.

Both paths write through the same idempotent `ReviewBatchService.updateStatus`, so they cannot fight.

## Risks / Trade-offs

- **Loading a closed file's document at submit is I/O the user did not ask for** → Bounded by the
  batch: only files with comments are read, once, inside the background task that was already running.
- **A large file loaded purely to verify** → Same bound; if it ever matters, the recorded range gives
  a cheaper targeted read, but measuring first is the right order.
- **Removing the explanatory note is a regression in agent-facing clarity** → Accepted per the
  maintainer's "concise flag" direction. Mitigation if it proves too terse: one legend line emitted
  once per `REVIEW.md`, not once per comment — which keeps the SSoT property the per-comment sentence
  broke.
- **The EDT ↔ background hop is the classic place to leak a threading bug** → The store mutations
  stay EDT-only and the reads stay off it; the split is stated in D2 and must be asserted, not assumed.

## Migration Plan

None. No persisted format changes; statuses already round-trip. The export text for a `STALE` comment
changes shape (shorter), which affects only the tests that pin it.

## Open Questions

1. Should an `ORPHANED` comment also be surfaced *before* submit — the tool-window status question
   left open by the previous change? Still out of scope here, but this change makes it more pressing:
   `ORPHANED` is now reachable for files the user never opened, so the tool window is the only place
   they could notice one.
2. Does `⚠️` survive into every agent's reader? Unverified. An ASCII fallback remains a one-line
   change, and is now cheaper to make since the marker is a single constant per status.
