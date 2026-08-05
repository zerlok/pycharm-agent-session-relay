## 0. Scope discipline

- [x] 0.1 Confirm the working branch and that `./gradlew compileKotlin --offline` is green before any edit, so a later failure is attributable to this change.
- [x] 0.2 Keep the delivery-pipeline refactor out: `ui/SubmitReviewAction` gains one call and nothing else. Everything else it does today stays put for `delivery-service-seam`.
- [x] 0.3 Do not add fuzzy matching, `contextHash` validation, or any code path that *moves* a comment. If a task seems to need one, stop and re-read design Non-Goals.

## 1. Domain — the anchor predicate

- [x] 1.1 Add `Anchoring.matches(recorded: String?, current: String): Boolean` (design D1): `true` when `recorded` is null; otherwise compare after normalizing `\r\n` → `\n` and stripping trailing whitespace per line. Pure, no platform imports.
- [x] 1.2 Extend `AnchoringTest` — identical text matches; trailing-whitespace-only and CRLF-only differences match; a changed word does not; null recorded matches; empty-vs-null are distinguished.
- [x] 1.3 Update the `CommentStatus` KDoc at `domain/ReviewComment.kt:10-11`: `STALE` / `ORPHANED` are no longer "a later change" — document what each now means per the `review-batch` delta.

## 2. Logic — the status command

- [x] 2.1 Add `ReviewBatchService.updateStatus(id, status)` following the shape of `updateBody` / `updatePosition`: EDT-only, publishes `commentUpdated`, and **no-ops with no event when the status is unchanged** (design D4's reentrancy contract).
- [x] 2.2 Add the matching `ReviewBatchStorage` update path if one is needed; keep storage dumb CRUD. *(None needed: `update(comment)` already replaces a record in place, and the status rides that same path — adding a status-specific method would have put policy in storage.)*
- [x] 2.3 Test in `ReviewBatchServiceTest`: status change notifies once; setting the same status again notifies zero times; unknown id is a no-op.
- [x] 2.4 Test in `PersistentReviewBatchStorageTest` that a non-`ACTIVE` status round-trips (it already serializes — pin it so it cannot regress).

## 3. Presentation — per-document marker ownership

- [x] 3.1 Extract `ui/DocumentReviewMarkers`: owns the `RangeHighlighter` map, `markerSubjects`, and `reconcileMarkers()` for one `Document`, writing to `DocumentMarkupModel.forDocument` exactly as today (design D2).
- [x] 3.2 Have `EditorReviewOverlayService` create it on the first qualifying editor for a document and dispose it when the last one is released — reference-counted, driven only from the existing `editorCreated` / `editorReleased` path, parented like overlays are today.
- [x] 3.3 Strip marker ownership out of `EditorReviewOverlay`; it keeps cards, card-hover highlight, and edit suppression. Its `document` field stays (the save-flush scoping uses it).
- [x] 3.4 Add `DocumentReviewMarkers.liveState(): Map<CommentId, LiveAnchor>` reading position **and** current anchor text from each marker in one pass (design D3); express `currentPositions()` as a projection of it.
- [x] 3.5 Re-point `EditorReviewOverlayService.currentPositions()` / `syncPositions()` / `flush()` at the document owner instead of iterating overlays.
- [x] 3.6 Re-point `EditorReviewOverlayTest`'s `allHighlighters.single { … }` assertions (`:169`, `:198`) at the new owner.
- [x] 3.7 New test: open two `MAIN_EDITOR`s on one document, add a comment, assert exactly **one** gutter-bar highlighter exists; close one editor and assert the marker survives; close both and assert it is gone. This case is currently inexpressible and is the regression net for D2.

## 4. Presentation — orphaned instead of clamped

- [x] 4.1 Remove the `coerceIn` clamp in `addMarker`; when the recorded range does not fit the document, create no marker and no card.
- [x] 4.2 Mark such a comment `ORPHANED` via `updateStatus` during reconcile, and transition it back to `ACTIVE` when it fits again.
- [x] 4.3 Verify the reconcile → store-mutation → event → reconcile loop terminates, and add the test that pins it (a comment going orphaned triggers exactly one settling pass, not an unbounded cascade).
- [x] 4.4 Test: a comment recorded at lines 300–301 restored into a 50-line document is `ORPHANED`, has no marker and no card, and its stored subject still reads 300–301.
- [x] 4.5 Test the D2-critical invariant directly: after that restore, run a save flush and an editor-close flush and assert the stored subject is **still** 300–301.

## 5. Presentation — the comment box submits its live range

- [x] 5.1 Derive `submit()`'s line range from `highlighter.startOffset` / `endOffset` via `document.getLineNumber` (design D8), falling back to `start`/`end` clamped into the document when the highlighter is invalid.
- [x] 5.2 Derive `rangeStartOffset()`, `rangeEndOffset()`, and `contextWindow()` from the same source, removing the unguarded `getLineStartOffset(start)`.
- [x] 5.3 Test: with a box open over lines 40–42, shift the document so the highlight moves, submit, and assert the stored subject **and** the stored `anchorText` describe the moved range.
- [x] 5.4 Test: with a box open near the end of the file, replace the document with a shorter one, submit, and assert no exception and a range within the new document.

## 6. Export sync point — validation

- [x] 6.1 Add `EditorReviewOverlayService.validateAnchors()`: for each document owner, compare each comment's recorded `anchorText` against `liveState()`'s current text via `Anchoring.matches`, and `updateStatus` to `STALE` or `ACTIVE` accordingly. Skip comments with no live entry and comments with a null recorded anchor (design D6).
- [x] 6.2 Call it from `SubmitReviewAction` immediately after the existing position flush and before `export` — one statement, adjacent to the flush, so both move together later.
- [x] 6.3 Test: unchanged text → `ACTIVE`; text under the comment replaced → `STALE`; lines inserted *above* the comment (marker shifts, text unchanged) → stays `ACTIVE`.
- [x] 6.4 Test: a comment whose file has no open editor keeps its status; a comment with null `anchorText` keeps its status.
- [x] 6.5 Test: validation never changes a stored subject.

## 7. Export — the flag

- [x] 7.1 Render a `STALE` comment per design D7: `⚠️ unverified anchor` appended to the reference line, then an unquoted note line, then the unchanged blockquoted body. `ReviewExporter` stays pure and reads only `status`.
- [x] 7.2 Test: a `STALE` comment's block contains the unmodified `@path#L…` token, the flag, and the note; the body stays `> `-quoted.
- [x] 7.3 Test: an all-`ACTIVE` batch produces output byte-identical to the pre-change exporter — assert against the existing expected strings, unchanged.
- [x] 7.4 Test: a mixed batch still orders blocks by path then start line, unaffected by status.

## 8. Docs & gates

- [x] 8.1 Remove the `[NOT IMPLEMENTED]` tag and the "safety net is captured but never armed" paragraph from `docs/ARCHITECTURE.md` §5.2, and update the tier table to show tier 2 as validation-and-flag (fuzzy re-anchoring still deferred).
- [x] 8.2 Remove the marker-ownership "known deviation" note from §3.3 — the code now matches the rule.
- [x] 8.3 Update §5.4's `[NOT IMPLEMENTED]` re-anchoring paragraph to describe the orphaned-on-open behavior that now exists, keeping the lazy-re-anchor intent tagged as still deferred.
- [x] 8.4 `./gradlew compileKotlin --offline` green.
- [x] 8.5 `./gradlew test` green (needs network), with the new tests from sections 1–7 counted and no existing test loosened or deleted to make room. *(155 tests, 0 failed — 122 before, 33 new. The only existing-test edits re-point `overlay.currentPositions()` / the two `allHighlighters.single { … }` reads at the new document owner; every assertion is preserved verbatim.)*
- [x] 8.6 Record the two `runIde` checks this environment cannot run as manual QA. **Both are UNVERIFIED here — no display, so `runIde` cannot start:**
  - [ ] 8.6a Split a commented file into two editor splits: each split shows **one** resting gutter bar over the commented range (not a doubled/darker bar), the bar tracks edits in either split, and closing one split leaves the other's bar intact.
  - [ ] 8.6b Author a comment, have an agent rewrite those lines on disk, refresh, then Submit: `REVIEW.md` carries the unchanged `@path#L…` token followed by `⚠️ unverified anchor` and the note line, and the `⚠️` survives the round trip into the agent's reader (design Open Question 3 — an ASCII `[!]` fallback is a one-line change if it does not).

## 9. Follow-ups this change deliberately leaves open

- [ ] 9.1 Answer design Open Question 1 (status shown in the tool window) — needs a `review-batch` requirement change, so it is its own small proposal if accepted. *(Deliberately not built by this change. A comment marked `ORPHANED` is currently indistinguishable from an `ACTIVE` one in the tool window, which is the safety valve the design leans on — worth closing, but it widens `review-batch`'s tool-window requirement.)*
- [ ] 9.2 Note in the `delivery-service-seam` change that it must carry the `validateAnchors()` call along with the flush when it moves the pipeline into `delivery/`. *(That change does not exist yet; `SubmitReviewAction.actionPerformed` keeps the flush loop and `overlayService.validateAnchors()` adjacent so they move as one unit.)*
