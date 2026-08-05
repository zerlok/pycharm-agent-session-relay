## 0. Orientation

- [ ] 0.1 Read `StoredCommentCard.kt`, `InlineWidth.kt`, `CommentDraft.buildPanel`/`showBox`,
      `RelayStyle.kt` and `EditorReviewOverlayService.kt` end to end before changing anything.
- [ ] 0.2 Read `openspec/changes/archive/2026-08-02-stored-comment-card-presentation/design.md` on the
      layout feedback loop, and `archive/2026-08-05-comment-box-editing-fidelity/design.md` D2-R on the
      `revalidate()` re-measure path. This change reuses the second and must not break the first.
- [ ] 0.3 Scope discipline: presentation layer only (`ui/`). Do **not** touch the domain, storage,
      logic, export or delivery layers, the range/edge-drag geometry, or the tool window.
- [ ] 0.4 Hold D1's invariant in mind throughout — **no measure path may read the surface's own
      width**. If a step seems to need `this.width` inside `getPreferredSize`, stop: that is the CPU
      loop, and the answer is to take the number from the watcher instead.

## 1. The width source

- [ ] 1.1 Add a per-editor width watcher: a `java.awt.event.ComponentAdapter` registered on the
      editor's component that recomputes on `componentResized`, `componentHidden` and `componentShown`
      (the three the reference implementation reacts to).
- [ ] 1.2 Implement the width rule in `InlineWidth`:
      `min(available, readingMeasure, rightMargin when configured)`, with
      `available = max(viewport.width − verticalScrollbarWidth − gutter, 0)`. Resolve design Open
      Question 5 while doing it and delete the `VISIBLE_MARGIN_DP = 16` fudge.
- [ ] 1.3 Replace `BASE_COLUMNS = 80` editor columns with the UI-font-relative reading measure (D4).
      Keep `MIN_CAP_DP` or its equivalent so a tiny right-margin column can never collapse a surface
      below its own chrome.
- [ ] 1.4 Store the last computed width in the watcher and short-circuit when a recompute yields the
      same number, so a window drag does not fan out a revalidate per event (Risks).
- [ ] 1.5 Give the watcher a registration API: surfaces attach on build and detach on dispose, and the
      watcher pushes the current width to each on change.

## 2. Ownership and lifecycle

- [ ] 2.1 Create and dispose the watcher in `EditorReviewOverlayService`, alongside the overlay, on
      `editorCreated` / `editorReleased` (D2).
- [ ] 2.2 Publish it per editor so both surfaces can find it — the card is built per editor by
      `EditorReviewOverlay`, the box per project by `CommentDraftController`.
- [ ] 2.3 Fall back to computing the width directly from the editor when no watcher is registered (an
      editor the service never saw, or a test fixture), so no surface is left without a width.
- [ ] 2.4 Verify no registration outlives its surface: a disposed card or a closed box must detach.

## 3. The card

- [ ] 3.1 Replace the captured `baseWidth` with the watcher's current value as the single width that
      `getPreferredSize` and `doLayout` both read. Keep `contentWidth(insets)` as the one expression
      both sides derive the content width from — only its input changes.
- [ ] 3.2 Measure *and* lay out the body at that same current width in the same pass, so narrowing
      re-wraps the body and grows the card taller instead of clipping the text. Preserve the guarded
      `setSize` so the measure settles to a no-op.
- [ ] 3.3 Lay the header out across the current width, and add the collision rule (D5): reserve the
      Edit/Delete icons' width first, give the author label what remains, so the actions are never
      pushed outside the card.
- [ ] 3.4 Keep the rest-vs-hover height invariant intact — `headerHeight` stays a build-time constant
      and the icons keep being bounded whether or not they are visible.
- [ ] 3.5 Re-size and `revalidate()` in place on a width change; do **not** dispose and re-add the
      inlay (D3).

## 4. The box

- [ ] 4.1 Point `CommentDraft.buildPanel`'s width at the same watcher value, so the box and the card
      stay identical by construction rather than by two similar calls.
- [ ] 4.2 Re-size and revalidate the open box on a width change, reusing `scheduleRemeasure`'s path
      rather than adding a second re-measure route. Check design Open Question 2 — the body is an
      `EditorTextField` that recomputes soft wraps asynchronously, so this may need the same
      `invokeLater` deferral the content path needed.
- [ ] 4.3 Confirm the box still re-sizes correctly across an edge-drag rebuild, where the panel is a
      new instance but the body field is retained.

## 5. The shared body font

- [ ] 5.1 Add the shared body font to `RelayStyle`, and update its KDoc scope sentence to name the
      font instead of being contradicted by it (D6).
- [ ] 5.2 Set it explicitly on the card's body so it stops inheriting `TextArea.font` (Monospaced).
- [ ] 5.3 Set the same value explicitly on the box's body field, so the two are pinned to one source
      rather than agreeing by coincidence.
- [ ] 5.4 Check the font change against the width work: text metrics feed preferred size, so 3.2's
      wrap width and the reading measure in 1.3 must be re-checked once the body font actually changes.

## 6. Tests

- [ ] 6.1 Width rule: given a viewport width, a right-margin setting and a reading measure, the
      computed width is the minimum of the three — including the case where the viewport is the
      smallest, which is the reported defect.
- [ ] 6.2 The watcher pushes a new width to registered surfaces and revalidates them, and does nothing
      when the recomputed width is unchanged (1.4).
- [ ] 6.3 The card's header reserves the icons: at a width too small for both, the icons keep their
      full width and the label is the thing that shrinks — assert on bounds, since nothing renders
      headlessly.
- [ ] 6.4 The card's body is measured and laid out at the same width in one pass (the loop invariant),
      and that width follows the watcher rather than a build-time constant.
- [ ] 6.5 Card body and box body report the same font.
- [ ] 6.6 A surface detaches from the watcher on dispose (2.4).
- [ ] 6.7 Make each test non-vacuous: confirm it fails against the pre-change behaviour, as the
      defect-C regression test in `comment-box-editing-fidelity` was.

## 7. Verify

- [ ] 7.1 `./gradlew compileKotlin --offline` — clean.
- [ ] 7.2 `./gradlew test` — green, with no existing test loosened to accommodate the change.
- [ ] 7.3 Running-IDE checklist — **cannot be run on this machine** (no display). Hand it over and
      record outcomes in design.md "## Open Questions" rather than claiming any of it verified:
      - split the editor right with a card visible → its Edit/Delete icons stay reachable and its body
        re-wraps instead of being clipped (the reported defect);
      - shrink the IDE window to a narrow editor → same;
      - widen it again → the surfaces grow back to the reading measure;
      - open a comment for editing and split while the box is open → the box re-sizes (Open Question 1
        and 2);
      - Ctrl+scroll to change the editor font size → the surfaces re-size;
      - drag the window edge with several cards in one file → no perceptible lag (Open Question 3);
      - read a comment that quotes code in the UI font → still legible, or reconsider the parity
        direction (Open Question 4).
