## 0. Orientation

- [x] 0.1 Read `StoredCommentCard.kt`, `InlineWidth.kt`, `CommentDraft.buildPanel`/`showBox`,
      `RelayStyle.kt` and `EditorReviewOverlayService.kt` end to end before changing anything.
- [x] 0.2 Read `openspec/changes/archive/2026-08-02-stored-comment-card-presentation/design.md` on the
      layout feedback loop, and `archive/2026-08-05-comment-box-editing-fidelity/design.md` D2-R on the
      `revalidate()` re-measure path. This change reuses the second and must not break the first.
- [x] 0.3 Scope discipline: presentation layer only (`ui/`). Do **not** touch the domain, storage,
      logic, export or delivery layers, the range/edge-drag geometry, or the tool window.
- [x] 0.4 Hold D1's invariant in mind throughout — **no measure path may read the surface's own
      width**. If a step seems to need `this.width` inside `getPreferredSize`, stop: that is the CPU
      loop, and the answer is to take the number from the watcher instead.

## 1. The width source

- [x] 1.1 Add a per-editor width watcher: a `java.awt.event.ComponentAdapter` registered on the
      editor's component that recomputes on `componentResized`, `componentHidden` and `componentShown`
      (the three the reference implementation reacts to). — `InlineWidthWatcher`, plus a
      `VisibleAreaListener` for the inputs that move without a component resize (font size).
- [x] 1.2 Implement the width rule in `InlineWidth`:
      `min(available, readingMeasure, rightMargin when configured)`, with
      `available = max(viewport.width − verticalScrollbarWidth − gutter, 0)`. Resolve design Open
      Question 5 while doing it and delete the `VISIBLE_MARGIN_DP = 16` fudge. — resolved to
      `scrollingModel.visibleArea.width` unadjusted (that rect already excludes gutter and scrollbar);
      see design.md Open Question 5 for the argument and the one running-IDE caveat.
- [x] 1.3 Replace `BASE_COLUMNS = 80` editor columns with the UI-font-relative reading measure (D4).
      Keep `MIN_CAP_DP` or its equivalent so a tiny right-margin column can never collapse a surface
      below its own chrome. — `MIN_CAP_DP` kept, and kept on the *right-margin cap only*: flooring the
      final width would put a surface back outside a genuinely narrow viewport.
- [x] 1.4 Store the last computed width in the watcher and short-circuit when a recompute yields the
      same number, so a window drag does not fan out a revalidate per event (Risks).
- [x] 1.5 Give the watcher a registration API: surfaces attach on build and detach on dispose, and the
      watcher pushes the current width to each on change. — the watcher pushes the *trigger*; the width
      itself is pulled from `InlineWidth.currentWidthPx` at measure time, so no surface holds a copy.

## 2. Ownership and lifecycle

- [x] 2.1 Create and dispose the watcher in `EditorReviewOverlayService`, alongside the overlay, on
      `editorCreated` / `editorReleased` (D2).
- [x] 2.2 Publish it per editor so both surfaces can find it — the card is built per editor by
      `EditorReviewOverlay`, the box per project by `CommentDraftController`. — published as editor
      user data, read through `InlineWidthWatcher.of(editor)`.
- [x] 2.3 Fall back to computing the width directly from the editor when no watcher is registered (an
      editor the service never saw, or a test fixture), so no surface is left without a width. —
      `InlineWidth.baseWidthPx`, covered by `InlineWidthTest` "an editor with no watcher still gets a
      width" (the path every other card/box test runs on).
- [x] 2.4 Verify no registration outlives its surface: a disposed card or a closed box must detach. —
      the card's detach rides its *inlay's* disposal (`EditorReviewOverlay.addCard`), the box's rides
      `hideBox`; both covered by tests.

## 3. The card

- [x] 3.1 Replace the captured `baseWidth` with the watcher's current value as the single width that
      `getPreferredSize` and `doLayout` both read. Keep `contentWidth(insets)` as the one expression
      both sides derive the content width from — only its input changes.
- [x] 3.2 Measure *and* lay out the body at that same current width in the same pass, so narrowing
      re-wraps the body and grows the card taller instead of clipping the text. Preserve the guarded
      `setSize` so the measure settles to a no-op.
- [x] 3.3 Lay the header out across the current width, and add the collision rule (D5): reserve the
      Edit/Delete icons' width first, give the author label what remains, so the actions are never
      pushed outside the card.
- [x] 3.4 Keep the rest-vs-hover height invariant intact — `headerHeight` stays a build-time constant
      and the icons keep being bounded whether or not they are visible.
- [x] 3.5 Re-size and `revalidate()` in place on a width change; do **not** dispose and re-add the
      inlay (D3).

## 4. The box

- [x] 4.1 Point `CommentDraft.buildPanel`'s width at the same watcher value, so the box and the card
      stay identical by construction rather than by two similar calls. — both call
      `InlineWidth.currentWidthPx(editor)`.
- [x] 4.2 Re-size and revalidate the open box on a width change, reusing `scheduleRemeasure`'s path
      rather than adding a second re-measure route. Check design Open Question 2 — the body is an
      `EditorTextField` that recomputes soft wraps asynchronously, so this may need the same
      `invokeLater` deferral the content path needed. — reused as-is, so the width path gets that
      deferral for free; whether one deferred revalidate is enough stays Open Question 2.
- [x] 4.3 Confirm the box still re-sizes correctly across an edge-drag rebuild, where the panel is a
      new instance but the body field is retained. — `hideBox` detaches the old panel and `showBox`
      attaches the new one; because the width is pulled rather than cached, a resize *during* the drag
      is already reflected in the rebuilt box. Covered by the unchanged `CommentDraftEdgeDragTest`.

## 5. The shared body font

- [x] 5.1 Add the shared body font to `RelayStyle`, and update its KDoc scope sentence to name the
      font instead of being contradicted by it (D6).
- [x] 5.2 Set it explicitly on the card's body so it stops inheriting `TextArea.font` (Monospaced).
- [x] 5.3 Set the same value explicitly on the box's body field, so the two are pinned to one source
      rather than agreeing by coincidence.
- [x] 5.4 Check the font change against the width work: text metrics feed preferred size, so 3.2's
      wrap width and the reading measure in 1.3 must be re-checked once the body font actually changes.
      — done in one measure pass (the font is set before any measure), and the re-check found two
      things: the reading measure had to become UI-font-relative for the same reason (1.3), and
      `Label.font` is *bold* under the headless LaF, so the shared font is pinned to plain.

## 6. Tests

- [x] 6.1 Width rule: given a viewport width, a right-margin setting and a reading measure, the
      computed width is the minimum of the three — including the case where the viewport is the
      smallest, which is the reported defect. — `InlineWidthTest`.
- [x] 6.2 The watcher pushes a new width to registered surfaces and revalidates them, and does nothing
      when the recomputed width is unchanged (1.4). — `InlineWidthWatcherTest`.
- [x] 6.3 The card's header reserves the icons: at a width too small for both, the icons keep their
      full width and the label is the thing that shrinks — assert on bounds, since nothing renders
      headlessly. — `StoredCommentCardTest`.
- [x] 6.4 The card's body is measured and laid out at the same width in one pass (the loop invariant),
      and that width follows the watcher rather than a build-time constant. — `StoredCommentCardTest`,
      which also asserts the narrowed body re-wraps and grows the card taller.
- [x] 6.5 Card body and box body report the same font. — `CommentDraftPresentationTest`.
- [x] 6.6 A surface detaches from the watcher on dispose (2.4). — `EditorReviewOverlayTest` (card),
      `InlineWidthWatcherTest` (box).
- [x] 6.7 Make each test non-vacuous: confirm it fails against the pre-change behaviour, as the
      defect-C regression test in `comment-box-editing-fidelity` was. — every new test was run against
      a reverted behaviour and observed to fail; the mutations and their failures are listed in the
      implementation report.

## 7. Verify

- [x] 7.1 `./gradlew compileKotlin --offline` — clean.
- [x] 7.2 `./gradlew test` — green, with no existing test loosened to accommodate the change. —
      `./gradlew test --rerun-tasks`: 184 tests, 0 failures (167 before, +17 new). No existing
      assertion was weakened or deleted.
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
        direction (Open Question 4);
      - **added while implementing:** at a viewport-bound width, check the header's icons are not under
        the vertical scrollbar — if they are, `InlineWidth.availableWidthPx` must subtract it (design
        Open Question 5).
