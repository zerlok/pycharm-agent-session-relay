## Why

The shipped annotation surface (`specs/review-annotation`) captures a comment but only reports it
via a notification and a log entry — there is no place to collect several comments, see which lines carry one, or
remove them. This change adds the **ReviewBatch** stage of the relay pipeline
([`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) §3): a project-scoped store of pending
comments, gutter markers and a tool window that visualize them, and delete. It turns the baseline's
one-shot capture into a durable, reviewable set — the input the later `review-export` and
`review-delivery` stages consume.

## What Changes

- **Comment model.** Introduce `ReviewComment` (id, subject, `anchorText?`, `contextHash?`, body,
  status) with a live `RangeMarker`. `subject` is an **open type** — `Line` / `LineRange` / `File`
  / `Files` / `Project` — but the MVP authors only the line/range scope (the others are modeled so
  later scopes are additive, per ARCHITECTURE §3).
- **Batch store.** A `Project`-scoped service holds the pending comments with add / delete / clear
  and a change-listener mechanism. In-memory only (persistence across restarts is deferred).
- **Submit persists to the batch.** MODIFY `review-annotation`: on submit the captured comment is
  added to the store (with its `RangeMarker`, `anchorText`, and `contextHash`) instead of only
  raising a notification.
- **Gutter markers for stored comments** — a persistent marker on each commented line range,
  distinct from the transient hover "+", refreshing on store changes.
- **Tool window** listing pending comments grouped by file (line range + body snippet), with
  navigate-to-line.
- **Delete** a pending comment from either the gutter or the tool window, keeping store, gutter,
  and tool window in sync.
- **Refresh & review** — an action that triggers an async VFS refresh so agent edits written to
  disk (locally or synced from a sandbox) become visible before review. MODIFY `review-annotation`.

## Out of scope (later stages / changes)

- Serializing the batch to `REVIEW.md` (`review-export`) and writing/notifying it (`review-delivery`).
- Whole-file / multi-file / project subjects authoring; in-place comment body editing.
- Persistence across IDE restarts (`PersistentStateComponent`) and fuzzy re-anchoring — the model
  carries `anchorText` + `contextHash` now so both are additive later.

## Capabilities

### New Capabilities
- `review-batch`: Hold pending line-anchored comments in a project-scoped store; display them as
  gutter markers and in a tool window grouped by file with navigate-to-line; delete them.

### Modified Capabilities
- `review-annotation`: submit now persists the comment into the batch (was: notification only); adds
  a "Refresh & review" VFS-refresh action.

## Impact

- **IntelliJ Platform APIs**: `RangeMarker`, `GutterIconRenderer` / `LineMarkerProvider`,
  `ToolWindowFactory`, project-scoped `Service`, `LocalFileSystem` / `VirtualFileManager`
  (async VFS refresh).
- **Code**: new comment model + store service; a gutter renderer keyed on the store; a tool-window
  factory; the `CommentDraft.submit` stub is rerouted to the store.
