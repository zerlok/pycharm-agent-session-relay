## Context

Two defects found in the 2026-08-06 maintainer QA, both against code that shipped on the `refactor`
branch, both rooted in the plugin doing by hand what the platform already owns.

**Delivery.** `ReviewDeliveryService.submit` runs three stages: EDT position flush → background
verify-and-write → EDT status-apply and clear-or-preserve. Stage 2 writes with `Files.writeString`
and then calls `LocalFileSystem.refreshAndFindFileByNioFile`. When `REVIEW.md` is open, the editor
renders `FileDocumentManager`'s in-memory `Document`, which that write never touches. Stage 3 then
clears the batch on `Outcome.Written` — an outcome derived from the disk write not throwing. The
comments are destroyed while the user is looking at the previous export.

**Width.** `InlineWidth` + `InlineWidthWatcher` (263 lines, 213 lines of tests) derive
`min(visibleArea.width, readingMeasure, rightMargin)` from the editor and push re-measures on
`VisibleAreaListener` and component events. The platform ships this: `ComponentInlaysContainer`
already listens to the same events, and `CodeReviewComponentInlayRenderer` already composes
`FIT_VIEWPORT_WIDTH` with an inner reading-measure cap built from the same two constants
`InlineWidth` copied. Two hand-derived width bugs have now been fixed in this area.

Facts below were read from the 2024.2.5 target's bytecode (`lib/app-client.jar`,
`lib/modules/intellij.platform.collaborationTools.jar`), not from documentation.

## Goals / Non-Goals

**Goals:**

- The batch is destroyed only on evidence the user can see the export.
- One writer for `REVIEW.md` while it is open, named in the design rather than emergent.
- Inline surface width is the platform's problem, and the plugin's width code goes away with it.

**Non-Goals:**

- Making the inspections widget itself move, hide, or reflow. The surfaces stay clear of it (D7);
  the widget is left alone.
- Re-anchoring after an out-of-IDE rewrite; tool-window status indication. Both stay open.
- Changing what `REVIEW.md` contains, or when the plugin decides there is nothing to submit.

## Decisions

### D1 — Write `REVIEW.md` through its `Document`, not through the filesystem

The invariant is that clearing the batch requires the export to be **visible**. Two ways to get
there:

- **Reconcile after the fact** — keep the nio write and call `FileDocumentManager.reloadFiles(vFile)`
  in stage 3. One line, and the `review-delivery` off-EDT requirement survives untouched. But the
  success signal and the user-observable stay two different things joined by a step that must keep
  working, and the gap between the stage-2 write and the stage-3 reload stays open — IntelliJ saves
  documents on frame deactivation, so an alt-tab inside that window writes the stale document back
  over the export.
- **Collapse the gap** — write the `Document`. The object the user is looking at is the object
  written. There is no reconciliation step to skip, no window to lose, and the user gets undo on a
  generated file for free.

**Chosen: collapse the gap.** The reconciliation approach makes a destructive, un-undoable step
depend on a second mechanism holding; this one makes the two events the same event. The cost is D2.

Mechanics: resolve the project base directory's `VirtualFile`, find or create the `REVIEW.md` child,
take its `Document` from `FileDocumentManager`, and `setText` + `saveDocument` inside a
`WriteCommandAction`. The command wrapper is not decoration — a bare `setText` on a document with an
open editor is what the platform's undo and PSI-commit machinery expects to be commanded.

### D2 — Re-derive "the write runs off the EDT" rather than work around it

The `review-delivery` spec requires the write off the EDT. That requirement was written against a
raw nio write plus a **synchronous VFS refresh**, both of which are genuinely illegal there and would
freeze the IDE. A `Document` write is not that operation: every VFS and Document mutation in the
platform is EDT-plus-write-action by contract, and `REVIEW.md` is a few KB of text the plugin just
finished building in memory.

What the requirement was protecting — submit does not freeze the IDE — is kept and re-pointed at the
work that can actually block: reading each commented file's content to verify anchors, which stays
in stage 2 off the EDT under a read action. The pipeline still hops exactly once; the write moves
from the far side of the hop to the near side.

Rejected: keeping the write off the EDT by driving the document write from the background thread via
`WriteAction.runAndWait`. It reaches the same place through a blocking hop, adds a second threading
rule to the pipeline's documented one, and buys nothing — the work it defers is trivial.

### D3 — `Outcome.Written` means written *and* visible

Stage 3 currently receives an `Outcome` decided in stage 2. With D1 the write itself happens in stage
3, so the outcome that authorizes `clear()` is produced by the same write action that put the text in
the document. Stage 2 is reduced to what it should have been: verify anchors, plan the content, and
hand stage 3 either `NothingToSubmit` or the exact string to write. Nothing between the two stages
can invalidate the signal, because there is no longer anything between them.

### D4 — Platform inlay placement, with our own inner width cap

Replace `EditorEmbeddedComponentManager.addComponent(editorEx, panel, Properties(...))` with
`editor.addComponentInlay(offset, InlayProperties(), component, ComponentInlayAlignment
.FIT_VIEWPORT_WIDTH)`.

Verified semantics (`ComponentInlaysContainer.doLayout`):

```
FIT_VIEWPORT_WIDTH   bounds.width = max(component.minimumSize.width,
                                        viewport.width − verticalScrollBar.width)
                     bounds.x     = the inlay's own x
recomputed on        VisibleAreaListener + content-resize + folding listeners
```

That is `availableWidthPx` and the whole of `InlineWidthWatcher`, already written and already
maintained by JetBrains.

Property mapping: `relatesToPrecedingText`, `showAbove`, `showWhenFolded` and `priority` carry over
to `InlayProperties` one-for-one. `fullWidth` has no counterpart — the alignment *is* that concept.
`ResizePolicy.none()` disappears; nothing in Relay used a non-none policy.

The reading-measure cap stays ours. `CodeReviewComponentInlayRenderer` gets it from
`wrapWithLimitedWidth`, a `JPanel` under `SizeRestrictedSingleComponentLayout` with `prefSize` and
`maxSize` both pinned to the measure — but that layout lives in `com.intellij.collaboration.*`, which
ARCHITECTURE §8 rules out as a dependency. So `pinLeading`'s BoxLayout-plus-glue is replaced by a
small single-component layout of our own doing the same job: lay the one child out at
`min(parent.width, readingMeasure)`, pinned leading. A layout manager rather than a glue trick,
which also retires the design's unconfirmed question about whether glue reliably caps visible width.

`InlineWidth` keeps `readingMeasurePx` and nothing else.

### D5 — Drop the right-margin cap

The platform rule has no right-margin term. Keeping ours would mean keeping `rightMarginPx`,
`columnsPx`, `EditorUtil.getPlainSpaceWidth`, the `MIN_CAP_DP` floor that exists only to stop a
narrow guide from crushing the button row, and a plugin-side width computation feeding a
platform-side layout — i.e. keeping the machinery this change exists to delete, to preserve a cap
that a 320dp floor already neuters for most configurations.

D4's reading measure is UI-font-relative and the right margin is editor-column-relative; they were
never commensurable. Dropping it is a visible behavior change for anyone with a narrow guide column,
which is why it is written into the `review-annotation` and `review-batch` deltas as its own
scenario rather than left to fall out of the implementation.

### D7 — Reserve what the inspections widget covers, measured from bounds

`FIT_VIEWPORT_WIDTH` lays a row out at `viewport.width − verticalScrollBar.width`. The inspections
widget — the floating "no problems found" toolbar — is **not** in that subtraction and not in the
viewport's layout: `EditorMarkupModelImpl` hands it to the scroll pane via
`JBScrollPane.setStatusComponent`, and it floats over the content area. So a surface that fills the
row on a narrow editor is laid out underneath it, and the card's trailing Edit/Delete icons become
unreachable. The widget's own avoidance is caret-based only (`doUpdateTrafficLightVisibility`
collapses it when the *caret's* XY falls inside its bounds); inlays are invisible to it, which is why
the platform's own review comments behave the same way and why adopting their arrangement did not fix
this.

`InlineWidth.overlayInsetPx` reserves it. Two decisions inside that:

- **Measured from laid-out bounds, not from the widget's width.** The widget partly overhangs the
  vertical scrollbar, which is already outside the viewport, so its width overstates what it costs the
  content area. The distance from its leading edge to the viewport's trailing edge answers the only
  question that matters and does not depend on how the scroll pane positions it.
- **Reserved unconditionally, not per scroll position.** The widget is fixed to the top of the
  *viewport* while a surface sits in the *document*, so which rows it actually overlaps changes on
  every scroll. Sizing on the live overlap would re-wrap comments as the user scrolls past them. On a
  wide editor the reading measure dominates anyway, so the reserve only bites where the overlap
  happens.

**The row reports a zero minimum.** `FIT_VIEWPORT_WIDTH` lays the row out at
`max(minimumSize.width, viewport − scrollbar)`, so that minimum is a floor the viewport cannot pull
the row below — and it must not be derived from the child. `Component.getMinimumSize` caches its
answer from the component's **current size** when no explicit minimum was set, so a child that has
been laid out once reports its laid-out width as its minimum; the floor then ratchets up to whatever
width the row last had and the row can never shrink again. Nothing needs protecting by a floor here:
the row is a container, and the surface inside it is sized by the cap and the reserve.

This one is also a warning about test doubles. The first cut's row tests used a stub child with an
explicit `getMinimumSize`, which by construction cannot ratchet — they passed while the IDE was
visibly broken. The behaviour only appears through the platform's own placement and layout, so that
is what the regression test drives.

**Correcting the reason this was rejected earlier.** The alternative was dismissed on the ground that
the widget's geometry is unreachable — `EditorMarkupModelImpl.statusToolbar` and `cachedToolbarBounds`
are both private. That is true and irrelevant: the widget is registered with the scroll pane through
**public** `setStatusComponent`/`getStatusComponent`, so no private access and no hardcoded inset is
needed. The earlier assessment looked only at the class that owns the widget, not at the one it is
handed to.

### D6 — ARCHITECTURE §8 is wrong about this API and must be corrected, not quietly relied on

§8 places `Editor.addComponentInlay` / `ComponentInlayRenderer` / `ComponentInlayAlignment` in "the
**stable** platform" and contrasts them with the collaboration-tools code-review package, which it
rules out because that package is `@ApiStatus.Experimental`.

All three of those classes are `@ApiStatus.Experimental` in 2024.2.5. The stated ground for the
distinction does not hold.

The move is still defensible, on different ground: Relay today depends on
`com.intellij.openapi.editor.**impl**.EditorEmbeddedComponentManager`, an implementation-package class
carrying no stability contract at all. Trading it for an experimental class in the public `openapi`
package is a step toward stability, not away from it — and it is the API the platform's own
review-comment inlays are built on, so it will not be removed without a replacement. But this is the
maintainer's call to make knowingly, so §8 is corrected as part of this change rather than after it.

Also verified while checking: `intellij.platform.collaborationTools` **is** bundled in PyCharm
Community 2024.2.5 (`lib/modules/`), which §8 records as unverified. The don't-depend rule stands on
stability grounds; only the open question closes.

## Risks / Trade-offs

- **The authoring box hosts a live `EditorTextField`, and the inlay hosting path changes underneath
  it** → `review-annotation` carries strong requirements about focus movement, keystroke ownership,
  and undo/redo scoping to the box rather than the file. `ComponentInlaysContainer` implements
  `EditorHostedComponent` with `isInputFocusOwner`, and the platform's own review *reply editor* runs
  on this path, so the capability exists — but it is the highest-risk part of this change. Mitigation:
  QA the focus, undo-scoping, and Escape/Enter scenarios explicitly, not just the visual ones; if
  they regress, the box can stay on `EditorEmbeddedComponentManager` while the read-only card moves,
  at the cost of two hosting paths.
- **Deleting `InlineWidthWatcher` deletes its tests** → the "surface tracks the editor" behavior
  becomes the platform's, and unit tests can no longer observe it. Mitigation: keep a headless test
  asserting the renderer is constructed with `FIT_VIEWPORT_WIDTH` and the inner cap at the reading
  measure — the two decisions that are ours — and move the tracking behavior to the QA checklist.
- **`@ApiStatus.Experimental`** → a future platform release may change or withdraw the API. Mitigation:
  the surfaces already go through one construction site each; a revert to
  `EditorEmbeddedComponentManager` restores the previous path without touching the surfaces' own code.
- **The `Document` write on the EDT is a real EDT write** → for a pathological batch (hundreds of
  comments) the string is large. Mitigation: none needed at this size, but the planning stays in
  stage 2 so only `setText` is on the EDT; if it ever matters, the export is already a pure function
  and can be measured.
- **`WriteCommandAction` makes the export undoable** → a user pressing Ctrl+Z in `REVIEW.md` can
  revert the export *after* the batch is cleared. This is not a regression (today they can edit the
  file freely), and the file is regenerated on the next submit, but it is new behavior worth naming.
- **Right-margin users see wider surfaces** → deliberate; D5.

## Migration Plan

No data migration. `REVIEW.md` content is byte-identical; only how it is written changes. The width
change is visible but self-correcting on the next editor layout. Rollback is a revert of the change —
neither half writes persistent state in a new format.

## Open Questions

1. ~~**Does the box move too, or only the card?**~~ **RESOLVED:** both moved. The maintainer confirmed
   on 2026-08-08 that undo inside the box still acts on the comment body, so the split-hosting
   fallback is not needed and Relay keeps one placement path.
2. **§8's charter** — confirmed the correction (D6) is what the maintainer wants recorded, given the
   API's real status is experimental rather than stable.
