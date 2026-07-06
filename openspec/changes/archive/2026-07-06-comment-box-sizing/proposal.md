## Why

The inline comment box and the read-only stored-comment card are both rendered as `fullWidth = true`
block inlays, so they stretch edge-to-edge — unreadable on a wide monitor — and they reserve more
vertical space than their content needs: cards carry a permanent Edit/Delete button row, and the
authoring box has a fixed 4-row minimum. The effect is a bulky box that hides code. GitHub and GitLab
solve this the same way: comment surfaces have a max reading width and keep chrome compact, revealing
actions on hover. We want to match that.

## What Changes

- Cap the inline comment box **and** the stored-comment card width at the editor's configured right
  margin (the vertical guide, e.g. 120 columns). When a file has no right margin set (guide disabled
  / `≤ 0`), fall back to today's full-width behavior.
- Reveal the card's **Edit** and **Delete** actions on hover (a hover toolbar) instead of a permanent
  button row, so a resting card is only as tall as its body.
- Let the authoring box grow from a compact minimum height instead of a fixed 4-row floor, so a short
  comment leaves more code visible.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `review-annotation`: the authoring box is width-capped at the right margin (fallback full width) and
  grows from a compact minimum rather than a fixed 4-row floor.
- `review-batch`: the stored-comment card is width-capped at the right margin (fallback full width),
  fits its body height, and reveals Edit/Delete on hover instead of an always-visible button row.

## Impact

- `CommentDraft`: box panel width cap; `BODY_ROWS` floor lowered / grow-from-compact.
- `StoredCommentCard`: hover-revealed action toolbar replacing the permanent button row; body-height
  sizing.
- `EditorReviewOverlay.addCard` / `CommentDraft.showBox`: apply the width cap to the embedded panel.
- A small shared helper to compute the right-margin pixel width from
  `editor.settings.getRightMargin(project)` and the plain char width.
- View-only change: no domain, store, export, or delivery changes.
