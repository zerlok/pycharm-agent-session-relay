## Context

Both inline surfaces are `EditorEmbeddedComponentManager` block inlays created with `fullWidth = true`
and anchored at `document.getLineEndOffset(end)`:

- `CommentDraft.showBox()` — the authoring/edit box (blue wash + `EditorTextField` body + buttons),
  with a `BODY_ROWS = 4` minimum height on the body field.
- `EditorReviewOverlay.addCard()` building `StoredCommentCard` — the read-only card (a `JBTextArea`
  body that already grows with content, plus a permanent `FlowLayout` row of Edit/Delete `JButton`s).

`fullWidth = true` makes both span the viewport. The card's permanent button row is the dominant
vertical cost for a short comment; the box's `BODY_ROWS` floor is the box's.

## Goals / Non-Goals

**Goals:**

- Cap both surfaces at the editor's right-margin column; fall back to full width when the margin is
  non-positive.
- Card actions revealed on hover; resting card is body-height only.
- Authoring box grows from a compact minimum.

**Non-Goals:**

- Any change to anchoring, store, export, or delivery — this is view-only.
- Collapsing/hiding the card body (only the *actions* become hover-revealed; the body stays visible).
- Reworking the edit-box wash / edge-drag resize behavior.

## Decisions

**Compute the cap once, share it.** Add a small helper (e.g. `InlineWidth.rightMarginPx(editor)`)
returning the pixel width of `editor.settings.getRightMargin(project)` columns, or `null` when the
margin is `≤ 0`. Column→pixel uses the plain space width
(`EditorUtil.getPlainSpaceWidth(editor)` / font metrics of the editor's plain font). Both builders
consult this helper so box and card cap identically.

**Apply the cap without changing inlay placement semantics.** Keep `fullWidth = true` and constrain
the *inner* panel: wrap the content in a left-aligned container whose `maximumSize`/`preferredSize`
width is the cap, so the inlay still lays out as a full-width row but the visible box occupies only
the leftmost `cap` pixels. `null` cap ⇒ no constraint ⇒ today's full-width look.

- _Alternative — `fullWidth = false` with an explicit inlay width:_ changes how the embedded
  component manager positions and measures the inlay (inline-offset semantics, wrap behavior). More
  risk for no visual gain over constraining the inner panel. Rejected; if the inner-panel constraint
  proves unreliable in the running IDE, revisit this as the fallback (see Open Questions).

**Card actions as a hover toolbar.** Replace `StoredCommentCard`'s permanent `SOUTH` button row with
a small toolbar shown only while the pointer is over the card. Attach a `MouseListener`
(enter/exit) on the card panel that toggles the toolbar's visibility (and repaints), keeping the two
existing actions and their store-routed callbacks. At rest the card is `BorderLayout.CENTER` body +
padding only. Preserve the existing mouse-swallowing so a click on card chrome does not retarget to
the editor.

- _Alternative — a `⋯` kebab that opens a popup menu:_ closer to GitHub, but heavier (popup wiring)
  and the two flat actions fit a plain hover toolbar. Kebab can come later if actions grow.

**Authoring box compact height.** Lower the body field's minimum from `BODY_ROWS = 4` toward 2 (or 1)
and let its natural `getPreferredSize` growth take over, so an empty box is short and grows as the
user types. The exact floor is a taste call to settle in the running IDE, kept as a single constant.

## Risks / Trade-offs

- **[Hover-revealed actions are less discoverable]** → accepted; this matches GitHub/GitLab. A resting
  card still reads clearly as a comment; hovering reveals the affordances.
- **[Toolbar appearing/disappearing shifts the card's height on hover]** → reserve the toolbar's
  height at rest (empty strut) *or* let it overlay the top-right corner so hovering doesn't reflow
  code. Prefer a fixed top-right overlay so the card's block-inlay height is stable regardless of
  hover; settle in the running IDE.
- **[Right-margin lookup differs per file type / language]** → `getRightMargin(project)` already
  resolves the effective per-file value; the `≤ 0` fallback covers "guide disabled".
- **[Very narrow right margin vs. the buttons' intrinsic width]** → floor the cap at a sane minimum so
  the box never clips its own buttons; clamp in the helper.

## Open Questions

- Does constraining the inner panel under `fullWidth = true` reliably cap the *visible* width across
  themes/zoom, or is `fullWidth = false` needed? Resolve with a quick check in the running IDE during
  task 1; the design's fallback is the `fullWidth = false` path.
- Final resting height of the authoring box (1- vs 2-row floor) — settle visually.
