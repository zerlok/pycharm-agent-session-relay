## 1. Width cap helper

- [x] 1.1 Add a small helper (e.g. `InlineWidth.rightMarginPx(editor)`) returning the pixel width of
      `editor.settings.getRightMargin(project)` columns via the plain space width, or `null` when the
      right margin is `≤ 0`; clamp to a sane minimum so a box never clips its own buttons.
- [ ] 1.2 Verify in the running IDE that constraining the inner panel's max width under
      `fullWidth = true` visibly caps the box; if not, switch that surface to `fullWidth = false` with
      an explicit width per design's fallback.

## 2. Apply the cap to both surfaces

- [x] 2.1 In `CommentDraft.showBox()` / `buildPanel`, wrap the box content in a left-aligned container
      constrained to the cap (no constraint when the helper returns `null`).
- [x] 2.2 In `StoredCommentCard.build` (called from `EditorReviewOverlay.addCard`), apply the same cap
      to the card panel.

## 3. Card hover toolbar

- [x] 3.1 Replace `StoredCommentCard`'s permanent `SOUTH` Edit/Delete row with a compact toolbar that
      is shown only while the pointer is over the card (mouse enter/exit toggles visibility), keeping
      the existing store-routed Edit/Delete callbacks and the mouse-swallowing behavior.
- [x] 3.2 Keep the card's block-inlay height stable between rest and hover (fixed top-right overlay or
      reserved strut) so revealing the toolbar does not reflow the code below.

## 4. Compact authoring box

- [x] 4.1 Lower `CommentDraft.BODY_ROWS` toward a compact floor (1–2) so an empty/short box is short
      and grows with the typed body; settle the exact floor visually in the running IDE.

## 5. Verify

- [ ] 5.1 Manually verify in the running IDE: box and card cap at the right margin on a wide editor;
      full-width fallback when the guide is disabled; a 1-line card shows no button row at rest and
      reveals Edit/Delete on hover; a short authoring box leaves more code visible.
- [x] 5.2 Build offline and run the test suite per the project build/test env; update any
      `StoredCommentCard`/`CommentDraft` test that asserts the old always-visible button row.
