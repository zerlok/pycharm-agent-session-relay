## 0. Scope discipline

- [ ] 0.1 `./gradlew compileKotlin --offline` green before the first edit.
- [ ] 0.2 No fuzzy matching, no `DocumentTracker`, no re-anchoring. A comment is flagged, never moved.
- [ ] 0.3 Do not keep the marker-based verification as a fast path — design D1 requires deleting it.

## 1. Delivery layer — the pipeline moves

- [ ] 1.1 Add `delivery/ReviewDeliveryService` (project `@Service`) owning: flush live positions (EDT) → verify anchors (background) → `ReviewDelivery.plan` → write `REVIEW.md` (background) → VFS refresh → clear on success / preserve on failure (EDT). `delivery/ReviewDelivery` stays a pure planner.
- [ ] 1.2 Expose the outcome as a value a test can assert on (written / nothing-to-submit / failed), so clear-versus-preserve is observable without an `AnActionEvent`.
- [ ] 1.3 Reduce `ui/SubmitReviewAction` to invoking the service and rendering notifications. It keeps no pipeline logic.
- [ ] 1.4 Log the failure branch with `thisLogger().warn(message, throwable)` — Relay currently has no logging in `src/main` at all.
- [ ] 1.5 Verify the threading split holds: store mutations and `clear()` on the EDT, file reads and the write off it. Assert it rather than assuming it.

## 2. Verification against file content

- [ ] 2.1 Add the verification routine to the delivery layer: for each line-anchored comment with a recorded `anchorText`, resolve its file, get the document via `FileDocumentManager.getDocument`, and compare `Anchoring.matches(anchorText, text[recordedRange])`.
- [ ] 2.2 Range does not fit the document ⇒ `ORPHANED`. Fits and matches ⇒ `ACTIVE`. Fits and differs ⇒ `STALE`.
- [ ] 2.3 No recorded `anchorText`, or the file cannot be resolved/read ⇒ keep current status. A **closed file is not** an unverifiable case.
- [ ] 2.4 Run the content reads under a read action, off the EDT; apply the resulting statuses back on the EDT via `ReviewBatchService.updateStatus`.
- [ ] 2.5 Delete `EditorReviewOverlayService.validateAnchors()` and the `liveState()`-based verification. Keep `liveState()` itself — the position flush still uses it.
- [ ] 2.6 Confirm `DocumentReviewMarkers`' editor-time `ORPHANED`/`ACTIVE` transitions still work and cannot fight the new path (both go through the same idempotent `updateStatus`).

## 3. Export — concise flags

- [ ] 3.1 Replace the sentence-form `STALE` note with a single marker appended to the reference line: `  ⚠️ unverified anchor`.
- [ ] 3.2 Add the `ORPHANED` marker: `  ⚠️ anchor deleted`, keeping the last-known line numbers in the reference.
- [ ] 3.3 Keep `ReviewExporter` pure — status in, text out; no I/O, no document, no marker.

## 4. Tests — the invariants that had none

- [ ] 4.1 **Preserve on failure:** a failed write leaves the batch intact. Drive the service directly; do not construct an action event.
- [ ] 4.2 **Clear on success:** a successful write empties the batch.
- [ ] 4.3 **Nothing to submit:** an empty batch writes no file and reports the empty outcome.
- [ ] 4.4 **Closed-file verification** — the QA repro, as a test: comment at a recorded range, file not open, content changed so the recorded lines hold different text ⇒ `STALE`.
- [ ] 4.5 **Closed-file orphan:** recorded range beyond the end of the file's content ⇒ `ORPHANED`, stored range unchanged.
- [ ] 4.6 **Open-file parity:** the same two cases with the file open produce the same statuses as when closed.
- [ ] 4.7 **No false positive:** content changed only *above* the recorded range ⇒ stays `ACTIVE`.
- [ ] 4.8 **Keeps status when unverifiable:** null `anchorText` ⇒ unchanged; unresolvable file ⇒ unchanged.
- [ ] 4.9 **Export flags:** `STALE` and `ORPHANED` render their distinct markers on the reference line, bodies stay `> `-quoted, no explanatory line is emitted, and an all-`ACTIVE` batch is byte-identical to today's output.
- [ ] 4.10 Update the exporter tests that pin the old sentence-form `STALE` note — this is the one place existing assertions change, and the change is the point.

## 5. Docs & gates

- [ ] 5.1 `docs/ARCHITECTURE.md` §5.2: the out-of-IDE row of the tier table becomes shipped for **detection** (flag), with re-anchoring still `[NOT IMPLEMENTED]`.
- [ ] 5.2 Record the QA finding in §5.2 or §3.3: the platform's reload-from-disk is **diff-based**, so an open file's markers remap correctly on their own. This is why the deferred `DocumentTracker` work is low priority — without it written down it will be re-proposed on the review's original, wrong reasoning.
- [ ] 5.3 `./gradlew compileKotlin --offline` green.
- [ ] 5.4 `./gradlew test` green, with no existing assertion weakened except the exporter's `STALE` text per 4.10.
- [ ] 5.5 Manual QA to re-run the original repro: comment on a line, close the file, edit it externally so the commented lines change, Submit ⇒ `REVIEW.md` carries `⚠️ unverified anchor`. Then the orphan variant: delete enough lines that the recorded range no longer exists ⇒ `⚠️ anchor deleted`.

## 6. Follow-ups left open

- [ ] 6.1 Tool-window status indication (design Open Question 1, carried from the previous change). More pressing now: `ORPHANED` is reachable for files the user never opened, so the tool window is the only place they could notice one before submitting.
- [ ] 6.2 The mid-session marker-invalidation gap reported by the previous change's implementation: a comment whose range is destroyed while its file stays open is not orphaned until the next reconcile. Under this change the export-time check catches it before it can ship, so this drops from a correctness bug to a UI-freshness one.
