## 1. Shared accent color

- [x] 1.1 In `src/main/kotlin/io/github/zerlok/agentsessionrelay/ui/RangeHighlight.kt`, add an internal
      `JBColor` accent constant (light/dark pair) beside `gutterBar` (`RangeHighlight.kt:42`) — the
      single home for the stored-comment accent, used by both the card's left bar and the resting gutter
      bar. Document *why* it does not reuse `CommentDraft.RANGE_BACKGROUND` (a wash color, invisible as a
      line) and that unifying it with `CommentDraft`'s private `EDGE_ACTIVE` (`CommentDraft.kt:429`) is a
      follow-up, because `CommentDraft.kt` is out of scope here.
- [x] 1.2 Do **not** change `gutterBar`'s signature or `BAR_WIDTH_DP` (`RangeHighlight.kt:34`) in this
      task; the resting bar reuses the existing factory as-is.

## 2. Card visual identity

- [x] 2.1 In `StoredCommentCard.kt`, replace the card's `background = editor.colorsScheme.defaultBackground`
      (`StoredCommentCard.kt:124`) with the platform panel/UI-surface background
      (`UIUtil.getPanelBackground()`), so the card no longer shares the code's fill.
- [x] 2.2 Replace the toolbar's `background = editor.colorsScheme.defaultBackground`
      (`StoredCommentCard.kt:68`) with the same card background — it is an opaque patch inside the card
      and would otherwise show as a seam. (The `FlowLayout` toolbar container is gone entirely, replaced
      by the header row of task 3.2; the header carries the shared card background.)
- [x] 2.3 Rebuild the border (`StoredCommentCard.kt:125-128`) as: the existing 1px
      `customLine(JBColor.border(), 1)` outline **plus** a left-only accent line
      (`customLine(ACCENT, 0, ACCENT_WIDTH, 0, 0)`) **plus** the existing `empty(8, 12)` padding, nested
      with `JBUI.Borders.compound`. The accent must be carried by the *border* (so it lands in `insets`),
      never by an extra child component. (`JBUI.Borders.compound` has a vararg overload, so all three
      nest in one call. `customLine(color, t, l, b, r)` wraps its widths in `JBInsets`, which scales
      them itself — so `ACCENT_WIDTH_DP` is passed raw, NOT through `JBUI.scale`.)
- [x] 2.4 Verify by inspection that `contentWidth` (`StoredCommentCard.kt:87`) is left defined as
      `baseWidth - insets.left - insets.right` and is still the ONLY width both `getPreferredSize` and
      `doLayout` use — the accent's width must reach both sides through `insets` alone. Do not introduce a
      second width constant.

## 3. Always-present header row

- [x] 3.1 Add an author `JBLabel` with the view-level constant text `"You"` (de-emphasised foreground,
      e.g. `UIUtil.getContextHelpForeground()`). It is a hardcoded view string — do NOT add an author
      field to `ReviewComment` or any other domain/storage type.
- [x] 3.2 Add a header `JPanel` child (opaque, card background, no layout manager) holding the author
      label and the existing `editButton` / `deleteButton` (`StoredCommentCard.kt:64-65`). Add it to the
      card ahead of `bodyArea` and position its children explicitly (label left, buttons right) rather
      than relying on a layout manager, so an invisible button cannot collapse the row. (Implemented as
      an `object : JPanel()` overriding `doLayout` — the same idiom the card itself uses — rather than
      passing a `null` layout manager: with `doLayout` overridden the default manager is never consulted.
      The header lays out its children whether or not they are visible, so a reveal is a repaint.)
- [x] 3.3 Capture the header's height ONCE at build time as
      `max(authorLabel.preferredSize.height, editButton.preferredSize.height, deleteButton.preferredSize.height)`,
      read **before** the buttons are hidden, into a local `val`. This constant — never a live
      `preferredSize` read — is what keeps the row's height independent of hover state.
- [x] 3.4 Keep the Edit/Delete icons hidden at rest and revealed on hover, now by toggling their
      visibility inside the reserved header row (adapt `setToolbarVisible`, `StoredCommentCard.kt:180-187`).
      The old floating top-right overlay branch in `doLayout` (`StoredCommentCard.kt:117-120`) is replaced
      by the header's fixed placement. (`setToolbarVisible` became `setActionsVisible(card, editButton,
      deleteButton, visible)` — with no toolbar container left, visibility toggles on the two buttons.)
- [x] 3.5 Update `getPreferredSize` (`StoredCommentCard.kt:96-105`) to return
      `headerHeight + HEADER_GAP + bodyArea.preferredSize.height + insets.top + insets.bottom`, keeping
      the width at `baseWidth` and keeping the guarded `if (bodyArea.width != cw) bodyArea.setSize(cw, Int.MAX_VALUE)`
      (`StoredCommentCard.kt:103`) exactly as it is.
- [x] 3.6 Update `doLayout` (`StoredCommentCard.kt:107-121`) to place the header at
      `(insets.left, insets.top, cw, headerHeight)` and the body at
      `(insets.left, insets.top + headerHeight + HEADER_GAP, cw, height - insets.top - insets.bottom - headerHeight - HEADER_GAP)`.
      The body must still be laid out at `cw`, the same value `getPreferredSize` measures it at.
- [x] 3.7 Attach the existing hover `MouseAdapter` (`StoredCommentCard.kt:145-166`) to the new header
      panel and author label as well as the current targets (`StoredCommentCard.kt:167-171`). It is both
      the reveal trigger and the `mousePressed` swallow — a child without it lets a click retarget to the
      editor and start a text selection.
- [x] 3.8 Leave `getMaximumSize` (`StoredCommentCard.kt:94`) and the closing
      `InlineWidth.capWidth(card, InlineWidth.rightMarginPx(editor))` (`StoredCommentCard.kt:174`)
      untouched — the base-width pin and the right-margin cap are unchanged by this change.
- [x] 3.9 Refresh the class KDoc (`StoredCommentCard.kt:18-38`): the card is now ONE MESSAGE (author
      header + body) inside an accent frame; state that a future discussion thread stacks N such messages
      in the same frame, and that the reserved header — not a floating overlay — is now what keeps the
      rest/hover height identical.

## 4. Resting gutter tie

- [x] 4.1 In `EditorReviewOverlay.addMarker` (`EditorReviewOverlay.kt:176-189`), set
      `highlighter.lineMarkerRenderer = RangeHighlight.gutterBar(<accent from 1.1>)` on the marker the
      overlay already creates. Leave its `null` text attributes (no resting wash) and its absent
      `gutterIconRenderer` as they are.
- [x] 4.2 Amend the D5 comment (`EditorReviewOverlay.kt:184-186`) and the class KDoc
      (`EditorReviewOverlay.kt:23-43`): the marker is now the live position source **and** the resting
      gutter signal; there is still no gutter *icon*, and `StoredCommentGutterIconRenderer` stays unwired
      for the deferred hide-comments change.
- [x] 4.3 Do NOT wire `StoredCommentGutterIconRenderer` and do NOT add a resting code-area wash.
- [x] 4.4 Leave the hover path (`EditorReviewOverlay.kt:257-264`) unchanged in this task; if the running-IDE
      check in 6.2 shows the hover bar swallowing the accent bar, apply the design's fallback (an opt-out
      for `RangeHighlight.create`'s gutter bar) and record the outcome here.

## 5. Tests

- [x] 5.1 In `src/test/kotlin/io/github/zerlok/agentsessionrelay/ui/EditorReviewOverlayTest.kt`, add a
      headless test asserting the stored comment's document-markup highlighter now carries a non-null
      `lineMarkerRenderer`, modelled on the existing markup assertion at `EditorReviewOverlayTest.kt:147`.
      (Went one step further than non-null: the renderer is painted onto an offscreen image and the pixel
      is asserted equal to `RangeHighlight.STORED_COMMENT_ACCENT`, so a bar in the pale draft wash — which
      is invisible as a stripe — fails too. The marker's `null` text attributes are asserted alongside, so
      "no resting wash" stays covered. A second test asserts the bar tracks an in-IDE edit, matching what
      `currentPositions` reports.)
- [x] 5.2 Keep `test a stored comment marker carries no gutter icon` (`EditorReviewOverlayTest.kt:147`)
      passing unchanged — a gutter *bar* is not a gutter *icon*, and that regression guard still holds.
      (Unchanged and green; no existing test was modified anywhere in the suite.)
- [x] 5.3 New `src/test/kotlin/io/github/zerlok/agentsessionrelay/ui/StoredCommentCardTest.kt` covers the
      card's shape off a real card built over the editor fixture: the panel fill is the panel background
      and *not* the editor's text background; the leading accent is carried by the border (asserted as
      `insets.left - insets.right`, plus the painted pixel run being exactly that wide, in the accent
      color, inside the leading inset); the header row exists at rest with the "You" label and hidden
      actions; hover reveals both actions *inside* that header; the card's preferred height and the
      header's height are identical at rest and revealed; header and body are laid out at one content
      width; the hidden actions still get bounds at the trailing edge; and the card still opens at the
      base width under the right-margin cap. Each assertion was mutation-checked (accent color, card
      fill, and a live `header.preferredSize` read in place of the captured constant all make it fail).
- [x] 5.4 Not covered headlessly: the card's hide-on-exit branch. It is gated on
      `card.getMousePosition(true)`, which throws `HeadlessException` under the test runtime, and there is
      no seam short of inventing one. The overlay half of the same gesture is covered by the existing
      `onCardHover(id, false)` test; the card half stays a running-IDE check (6.3).

## 6. Verify

- [x] 6.1 Compile gate and tests per the project build/test env (`./gradlew compileKotlin --offline`,
      then `./gradlew test`). Do not run gradle invocations in parallel. (Both green: `./gradlew test`
      passes with 89 tests, including the 8 new card tests and the 2 new gutter-bar tests.)
- [ ] 6.2 In a running IDE, settle every item in design.md's Open Questions and record the answers by
      editing that section: panel background elevation across light/dark/Darcula/High Contrast; whether
      the accent gutter bar survives hover (apply the `RangeHighlight.create` opt-out fallback if not);
      final accent color pair and bar width; resting card height with the reserved header; `InplaceButton`
      hover rendering on the new background; gutter noise with many comments.
- [ ] 6.3 In a running IDE, re-confirm the comment-box-sizing invariants have not regressed: the card
      still opens at the base width and never exceeds the right-margin cap; the card's height is
      IDENTICAL at rest and on hover (no code reflow when the icons appear); a click on the card's
      padding or header does not start a text selection in the editor beneath; CPU stays idle while a
      card is on screen and while the editor is resized (the layout feedback loop must not return).
