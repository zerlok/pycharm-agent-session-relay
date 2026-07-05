## Context

An interaction refinement on the shipped `review-annotation` surface: make the commented line range
adjustable *after* the inline box opens, GitHub/GitLab-review style, by dragging the highlighted
block's edges. Builds on trusted editor APIs only (ARCHITECTURE §8) — notably the editor-factory
mouse listeners `RelayHoverService` already registers, so no new platform surface is taken on.

The whole design turns on one problem: the box is a block inlay pinned below the range's last line,
so a *changing* range forces a decision about where the box goes. The decisions below resolve it.

## Decisions

### D1 — Hide the box during an edge-drag; rebuild it once on release
When the user presses a range edge, the comment box is hidden for the duration of the drag and
rebuilt when the mouse is released, positioned under the range's (new) bottom line. *Why:*

- **Unobstructed code view** — the reviewer sees the code they are sizing the range over, not a box
  covering it.
- **No per-step inlay churn** — the box-follows-the-range-end look (GitHub-faithful) would otherwise
  require tearing down and re-adding the block inlay on every drag step, and each re-add re-triggers
  the focus/keyboard races the box already fights (deferred focus via `IdeFocusManager`, key
  dispatch bound on the text area). Hiding collapses that to **one** teardown on press and **one**
  rebuild on release.

*Mechanics:* "hide" = dispose the inlay (a hidden-but-present inlay would still reserve vertical
space); before disposing, capture the current body text. On release, re-add the inlay under the new
bottom line, restore the captured text, and re-request focus via the existing deferred-focus path.

*Trade-off:* the box briefly disappears mid-gesture. Accepted — it is the point (clean code view),
and the gesture is short.

### D2 — Claim the edge-press before the editor turns it into a text selection
A mouse press-drag that starts in the editing area normally makes the editor select text. When a
press starts on a comment-range edge (within the grab zone), the handler SHALL `consume()` the
mouse event so the editor does not also drag-select. *Why:* without this the drag would both resize
the range *and* smear a text selection across the file. This is the main build risk — the same
"editor steals the gesture" theme as the existing focus/key-dispatch handling — so it is called out
as a first-class decision, not an implementation afterthought.

### D3 — Two independent edges; a minimum one-line range
The top and bottom borders are independent resize grips. Dragging the bottom edge moves `end`;
dragging the top edge moves `start`. Each can grow or shrink its side. The edges cannot cross: the
range is clamped to a minimum of one line and to the document bounds. *Why:* two independent handles
match the GitHub/GitLab mental model and make both grow and shrink obvious without a separate
"anchor" concept.

### D4 — Grip affordance: brightened edge + resize cursor + small grab zone (no drawn handle squares)
The top and bottom edges are signalled by a slightly brighter/thicker line at the block's borders,
an N-S resize cursor when the pointer is within a few pixels of an edge, and a grab zone of that
same few-pixel band for hit-testing. *Why:* cheapest affordance that reads as draggable and reuses
the wash already drawn; explicit GitHub-style handle squares are a later polish, not needed to prove
the interaction. *Open:* exact grab-zone thickness and edge styling — tune during implementation.

### D5 — Initial range unchanged; drag refines it
Opening the box still resolves the initial range as today: the editor's selected lines when a
selection exists, otherwise the single clicked line (`CommentDraftController.rangeFor`). The drag is
a refinement on top. *Why:* preserves the existing fast path (select-then-comment) and keeps this
change additive. *Note:* the existing behavior where a selection sets the range even when **+** is
clicked outside it is kept — the user can now drag to correct it, so it is no longer a trap worth a
separate fix here.

### D6 — Submit stays the baseline report-only stub
Confirm still logs + notifies the (now adjustable) line range. *Why:* persistence is the separate
`comment-batch` change; keeping submit untouched here means the two changes stay orthogonal and this
one is a pure interaction change.

## Risks / Trade-offs

- **Gesture ownership** (D2) — if the `consume()` is missed or mis-scoped, the drag leaks into a
  text selection or the editor's own drag. Mitigate by scoping the "edge-drag" mode strictly to
  presses that begin inside a current range's edge grab-zone, and exiting it on release.
- **Release outside the editor** — the mouse can be released off the editor component; the release
  handler must still fire and rebuild the box. Handle via the editor mouse listener lifecycle;
  fall back to rebuilding on the next editor interaction if a release is ever missed.
- **Focus after rebuild** — re-adding the inlay must reuse the deferred-focus path so the rebuilt
  box takes the keyboard (not the editor). This is the one place D1's "rebuild once" still touches
  the focus race — but once per gesture, not per step.
- **Text preservation** — capturing/restoring the body across hide/rebuild must be exact (including
  an empty body), so an in-progress comment is never lost by adjusting the range.

## Migration

None — additive interaction on an existing surface. No stored data, no config, no API changes.
