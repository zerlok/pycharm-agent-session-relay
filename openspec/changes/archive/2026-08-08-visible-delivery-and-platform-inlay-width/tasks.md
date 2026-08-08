## 1. Delivery — write what the user sees

- [x] 1.1 Move the `REVIEW.md` write out of stage 2. Stage 2 (background) keeps the anchor
  verification reads and `ReviewDelivery.plan`, and returns either `NothingToSubmit` or the exact
  content string; it performs no I/O of its own.
- [x] 1.2 In stage 3 (EDT), resolve the project base directory's `VirtualFile` and find or create the
  `REVIEW.md` child, then write through its `Document` — `FileDocumentManager.getDocument` →
  `setText` → `saveDocument` — inside a `WriteCommandAction` (design D1).
- [x] 1.3 Delete `Files.writeString` and `LocalFileSystem.refreshAndFindFileByNioFile` from the
  service. No path writes `REVIEW.md` behind the Document layer any more.
- [x] 1.4 Produce `Outcome.Written` from the write action that put the text in the document, so the
  signal that authorizes `clear()` cannot be true while the user sees the previous export (D3).
  `clear()` stays where it is, gated on that outcome.
- [x] 1.5 Keep the failure path intact: a throw anywhere in stage 3 preserves the batch, logs via
  `thisLogger().warn`, and reports `Outcome.Failed`.
- [x] 1.6 Update `ReviewDeliveryService`'s class KDoc — the documented three-stage threading split is
  the contract, and the write moving across the hop changes it (D2).

## 2. Delivery — tests that could actually fail

- [x] 2.1 **The regression test:** open `REVIEW.md` in an editor, submit, and assert the **`Document`
  text** contains the new comment. Asserting the file's bytes passes against the unfixed code and is
  not a guard.
- [x] 2.2 Open-file submit clears the batch *and* updates the document — the two happen together
  (`review-delivery` "Submitting against an open REVIEW.md does not lose comments").
- [x] 2.3 `REVIEW.md` absent before submit: the file is created and carries the export.
- [x] 2.4 Re-verify the existing invariants under the new write path: clear on success, preserve on
  failure, nothing-to-submit writes no file.
- [x] 2.5 Mutation-check 2.1 and 2.2: restore the raw `Files.writeString` write, confirm each goes
  red, restore the fix. A test that passes against the defect is worse than no test.

## 3. Inline surfaces — platform placement

- [x] 3.1 Add a small single-component layout that lays its one child out at
  `min(parent.width, readingMeasure)`, pinned leading, and reports that as preferred and maximum
  width (D4). This replaces `InlineWidth.pinLeading`.
- [x] 3.2 Move the stored card's inlay to `editor.addComponentInlay(offset, InlayProperties(),
  component, ComponentInlayAlignment.FIT_VIEWPORT_WIDTH)` in `EditorReviewOverlay`, carrying
  `relatesToPrecedingText`, `showWhenFolded`, `showAbove` and `priority` across.
- [x] 3.3 Move the authoring box's inlay the same way in `CommentDraftController` / `CommentDraft`.
- [x] 3.4 Remove the surfaces' own width capping — the `getMaximumSize` overrides that read
  `InlineWidth.currentWidthPx` — now that the wrapper layout owns it.
- [x] 3.5 Delete `InlineWidthWatcher` and its installation/disposal in `EditorReviewOverlayService`,
  including the per-surface attach/detach registrations.
- [x] 3.6 Reduce `InlineWidth` to `readingMeasurePx` — delete `currentWidthPx`, `baseWidthPx`,
  `resolve`, `availableWidthPx`, `rightMarginPx`, `columnsPx`, `pinLeading` and `MIN_CAP_DP` (D5).

## 4. Inline surfaces — tests

- [x] 4.1 Delete `InlineWidthWatcherTest`; reduce `InlineWidthTest` to the reading measure.
- [x] 4.2 Assert the two decisions that remain ours: the inlay is created with `FIT_VIEWPORT_WIDTH`,
  and the wrapper caps its child at the reading measure.
- [x] 4.3 Assert a configured right margin no longer narrows a surface (`review-annotation` /
  `review-batch` "A narrow right margin does not narrow the …") — the deliberate behavior change,
  pinned so it cannot be reverted by accident.
- [x] 4.4 Check the existing card and box tests for assertions that encode the old width rule and
  update them, listing each one changed.

## 8. The inspections-widget overlap (added after maintainer QA of the first cut)

- [x] 8.1 Reserve the part of the viewport the editor's floating inspections widget covers, in
  `InlineWidth.overlayInsetPx`, measured from the widget's and the viewport's **laid-out bounds** via
  the public `JBScrollPane.getStatusComponent()` — not from the widget's own width, which overhangs
  the scrollbar and so overstates what it costs the content area.
- [x] 8.2 Apply that reserve in `ReadingWidthRow.contentWidthPx`, unconditionally rather than per
  scroll position, so a comment does not re-wrap while the user scrolls past it.
- [x] 8.3 **Regression from the first cut:** `ReadingWidthRow` is a `JPanel` and so was opaque by
  default, painting a panel-coloured band across the row beside the surface. Make it non-opaque.
- [x] 8.4 **Regression from the first cut:** `ReadingWidthRow.getMinimumSize` derived its floor from
  the child. `Component.getMinimumSize` caches its answer from the component's *current size* when no
  explicit minimum was set, so that floor ratcheted up to the width the row was last laid out at and
  the row could never shrink — resizing the editor left the comment at its previous width. The row's
  minimum is now zero; the cap and the widget reserve are applied inside it, so there is nothing a
  floor protects.
- [x] 8.5 A test driven through the platform's real placement and layout, not a stub child: a
  hand-made child with a fixed `getMinimumSize` cannot exhibit the ratchet, which is why the first
  cut's tests passed while the IDE did not.
- [x] 8.6 Tests for all of the above, mutation-checked: reverting either the reserve or the opacity turns the
  matching test red.

## 5. Docs & charter

- [x] 5.1 `docs/ARCHITECTURE.md` §8: correct the claim that `Editor.addComponentInlay` /
  `ComponentInlayRenderer` / `ComponentInlayAlignment` are stable — all three are
  `@ApiStatus.Experimental` in the 2024.2.5 target — and record the ground the dependency actually
  stands on (D6). Close the open note about whether `intellij.platform.collaborationTools` is bundled
  in PyCharm Community 2024.2: it is.
- [x] 5.2 `docs/ARCHITECTURE.md` §5.3: the submit pipeline's documented hop — the write is now on the
  EDT side, the verification reads are what stays off it.
- [x] 5.3 `docs/ARCHITECTURE.md` §3.3: the inline surfaces' hosting path is
  `Editor.addComponentInlay`, not `EditorEmbeddedComponentManager`.

## 6. Gates

- [x] 6.1 `./gradlew compileKotlin --offline` green.
- [x] 6.2 `./gradlew test --rerun-tasks` green — `--rerun-tasks` is required; plain `test` reports
  success from cache without executing anything. Report the count and name any assertion weakened.
- [x] 6.3 Manual QA — **delivery:** submit with `REVIEW.md` open and confirm the editor shows the new
  comment; submit with it closed; submit with the file absent. *(Maintainer, 2026-08-08: "data loss on
  export on viewing REVIEW.md — solved".)*
- [ ] 6.4 **NOT RUN — carried open past archive.** Manual QA of the box's input behavior on the new
  hosting path, the highest risk in this change (design risks): focus moves between box and editor,
  undo/redo stays scoped to the body rather than the file, Enter and Escape behave, and editing an
  existing comment still works. The maintainer's QA covered delivery (6.3) and width (6.5); this was
  not exercised, and no automated test reaches it. If undo turns out to edit the source file instead
  of the comment body, the fallback is in design Open Question 1: keep the read-only card on
  `addComponentInlay` and return the authoring box to `EditorEmbeddedComponentManager`.
- [x] 6.5 Manual QA — **width:** surfaces cap at the reading measure on a wide editor, follow a split
  or window resize, and a configured narrow right margin no longer narrows them. *(Maintainer,
  2026-08-08: "comment size fitting works", after the three follow-up fixes in §8.)*

## 7. Sequencing

- [x] 7.1 Archive `responsive-inline-comment-surfaces` before archiving this change — this change's
  `review-annotation` and `review-batch` deltas are written against its versions of those two
  requirements, which are not yet in the main specs.
- [x] 7.2 Record in `responsive-inline-comment-surfaces` that the inspections-widget overlap is
  **not** addressed here, with the verified reason: `FIT_VIEWPORT_WIDTH` subtracts only the vertical
  scrollbar, and the widget's own avoidance is caret-based, so the platform's review comments behave
  the same way. Its earlier premise — that the GitHub/GitLab plugins do not have this problem — does
  not hold.
