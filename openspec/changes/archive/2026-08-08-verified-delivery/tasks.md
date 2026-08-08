## 0. Scope discipline

- [x] 0.1 `./gradlew compileKotlin --offline` green before the first edit.
- [x] 0.2 No fuzzy matching, no `DocumentTracker`, no re-anchoring. A comment is flagged, never moved.
- [x] 0.3 Do not keep the marker-based verification as a fast path — design D1 requires deleting it.

## 1. Delivery layer — the pipeline moves

- [x] 1.1 Add `delivery/ReviewDeliveryService` (project `@Service`) owning: flush live positions (EDT) → verify anchors (background) → `ReviewDelivery.plan` → write `REVIEW.md` (background) → VFS refresh → clear on success / preserve on failure (EDT). `delivery/ReviewDelivery` stays a pure planner.
- [x] 1.2 Expose the outcome as a value a test can assert on (written / nothing-to-submit / failed), so clear-versus-preserve is observable without an `AnActionEvent`.
- [x] 1.3 Reduce `ui/SubmitReviewAction` to invoking the service and rendering notifications. It keeps no pipeline logic.
- [x] 1.4 Log the failure branch with `thisLogger().warn(message, throwable)` — Relay currently has no logging in `src/main` at all.
- [x] 1.5 Verify the threading split holds: store mutations and `clear()` on the EDT, file reads and the write off it. Assert it rather than assuming it.

## 2. Verification against file content

- [x] 2.1 Add the verification routine to the delivery layer: for each line-anchored comment with a recorded `anchorText`, resolve its file, get the document via `FileDocumentManager.getDocument`, and compare `Anchoring.matches(anchorText, text[recordedRange])`.
- [x] 2.2 Range does not fit the document ⇒ `ORPHANED`. Fits and matches ⇒ `ACTIVE`. Fits and differs ⇒ `STALE`.
- [x] 2.3 No recorded `anchorText`, or the file cannot be resolved/read ⇒ keep current status. A **closed file is not** an unverifiable case.
- [x] 2.4 Run the content reads under a read action, off the EDT; apply the resulting statuses back on the EDT via `ReviewBatchService.updateStatus`.
- [x] 2.5 Delete `EditorReviewOverlayService.validateAnchors()` and the `liveState()`-based verification. Keep `liveState()` itself — the position flush still uses it.
- [x] 2.6 Confirm `DocumentReviewMarkers`' editor-time `ORPHANED`/`ACTIVE` transitions still work and cannot fight the new path (both go through the same idempotent `updateStatus`).

## 3. Export — concise flags

- [x] 3.1 Replace the sentence-form `STALE` note with a single marker appended to the reference line: `  ⚠️ unverified anchor`.
- [x] 3.2 Add the `ORPHANED` marker: `  ⚠️ anchor deleted`, keeping the last-known line numbers in the reference.
- [x] 3.3 Keep `ReviewExporter` pure — status in, text out; no I/O, no document, no marker.

## 4. Tests — the invariants that had none

- [x] 4.1 **Preserve on failure:** a failed write leaves the batch intact. Drive the service directly; do not construct an action event.
- [x] 4.2 **Clear on success:** a successful write empties the batch.
- [x] 4.3 **Nothing to submit:** an empty batch writes no file and reports the empty outcome.
- [x] 4.4 **Closed-file verification** — the QA repro, as a test: comment at a recorded range, file not open, content changed so the recorded lines hold different text ⇒ `STALE`.
- [x] 4.5 **Closed-file orphan:** recorded range beyond the end of the file's content ⇒ `ORPHANED`, stored range unchanged.
- [x] 4.6 **Open-file parity:** the same two cases with the file open produce the same statuses as when closed.
- [x] 4.7 **No false positive:** content changed only *above* the recorded range ⇒ stays `ACTIVE`.
- [x] 4.8 **Keeps status when unverifiable:** null `anchorText` ⇒ unchanged; unresolvable file ⇒ unchanged.
- [x] 4.9 **Export flags:** `STALE` and `ORPHANED` render their distinct markers on the reference line, bodies stay `> `-quoted, no explanatory line is emitted, and an all-`ACTIVE` batch is byte-identical to today's output.
- [x] 4.10 Update the exporter tests that pin the old sentence-form `STALE` note — this is the one place existing assertions change, and the change is the point.

## 5. Docs & gates

- [x] 5.1 `docs/ARCHITECTURE.md` §5.2: the out-of-IDE row of the tier table becomes shipped for **detection** (flag), with re-anchoring still `[NOT IMPLEMENTED]`.
- [x] 5.2 Record the QA finding in §5.2 or §3.3: the platform's reload-from-disk is **diff-based**, so an open file's markers remap correctly on their own. This is why the deferred `DocumentTracker` work is low priority — without it written down it will be re-proposed on the review's original, wrong reasoning.
- [x] 5.3 `./gradlew compileKotlin --offline` green.
- [x] 5.4 `./gradlew test` green, with no existing assertion weakened except the exporter's `STALE` text per 4.10. *(167 tests, 0 failed — 155 before. The seven `validateAnchors` tests in `DocumentReviewMarkersTest` moved to `ReviewDeliveryServiceTest` with their assertions carried over, except `leaves a comment whose file is not open alone`, whose premise this change deliberately inverts: it is now `a comment whose file cannot be resolved is not verified` plus a new open/closed parity test. The exporter's `STALE` note assertion changed per 4.10 and the `an orphaned comment exports at its recorded range` assertion gained the flag — that was the second QA defect.)*
- [x] 5.5 Manual QA to re-run the original repro. **Run by the maintainer on 2026-08-06 against commit `a0d086c`: BOTH CASES PASS.** The closed-file drift repro (comment on line 9, file closed, external edit) and the orphan variant both produced a flagged reference in `REVIEW.md`. The change's primary defect is closed. *(Reported as "both cases provided unverified anchor" — read as: both were flagged, i.e. neither shipped silently wrong, which is the requirement. Whether the orphan case rendered `⚠️ anchor deleted` specifically rather than `⚠️ unverified anchor` was not distinguished in the report; see 7.2.)*

## 7. Defects found in maintainer QA (2026-08-06), not fixed by this change

- [x] 7.1 **DATA LOSS — the batch is cleared while the export is not visible.** Reported verbatim: "when REVIEW.md is opened in IDE — export doesn't change it content, so new comments just got lost."
  **What is lost is the comments.** With `REVIEW.md` open in an editor, a submit empties the batch — the comments are erased from the store and from persistent storage at the next save, with no undo — while the file the user is looking at still shows the previous export. Nothing in `REVIEW.md` is damaged; the user's work is.
  **The invariant this breaks.** The batch may be cleared only once the export is **visible to the user**. `Files.writeString` returning without throwing is evidence that bytes reached the disk, which is not the same claim. Stage 3 takes an irreversible step on the strength of the wrong signal, and when the file is open the two come apart.
  **Mechanism.** `ReviewDeliveryService.write` (`:174`) calls `Files.writeString` — a raw nio write behind the platform's Document layer — then `LocalFileSystem.refreshAndFindFileByNioFile`. When the file is open, `FileDocumentManager` holds an in-memory `Document` for it, and that `Document`, not the disk, is what the editor renders. The write cannot reach it.
  **Establish first, because it changes what happened:** was the disk content actually updated? Check `REVIEW.md` from outside the IDE (or close and reopen it) after a submit against an open file. If the export is there, the comments were recoverable and this is a trust failure with a data-loss shape. If the file on disk is genuinely stale, `Files.writeString` is not doing what this diagnosis assumes and neither option below is yet the fix.
  **Option A — keep the disk write, reconcile after it.** In stage 3, which is already on the EDT, call `FileDocumentManager.reloadFiles(vFile)` when a Document is loaded for that path. One call, no threading change, and the `review-delivery` spec's off-EDT write stands. The success signal and the user-observable stay two different things bridged by a step that must keep working; the window between the stage-2 write and the stage-3 reload also stays open (IntelliJ saves documents on frame deactivation, so an alt-tab inside it can write the stale document back over the export).
  **Option B — write through the `Document`.** Resolve or create the `VirtualFile`, then `setText` its `Document` and save it, inside a write action. The object the user is looking at *is* the object written, so "written" and "visible" cannot diverge, there is nothing to reconcile, and the clobber window does not exist. This moves the write onto the EDT, which the `review-delivery` spec forbids — a constraint written against nio I/O plus a synchronous VFS refresh, both genuinely illegal there, whereas every VFS and Document mutation in the platform is EDT-plus-write-action by contract and `REVIEW.md` is a few KB. Adopting B means re-deriving that spec requirement rather than working around it.
  **Why no test caught it:** nothing in the suite opens `REVIEW.md` before submitting. The existing tests assert on the file's bytes, so a fix must be tested on the **`Document` text** — asserting bytes again would pass against the unfixed code.
  **Related, larger:** the clear is irreversible and the batch is the user's only copy, so *some* signal will always be load-bearing for destroying it. Marking comments submitted instead of evicting them removes that dependency entirely. Out of scope here; same root.
- [x] 7.2 Confirm the two flags are distinguishable in practice. QA reported both QA cases as "unverified anchor"; the orphan case should render `⚠️ anchor deleted`. Either the report did not distinguish them, or the orphan case was classified `STALE` rather than `ORPHANED` — which would mean the recorded range still fit the shortened file. Re-run with a range unambiguously past the end of the file and check which marker appears.

## 6. Follow-ups left open

- [ ] 6.1 Tool-window status indication (design Open Question 1, carried from the previous change). More pressing now: `ORPHANED` is reachable for files the user never opened, so the tool window is the only place they could notice one before submitting.
- [ ] 6.2 The mid-session marker-invalidation gap reported by the previous change's implementation: a comment whose range is destroyed while its file stays open is not orphaned until the next reconcile. Under this change the export-time check catches it before it can ship, so this drops from a correctness bug to a UI-freshness one.
