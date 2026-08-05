## Why

Relay's whole value is that an agent receives a **line-anchored** reference it can resolve
directly. But the anchoring safety net described in `ARCHITECTURE.md §5.2` was only ever half
built: `anchorText` and `contextHash` are captured when a comment is added and are persisted,
yet **no code reads them back**, and `CommentStatus.STALE` / `ORPHANED` are never assigned by
any path. At every sync point a `RangeHighlighter`'s current offsets are trusted
unconditionally.

That is exactly wrong for the one workflow Relay exists to serve. When an agent rewrites a
file between the moment a comment is written and the moment it is submitted — the *normal*
case, not an edge case — the marker's offsets survive the document reload untouched, and
`REVIEW.md` gets a confident `@src/app.py#L40-42` pointing at unrelated code. The user sees no
warning and the agent has no way to tell. Mis-pointed feedback is worse than no feedback: the
agent acts on it.

Three narrower defects in the same anchoring path compound this and are fixed here as one
piece of work, because they share the code and the tests.

## What Changes

- **Anchors are validated at the submit/export sync point.** Before the batch is exported, each
  line-anchored comment's recorded `anchorText` is compared against the text its marker
  currently spans. On mismatch the comment is marked `STALE`.
- **A stale comment is still exported, and is flagged.** Its reference carries a visible
  "anchor may have moved" marker plus a comment block telling the agent to verify the lines
  before acting on them. Nothing is silently dropped, and nothing is silently wrong. Submit
  remains a single keypress with no blocking dialog.
- **A clamped position is never written back.** Restoring a comment whose recorded lines fall
  outside the current document no longer overwrites the recorded position with the clamped one
  at the next save/close. The recorded anchor is preserved; the comment is marked `ORPHANED`
  and renders no marker.
- **The comment box reads its live marker on submit.** `CommentDraft` builds its `Subject` from
  the `RangeHighlighter` it already declares as the position source, rather than from the line
  numbers captured when the box opened. This also removes an `IndexOutOfBoundsException`
  reachable when the document shrinks while a box is open.
- **A comment gets exactly one marker per document.** Markers move to document-scoped
  ownership, matching the document-scoped markup model they are written into. Two splits of one
  file no longer paint duplicate gutter bars.

Not breaking: no persisted format changes, no user-facing action is removed, and a batch with
no drift produces a byte-identical `REVIEW.md`.

## Capabilities

### New Capabilities

None. This change arms behavior the existing capabilities already describe.

### Modified Capabilities

- `review-annotation`: adds anchor validation at the sync point and the `STALE`/`ORPHANED`
  transitions; corrects the marker-ownership requirement from per-editor to per-document; the
  comment box's submitted range is defined as its live marker's range.
- `review-batch`: a restored comment's recorded position is authoritative and is not
  overwritten by a display-time clamp; defines when a stored comment carries a non-`ACTIVE`
  status and how it renders.
- `review-export`: defines how a `STALE` comment's reference is rendered so the agent is told
  the anchor is unverified.

## Impact

**Code**

- `domain/Anchoring.kt` — gains the pure anchor-match predicate alongside `contextHash`.
- `domain/ReviewComment.kt` — `STALE` / `ORPHANED` become reachable states.
- `logic/ReviewBatchService.kt` — a command to mark a comment's anchor status.
- `ui/EditorReviewOverlay.kt` — reports current anchor text with current positions; stops
  reporting clamped positions as live; marker ownership moves to the document.
- `ui/EditorReviewOverlayService.kt` — owns the per-document marker holder.
- `ui/CommentDraft.kt` — `submit()` reads the live marker.
- `ui/SubmitReviewAction.kt` — runs validation before export. *(This file is scheduled to be
  replaced by a delivery-layer service in the follow-up `delivery-service-seam` change; the
  validation call moves with it and is written to be portable.)*
- `export/ReviewExporter.kt` — renders the stale flag. Stays pure: it reads `status`, never a
  document.

**Docs**

- `docs/ARCHITECTURE.md` §3.3, §5.2, §5.4 — three `[NOT IMPLEMENTED]` tags and the
  marker-ownership "known deviation" note are removed as this change lands.

**Not in scope**

- Fuzzy re-anchoring (searching for `anchorText` nearby and *moving* the comment). Deliberately
  excluded — it can move a comment to the wrong place, and it needs the `DocumentTracker`-based
  line mapping tracked separately.
- `contextHash` remains captured-but-unread; only `anchorText` is validated. The hash is the
  input to the deferred fuzzy-matching tier.
- The delivery-layer refactor and its preserve-on-failure tests — the `delivery-service-seam`
  change.
