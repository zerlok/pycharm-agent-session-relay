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

## 7. Review revision (PR #9, 2026-08-01) — drop the card bar, restyle the box

Answers the three review notes; see design.md "Decisions — review revision" R1-R5. Tasks 1.1, 2.3, 2.4
and 5.3's accent assertion are **superseded** by 7.1-7.3 — they stay checked as the record of what the
first round shipped, and are not re-opened.

- [x] 7.1 New `src/main/kotlin/io/github/zerlok/agentsessionrelay/ui/RelayStyle.kt` (R4): an internal
      object holding every Relay color — `ACCENT` (the line/bar blue), `ACCENT_FILL` + `ACCENT_FILL_TEXT`
      (R5: the filled-button variant and its label color, a different luminance for a reason recorded in
      the KDoc), `RANGE_WASH`, `EDGE_IDLE`, and a `surface()` for the card/box fill. Colors and the fill
      only — no components, no borders, no layout.
- [x] 7.2 Retire the duplicate constants into `RelayStyle`, leaving no second declaration:
      `RangeHighlight.STORED_COMMENT_ACCENT` (`RangeHighlight.kt:47`) and `CommentDraft`'s private
      `EDGE_ACTIVE` (`CommentDraft.kt:429`) collapse into `RelayStyle.ACCENT`;
      `CommentDraft.RANGE_BACKGROUND` (`CommentDraft.kt:423`) becomes `RelayStyle.RANGE_WASH`, so
      `EditorReviewOverlay`'s hover call (`EditorReviewOverlay.kt:269`) stops reaching into `CommentDraft`
      for a color. Update the KDoc in `RangeHighlight.kt:13-20` and `:37-46` that explains the old split.
- [x] 7.3 In `StoredCommentCard.kt` (R1), remove the left accent line from the card's border compound
      (`StoredCommentCard.kt:196-204`) and delete `ACCENT_WIDTH_DP` (`:53`). The border becomes
      `customLine(JBColor.border(), 1)` + `empty(8, 12)`. Leave `contentWidth`, `getPreferredSize`,
      `doLayout`, the header row and the width pin exactly as they are — the insets simply become
      symmetric. Update the class KDoc (`:20-48`), which currently describes the accent frame.
- [x] 7.4 In `CommentDraft.buildPanel` (`CommentDraft.kt:497-502`, R2), replace
      `background = editor.colorsScheme.defaultBackground` with `RelayStyle.surface()` — the same value
      the card uses, from the same source. Keep the existing 1px outline + `empty(8, 12)` padding, and add
      no accent edge. Do NOT touch the panel's `getPreferredSize` width pin or `InlineWidth.capWidth`.
- [x] 7.5 Frame the body field in the accent (R2): `bodyField`'s border (`CommentDraft.kt:124-127`)
      keeps its `empty(3, 5)` inner padding but its `customLine(JBColor.border(), 1)` becomes
      `customLine(RelayStyle.ACCENT, 1)`.
- [x] 7.6 Rename the primary action (R3): `JButton(if (editing != null) "Save" else "Comment")`
      (`CommentDraft.kt:349`) becomes an unconditional `JButton("Comment")`. Update the comment above it
      (`:346-348`), which explains the two-label rule. Nothing else about `doSubmit`/`onClose` changes.
- [x] 7.7 Style the two buttons (R3): set `isOpaque = false` on **both** so neither paints Swing's
      default `Button.background` rectangle behind `DarculaButtonUI`'s rounded shape; on the primary
      only, `putClientProperty("JButton.backgroundColor", RelayStyle.ACCENT_FILL)` and
      `putClientProperty("JButton.textColor", RelayStyle.ACCENT_FILL_TEXT)` — the two properties
      `DarculaButtonUI.getBackground`/`getButtonTextColor` read first (verified against the disassembled
      2024.2.5 platform). Do not rely on `isDefaultButton()`: an inlay has no root pane.
- [x] 7.8 Update `StoredCommentCardTest.kt` (R1): replace
      `test the card carries a leading accent line in the shared stored-comment accent`
      (`StoredCommentCardTest.kt:65-79`) with the inverse — leading and trailing insets are **equal**, and
      no pixel of `RelayStyle.ACCENT` is painted anywhere in the card's border. Keep every other test as
      is; the fill, header, height and width assertions are unaffected.
- [x] 7.9 Update `EditorReviewOverlayTest.kt:181` to assert against `RelayStyle.ACCENT` (same value, new
      home) so the gutter-bar color test keeps failing on a pale-wash regression.
- [x] 7.10 New `src/test/kotlin/io/github/zerlok/agentsessionrelay/ui/CommentDraftPresentationTest.kt`
      covering R2/R3 off a real box built over the editor fixture: the box panel's fill equals the card's
      and is not `editor.colorsScheme.defaultBackground`; its border insets are symmetric (no accent
      edge); the body field's border paints `RelayStyle.ACCENT`; the primary button reads "Comment" in
      BOTH the new and the `openForEdit` case; both buttons are non-opaque; and the primary carries the
      two client properties with the fill/label colors. Mutation-check each assertion.
- [x] 7.11 Compile + test gate per the project env (`./gradlew compileKotlin --offline`, then
      `./gradlew test`), not run in parallel. (Both green: 95 tests, 0 failures — 89 before, +6 for the
      box's presentation, with the card's accent test replaced rather than added to. Every new assertion
      was mutation-checked and fails on exactly its own mutation: reverting the box fill to
      `editor.colorsScheme.defaultBackground`, the field frame to `JBColor.border()`, the label to the
      two-mode `if (editing != null) "Save"`, `isOpaque = false`, and the two client properties each fail
      one test and only that one; re-adding the card's accent line fails both no-accent tests — the
      card's own and the box-matches-the-card frame comparison.)
- [ ] 7.12 Running-IDE checks added by this revision (fold into 6.2): the accent fill's label legibility
      in light/Darcula/High Contrast; whether Cancel still reads as a button once non-opaque; and whether
      card→box across Edit reads as one object (same fill, frame, left edge, no width/padding jump).

## 8. Visual round 2 (PR #9 screenshot, 2026-08-01)

Answers the three spots marked on the screenshot plus one defect found while measuring it; see design.md
"Decisions — visual round 2" R6-R8. Each was measured in pixels off the review screenshot and
traced to a mechanism in the disassembled platform before any code was touched.

- [x] 8.1 (R6) In `CommentDraft`'s `bodyField` (`CommentDraft.kt:117-130`), add `isOpaque = true` and
      `background = editor.colorsScheme.defaultBackground`. `EditorTextField` extends `NonOpaquePanel`,
      so the `empty(3, 5)` padding inside the accent line was painting nothing and the box's surface
      showed through as a 5px band between the frame and the text. Keep the border composition as is —
      the frame must stay at the component's edge so it spans the box's full content width.
- [x] 8.2 (R7) Add `putClientProperty("JButton.borderColor", RelayStyle.ACCENT_FILL)` to `primaryButton`
      (`CommentDraft.kt`), beside the existing fill/text properties. `DarculaButtonPainter.getBorderPaint`
      reads that property as a `Color` and returns it for an enabled button ahead of its
      default/plain-button branches, so the gray ring around the accent fill becomes the fill.
- [x] 8.3 (R8) In `buildPanel`, change the action row's `FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)`
      to an `hgap` of 0 and put the 8dp gap *between* the two buttons instead (a horizontal strut).
      `FlowLayout` reserves its `hgap` at both ends of the row, which is what pushed the row 8px left of
      the field's trailing edge. Do not try to compensate for the remaining ~4px: that is the platform's
      own focus-ring inset inside the button's bounds.
- [x] 8.4 Extend `CommentDraftPresentationTest`: the body field is opaque and its background equals the
      editor's default background (so no box fill can show inside the frame); the primary action carries
      the border-color property with the fill color; and the action row's trailing edge equals the body
      field's trailing edge. Mutation-check each.
- [x] 8.5 Compile + test gate (`./gradlew compileKotlin --offline`, then `./gradlew test`), not in parallel.
      (Green: 98 tests, 0 failures — 95 before, +3 for R6/R7/R8. Each mutation-checked and failing on
      exactly its own mutation: dropping `isOpaque`, dropping the border property, and restoring the
      row's non-zero `hgap` each fail one test and only that one.)
- [ ] 8.6 Running-IDE re-check of the same screenshot: no band inside the field's frame, no gray ring on
      the primary action, and the action row flush with the field — in both a light and a dark theme.
