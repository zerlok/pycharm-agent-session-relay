## Context

The MVP treats the inline box (`CommentDraft`) as **transient, author-only**. On submit it hands a
`ReviewComment` to `ReviewBatchService`, closes, and the durable surfaces become the balloon gutter
marker (`StoredCommentGutterIconRenderer`) plus the tool-window snippet. There is no way back to the
body: you can't re-read it in the editor and there is no edit path. `EditorReviewOverlay` already
owns the per-editor projection of the store — one `RangeHighlighter`/gutter marker per stored comment
whose subject points at this file, reconciled **by diff** off `ReviewBatchListener` events. The store
(`ReviewBatchService`) exposes `addComment`, `updatePosition`, `removeComment`, `clear` and dispatches
`commentAdded/Updated/Removed`/`batchCleared`. `updatePosition` already does an in-place `storage.update`
+ `commentUpdated`; there is no equivalent for the body.

This change makes the submitted comment **persist visibly** (an always-expanded read-only card) and
**editable** (re-open the same box seeded, resubmit as an in-place update). The display-model choice
(always-expanded card vs. click-to-expand vs. toggle) was settled with the user: **always-expanded**.

Constraints inherited from `ARCHITECTURE`: the store is the single source of truth and every surface
reconciles from it (§3.1); the per-editor overlay is retained-mode/diff-reconciled (§3.2–3.3);
store commands and event dispatch run on the EDT (§5.3); no Swing/editor imports in the logic layer.

## Goals / Non-Goals

**Goals:**
- Keep a submitted comment's body visible in the editor as an always-expanded read-only inline card
  under its range, in every split showing the file.
- Let the user re-open a comment for editing from the card or the gutter, seeded with its current
  body and range, and resubmit as an **in-place update** (same id) of body + position.
- Add one store command, `updateBody(id, body)`, mirroring `updatePosition`, so an edit is a single
  mutation every surface reconciles from — never delete-and-re-add.
- Reuse the existing box (`CommentDraft`) for editing, so range-adjust, key capture, and focus
  handling come for free.

**Non-Goals:**
- Persistence across IDE restarts (batch stays in-memory — still deferred).
- Threaded replies, multiple comments per line, rich text / markdown rendering in the card.
- A click-to-expand or toggle display model (explicitly rejected in favor of always-expanded).
- Changing export/delivery: the position flush at submit/export time is unchanged.

## Decisions

### D1 — Reuse `CommentDraft` for editing, parameterized by an optional source comment

`CommentDraft.open(...)` gains an optional `editing: ReviewComment?` (or a sibling `openForEdit`).
When present: the body field is seeded with `comment.body`, the range starts at the comment's current
lines, and `submit()` routes to `updateBody(id, ...)` + `updatePosition(id, ...)` instead of
`addComment(...)`. Everything else — the wash, edge-drag resize, key capture, deferred focus — is
identical. `CommentDraftController.openForEdit(editor, comment)` is the entry point; it goes through
the same `close()`-first path, so the single-active-box rule already holds.

*Alternative considered:* a separate read/edit component. Rejected — it would duplicate the box's
focus/key/resize machinery, which was hard-won (`comment-box-key-capture`, `adjustable-comment-range`).

### D2 — The read-only card is a per-comment block inlay owned by `EditorReviewOverlay`

The overlay already reconciles a per-comment marker by diff. It gains a parallel per-comment map of
card inlays: for each stored comment on this file, a block inlay below its range (same
`EditorEmbeddedComponentManager` full-width block placement `CommentDraft` uses), rendering the body
read-only plus **Edit** and **Delete** buttons. Reconcile handles add/update/remove/clear uniformly:
on `commentUpdated`, dispose+rebuild that comment's card (bodies/positions are immutable on an inlay,
just like the highlighter). Card **Edit** → `CommentDraftController.openForEdit`; **Delete** →
`ReviewBatchService.removeComment`.

*Alternative considered:* render the card inside the highlighter's custom renderer. Rejected — a
custom renderer paints pixels, it can't host focusable Swing buttons; a block inlay is the right host
and matches how the authoring box is already placed.

### D3 — Suppress a comment's card while it is being edited (no overlap)

The card and the edit box would otherwise both sit under the same range. The overlay must know which
comment (if any) is currently being edited and skip its card during reconcile. `CommentDraftController`
is the natural owner of "currently-editing id"; the overlay reads it (and the controller notifies on
open/close so the overlay reconciles). On box close (submit or cancel) the editing id clears and the
card reappears via normal reconcile — after a submit it re-renders with the new body.

*Alternative considered:* leave the card visible and open the box below it. Rejected — visually
confusing (two copies of the same comment) and wastes vertical space.

### D4 — `updateBody` mirrors `updatePosition` exactly

`ReviewBatchService.updateBody(id, body)`: `storage.get(id) ?: return` → `copy(body = ...)` →
`storage.update` → `commentUpdated`. No new event type; `commentUpdated` already drives every
surface's reconcile. An edit submit calls `updateBody` and (if the range moved) `updatePosition`;
both publish `commentUpdated`, so the overlay reconciles once or twice harmlessly (idempotent diff).

### D5 — Edit affordance on the gutter too

`StoredCommentGutterIconRenderer.getPopupMenuActions()` gains an **Edit comment** action beside the
existing **Delete comment**, routing to `CommentDraftController.openForEdit`. This gives a second,
discoverable entry point matching the feedback ("can't find the comment box").

## Risks / Trade-offs

- **Vertical clutter with many comments** → Always-expanded is the user's explicit choice; accepted
  for the MVP. A future toggle/collapse (the rejected model) remains an easy follow-on if it bites.
- **Inlay churn on every keystroke?** → No: the card only rebuilds on store `commentUpdated`, which
  fires once per submit, not per keystroke. Editing happens in the box, not the card.
- **Card ↔ edit-box race** (card reappears before box fully closes, or vice-versa) → Single-threaded
  on the EDT and both driven off the controller's editing-id + store events, so ordering is
  deterministic; the box's existing deferred-focus path is unchanged.
- **Two `commentUpdated` events on an edit that moves the range** → Harmless; reconcile is an
  idempotent diff. Could coalesce into one `update(subject, body)` command later if noisy.
- **Card buttons stealing editor focus / mouse** → The card is read-only; like the box panel it must
  swallow its own mouse events so clicks don't retarget to the editor underneath (same `MouseAdapter`
  pattern already in `CommentDraft.buildPanel`).

## Open Questions

- Should the card show any metadata (line range, a "Relay" label) or just the body? Leaning
  body-only for the MVP to stay compact; revisit if users want provenance.
- Delete from the card: inline button vs. reuse only the gutter/tool-window delete? Proposed: include
  it on the card for discoverability (spec'd), but it's the cheapest thing to drop if scope tightens.
