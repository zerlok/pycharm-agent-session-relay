## Why

The shipped annotation surface (`specs/review-annotation`) already anchors a comment to a line
range — but the only way to *choose* that range is to select the lines in the editor *before*
clicking the **+**. Once the inline box is open, the range is fixed. Reviewers on GitHub/GitLab (and
JetBrains' own PR review) expect to open a comment on one line and then **tune the range by dragging
its edges** while they read the code. This change adds that: the highlighted block gets draggable
top and bottom edges, so a reviewer can grow or shrink the commented range after the box is open,
without re-selecting text.

The key interaction insight — and the reason this is cheap and safe — is that the comment box is
**hidden during an edge-drag and rebuilt once on release**. That gives the reviewer an unobstructed
view of the code while sizing the range, and it avoids re-placing the block inlay on every drag step
(which would reignite the focus/keyboard races the box already handles carefully).

## What Changes

- **Draggable range edges.** The top and bottom borders of the highlighted comment block become
  resize grips (a resize cursor + a small grab zone signal they are draggable). Dragging the bottom
  edge down/up grows/shrinks the range from the bottom; dragging the top edge up/down grows/shrinks
  it from the top. The two edges cannot cross — the range stays at least one line.
- **Hide the box during a drag, rebuild it on release.** Pressing an edge hides the comment box for
  an unobstructed code view; the wash resizes live as the mouse moves; on release the box reappears
  **under the range's bottom line**, with any already-typed body preserved and focus returned.
- **Unchanged initial range.** Opening the box still uses the editor's selected lines when a
  selection exists, otherwise the single clicked line (today's behavior). The drag is a refinement
  on top of that starting range.
- **Unchanged submit.** On confirm the comment is still surfaced with its (now adjustable) line
  range via the confirmation notification and log entry — the baseline stub. Routing submit to a
  store is the separate `comment-batch` change and is untouched here.

## Out of scope

- **Keyboard range extension** (Shift+↑/↓) — deferred.
- **Persisting the comment** into a batch/store, gutter markers for stored comments, export, and
  delivery — those are the `comment-batch` / `review-export` / `review-delivery` changes; submit
  here remains the baseline report-only stub.
- **Non-line subjects** (whole-file, multi-file, project) — the range editing applies only to the
  line/range scope.

## Capabilities

### Modified Capabilities
- `review-annotation`: the comment range is now adjustable after the box opens, by dragging the top
  and bottom edges of the highlighted block; the box hides during the drag and reappears under the
  range's bottom line on release.

## Impact

- **IntelliJ Platform APIs**: the editor-factory mouse listeners already registered by
  `RelayHoverService` (`EditorMouseListener` / `EditorMouseMotionListener` — `mousePressed`,
  `mouseDragged`, `mouseReleased`, and `EditorMouseEvent.consume()`); `RangeHighlighter` resizing;
  `EditorEmbeddedComponentManager` inlay teardown/rebuild; editor XY↔line mapping and cursor.
- **Code**: `CommentDraft` gains a mutable range + edge-drag handling and a hide/rebuild path for its
  inlay (preserving draft text); the range-resolution helper in `CommentDraftController` is reused
  for the initial range. No new plugin.xml extensions.
- **Depends on**: the shipped `review-annotation` baseline only. Parallel to `comment-batch` — the
  two touch different requirements of the capability and do not collide.
