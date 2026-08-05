## Context

`ARCHITECTURE.md §5.2` describes anchor drift as "defense in depth": in-IDE edits ride the
`RangeMarker`, out-of-IDE edits re-anchor via `anchorText` + `contextHash`, and anything still
ambiguous is marked stale and surfaced. Only the first tier was ever built. `anchorText` and
`contextHash` are captured at `ui/CommentDraft.kt:221-222`, threaded through the service and the DTO,
and persisted — and no code reads them back. `CommentStatus.STALE` / `ORPHANED` are declared at
`domain/ReviewComment.kt:18,21` and never assigned.

Current shape of the code this change touches:

- `ui/EditorReviewOverlay.kt:108-120` — `currentPositions()` reads each marker's offsets, skips
  invalid markers, and reports the rest as fact.
- `ui/EditorReviewOverlay.kt:173-177` — `addMarker` clamps a restored out-of-range comment with
  `coerceIn(0, lastLine)`, so the clamped line becomes what `currentPositions()` reports.
- `ui/EditorReviewOverlayService.kt:126-130` — `flush()` writes those positions into the store at
  save and editor-close; `ui/SubmitReviewAction.kt:44-47` does the same before export.
- `ui/EditorReviewOverlay.kt:56-59` — markers go into `DocumentMarkupModel.forDocument(...)`
  (document-scoped) but the overlay that writes them is created per editor
  (`EditorReviewOverlayService.kt:100-109`), so splits duplicate them.
- `ui/CommentDraft.kt:208-241` — `submit()` builds its `Subject` from the `start`/`end` ints captured
  when the box opened, and `rangeStartOffset()` calls `document.getLineStartOffset(start)` unguarded.

Constraints carried in from the project rules: the store holds only inert data (§3.2); `ui/` may
depend only on `ReviewBatchService` + the listener topic, never on storage; the exporter is a pure
function of the batch; store mutations and markup work happen on the EDT (§5.3); domain line numbers
are 0-based and user-facing/export forms are 1-based.

## Goals / Non-Goals

**Goals:**

- No `REVIEW.md` reference is ever presented as verified when the text under it has changed.
- A comment's recorded position is only ever changed by a genuine live marker or by the user — never
  by a display-time fallback.
- One position marker per comment per document, regardless of how many editors show the file.
- The comment box stores the range it is actually sitting on at the moment of submit.
- Every new behavior is reachable from a headless unit test.

**Non-Goals:**

- **Fuzzy re-anchoring.** Searching for `anchorText` elsewhere in the file and *moving* the comment is
  explicitly out. It can silently relocate a comment to a wrong-but-plausible match, which is the
  failure mode this change exists to eliminate. It also wants the `DocumentTracker` diff model, which
  is a separate change.
- **`contextHash` validation.** It stays captured-but-unread; it is the input to the deferred fuzzy
  tier, and validating it as well would only add false positives (it covers surrounding lines, which
  legitimately change).
- **Moving the delivery pipeline out of `ui/SubmitReviewAction`.** That is the follow-up
  `delivery-service-seam` change; this change calls validation from where the flush already happens
  and keeps that call trivially portable.
- **Blocking or reordering submit.** Submit stays one keypress with no dialog.

## Decisions

### D1. Anchor comparison is a pure domain predicate, normalized for whitespace only

`domain/Anchoring` gains `fun matches(recorded: String?, current: String): Boolean` beside the
existing `contextHash`. It returns `true` when `recorded` is null (nothing to verify — see D6), and
otherwise compares after normalizing line endings (`\r\n` → `\n`) and stripping trailing whitespace
from each line.

*Why normalize at all:* an editor's strip-trailing-whitespace-on-save, or a file arriving with CRLF
after a sync, changes bytes without changing meaning. Flagging those would train the user to ignore
the flag, which destroys the feature.

*Why nothing more:* any normalization beyond whitespace (case, indentation, blank lines) starts
matching code that genuinely changed. Whitespace is the one class of difference that is never
semantic.

*Alternative rejected:* comparing `contextHash` instead. The hash covers ±N surrounding lines, so an
unrelated edit two lines away would mark the comment stale. Anchor text covers exactly the lines the
user pointed at, which is exactly the claim the export makes.

### D2. Markers move to a per-document owner; overlays keep cards

Introduce `ui/DocumentReviewMarkers` — one instance per `Document` that has at least one qualifying
open editor, owning the `RangeHighlighter` map, `markerSubjects`, and the marker reconcile. It is
created and disposed by `EditorReviewOverlayService`, reference-counted against the number of live
overlays on that document; the last editor closing disposes it (after the close flush).

`EditorReviewOverlay` keeps what is genuinely per-editor: the inline cards (an `Inlay` belongs to its
editor), the card-hover range highlight, and the editing suppression.

*Why:* the markers already live in the **document**-scoped `DocumentMarkupModel`. Owning them
per-editor is what creates the duplication, and it also makes "the comment's live position" an
editor-relative idea when it is a document property. This makes ownership match scope, which
additionally means position and anchor queries have exactly one answer per document instead of N
answers that must be reconciled.

*Alternative rejected:* keep per-editor overlays and elect one "primary" overlay per document to own
markers. Less code today, but the primary has to be re-elected when it closes, and every query still
has to know which overlay is authoritative — the same complexity, hidden.

### D3. One live-state query, not two

`DocumentReviewMarkers` exposes a single `liveState(): Map<CommentId, LiveAnchor>` where
`LiveAnchor` carries both the current `Subject` and the current anchor text read from the same
marker in the same pass.

*Why one call:* position and anchor text must describe the same instant. Two separate queries could
straddle a document change and validate line 47's text against line 45's range — producing exactly
the wrong-but-confident result this change removes.

The existing `currentPositions()` becomes a thin projection of `liveState()` so the save and
editor-close sync points keep their current, narrower contract (they flush positions, they do not
validate — see D5).

### D4. Out-of-range comments are orphaned, not clamped

`addMarker` stops clamping. When a comment's recorded range does not fit the current document, no
marker and no card are created, and the comment is marked `ORPHANED`. Because no marker exists, it
has no entry in `liveState()`, so nothing can flush a substitute position over its recorded one —
the defect is closed structurally rather than by a guard that a later edit could drop.

**Reentrancy.** Marking status during reconcile means a view mutating the store, which republishes
and re-enters reconcile. This terminates because the status command is idempotent — setting a status
a comment already has is a no-op that publishes nothing — so the second pass finds nothing to change.
This is the same idempotence contract `updatePosition` already relies on and must be tested
explicitly, not assumed.

A comment returning into range (the file grew back) transitions back to `ACTIVE` on the next
reconcile, and its marker and card reappear.

### D5. Validation runs at the export sync point only

The save and editor-close sync points keep flushing positions and do **not** validate. Only the
export path runs `flush → validate → export`.

*Why not validate on save:* saving is not a claim about anything; marking a comment stale on every
save would fire while the user is still editing around it, and would need an un-stale path on the
next save. Export is the moment Relay asserts a line number to a third party, so it is the moment the
assertion has to be checked.

The validation call is a single statement — `overlayService.validateAnchors()` — placed beside the
existing flush loop in `SubmitReviewAction`, so the `delivery-service-seam` change moves both lines
together.

### D6. Unverifiable is not stale

A comment whose file is not open has no marker, so it has no live anchor text and is skipped: it
keeps whatever status it has. Same for a comment with a null `anchorText` (persisted from before this
change, or a non-line subject).

*Why:* "we could not check" and "we checked and it moved" are different claims. Reporting the first
as the second means every batch spanning a closed file arrives full of warnings, and the flag becomes
noise. This is the trade-off called out in Risks.

### D7. Export flag format

A stale comment's block becomes:

```
@src/app.py#L40-42  ⚠️ unverified anchor
The code at these lines changed after this comment was written — locate the intended code before acting on the line numbers.
> the retry loop needs a backoff
```

- The `@path#L…` token is byte-identical to the unflagged form and stays first on the line, so
  anything matching the reference syntax (Claude Code itself included) still resolves it.
- The note is an unquoted line; bodies are always `> `-quoted, so the note can never be confused with
  user text, and user text can never forge a note.
- An `ACTIVE` comment renders exactly as today — a no-drift batch produces a byte-identical file,
  which is what makes the existing exporter tests a regression net rather than churn.

*Alternative rejected:* an HTML comment (`<!-- … -->`). Invisible when the user previews `REVIEW.md`,
and the point is that a human can see which references are suspect.

### D8. `CommentDraft` submits its live range

`submit()` derives lines from `highlighter.startOffset` / `endOffset` via `document.getLineNumber`,
and `rangeStartOffset()` / `rangeEndOffset()` / `contextWindow()` derive from the same source. When
the highlighter is invalid, fall back to `start`/`end` clamped into the current document — that path
is what removes the `IndexOutOfBoundsException`, and it is the only place a clamp remains (a clamp
here affects a comment being created now, not a recorded position being overwritten).

This lands ahead of the eventual `CommentDraft` decomposition and gives that split a single obvious
seam (`DraftRange`) to lift out later.

## Risks / Trade-offs

- **A closed file's comments are never verified** → Accepted, per D6. The common loop (review the
  file, submit) has the file open. Mitigation if it bites: verify against the file on disk off-EDT
  during the background write, which the `delivery-service-seam` change makes natural.
- **Whitespace normalization hides a real change** → A change that is *only* trailing whitespace
  inside the commented lines will not flag. That edit cannot change what the code means, so the line
  reference stays correct, which is what the flag is about.
- **Orphaned comments vanish from the editor** → A comment the user can no longer see is a comment
  they cannot fix. It stays in the tool window, which is the safety valve; see Open Questions on
  showing status there.
- **`DocumentReviewMarkers` is a real lifecycle change** → Ref-counting across split open/close,
  project close, and dynamic plugin unload is where this can leak or double-dispose. Mitigation:
  drive it entirely from the existing `editorCreated`/`editorReleased` path (no new platform
  listener), parent to the project service exactly as overlays are today, and cover open-split /
  close-one / close-both explicitly — a case the current suite cannot express at all.
- **Existing tests assert the duplicated-marker world** → `EditorReviewOverlayTest.kt:169,198` use
  `allHighlighters.single { … }`, which passes only because no test opens a split. They must be
  re-pointed at the document owner, and the split case added. Test churn here is the point, not a
  cost.
- **A batch could arrive at the agent entirely flagged** → If an agent rewrote everything, every
  comment flags and the export is noisy. That is the honest report of what happened; the alternative
  is a quiet wrong one.

## Migration Plan

No migration. No persisted-format change: `PersistedComment.status` already round-trips
(`storage/PersistedComment.kt:36,54,97`) and unknown values already fall back to `ACTIVE`. Batches
written by the current build load unchanged and start life `ACTIVE`, which is correct — they have
never been verified.

Rollback is reverting the change; nothing on disk needs undoing.

## Open Questions

1. **Should the tool window show a comment's status?** Not specified by this change, and an orphaned
   comment is currently indistinguishable there from an active one. *Recommendation:* add a minimal
   suffix or icon on non-`ACTIVE` rows — it is small, and without it "orphaned but still listed" is
   a promise the UI does not keep. Flagged rather than assumed because it widens `review-batch`'s
   tool-window requirement.
2. **Should a stale comment be visually marked in the editor** (a muted gutter bar, say) at the
   moment it is detected? Deferred: detection runs at export, by which point the batch is about to be
   cleared, so there is nothing to look at. It becomes worth doing only if validation later moves to
   an earlier point.
3. **Does `⚠️` survive the round trip** into whatever terminal or agent reads `REVIEW.md`? The file is
   UTF-8 and the agent reads it as text, so this should be safe, but it is unverified on a real agent
   in this environment. An ASCII fallback (`[!]`) is a one-line change if it misbehaves.
