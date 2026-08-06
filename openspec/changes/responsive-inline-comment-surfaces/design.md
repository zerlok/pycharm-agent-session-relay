## Context

Both inline surfaces derive their width from `InlineWidth`, which is a pure function of the editor —
evaluated **once**, when the surface is built:

- `StoredCommentCard.build` captures `val baseWidth = InlineWidth.baseWidthPx(editor)`, overrides
  `getMaximumSize`/`getPreferredSize` to that number, and defines
  `contentWidth(insets) = baseWidth - insets.left - insets.right`. Both `getPreferredSize` and
  `doLayout` read that one value — the header is laid out across it, and the body is both *measured*
  and *laid out* at it.
- `CommentDraft.buildPanel` does the same for the box (`baseWidth` pinned as the preferred width,
  `InlineWidth.capWidth(content, cap)` as the wrapper).
- `InlineWidth.capWidth` wraps the surface in a `BoxLayout` row with a trailing glue, which is what
  squeezes the surface when the row is narrower than its preferred width.
- Nothing in `ui/` observes an editor resize. Cards are rebuilt only from store events
  (`EditorReviewOverlay`, by diff) and editing events.

So the captured width is a sample of live editor state. Four things invalidate it: a viewport resize
(split, window, tool window), an editor font-size change (it feeds `EditorUtil.getPlainSpaceWidth`),
a right-margin setting change, and a LaF/`JBUI` scale change. When it goes stale low — the editor is
now narrower than the captured width — `doLayout` still positions the header across the *old* width,
so the right-anchored Edit/Delete icons are placed outside the viewport, and the body is laid out
wider than the card's own bounds and is clipped by them.

**The fixed width is load-bearing, and that is the constraint this change has to respect.** The
`stored-comment-card-presentation` design records that making the height depend on the card's *actual*
width caused a layout feedback loop that pegged the CPU: `getPreferredSize` measured the body at one
width while `doLayout` sized it at another, and the two never settled. The in-code comment on
`contentWidth` is explicit that keeping both sides on one number is what stopped it.

### The reference implementation

JetBrains solves this exact problem — review comments as editor inlays — in
`intellij.platform.collaborationTools`, the module shared by their GitHub and GitLab plugins.
Established by disassembling the 2024.2.5 jars on this classpath:

- `EditorComponentInlaysManager` holds an `EditorTextWidthWatcher : ComponentAdapter` registered on
  the editor. It reacts to `componentResized`, `componentHidden` and `componentShown`, and calls
  `updateWidthForAllInlays()`.
- `calcWidth() = min( max(viewport.width − verticalScrollbarWidth − gutterTextGap, 0),
  JBUI.scale(PREFERRED_INLAY_WIDTH) )`. It reads the real scrollbar width and gutter gap, and handles
  a flipped vertical scrollbar.
- `PREFERRED_INLAY_WIDTH = CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH + 52`, where
  `TEXT_CONTENT_WIDTH = round(JBUIScale.DEF_SYSTEM_FONT_SIZE × 42)` — a reading measure in **UI font
  size units**, not editor columns.
- `ComponentWrapper.getPreferredSize()` returns
  `Dimension(watcher.editorTextWidth, component.preferredSize.height)`. The **width is a value pushed
  in from the watcher; only the height is pulled from the content.**
- `CodeReviewMarkdownEditor.setEditorFontFromComponent(EditorEx)` copies the Swing component's font
  name and size into the editor's colors scheme, re-applying on the `"font"` property change — i.e.
  the review-comment *editor* is deliberately forced onto the UI font. The display side uses HTML
  panes (`JEditorPane`), never `JTextArea`.

### Why the fonts differ today

`LafManagerImplKt.initFontDefaults` builds two font resources: `FontUIResource(uiFont)` and
`FontUIResource("Monospaced", PLAIN, uiFont.size)`. It assigns `TextArea.font` (and `PasswordField.font`
off macOS) the **monospaced** one, and `TextPane.font` / `EditorPane.font` the UI one. The card's body
is a `JBTextArea`, so it inherits Monospaced; the box's body is an `EditorTextField`, whose
`myInheritSwingFont` field is initialised to `true` in the constructor, so it pushes its own Swing font
(the UI font) into its inner editor. Neither surface sets a font, so the divergence is pure LaF
inheritance — the card's mono was never chosen by this codebase.

## Goals / Non-Goals

**Goals:**

- The card's Edit/Delete affordances are reachable at any editor width.
- Neither surface renders wider than the editor's available content width; the body re-wraps instead
  of being clipped.
- Both surfaces re-size when the editor's available width changes, rather than keeping the width they
  were built with.
- The card and the box render their bodies in one font, defined in one place.
- The layout feedback loop stays closed — this change must not reintroduce it.

**Non-Goals:**

- Any change to the surfaces' *vertical* behavior: the box's grow-on-edit path (`comment-box-editing-
  fidelity` D2-R), the card's rest-vs-hover height invariant, and the `BODY_ROWS` floor are untouched.
- Range/anchor geometry, edge-drag resize, the tool window, and the box's undo/keystroke ownership.
- Rich text, markdown rendering or HTML panes in either surface. The card stays a plain text area;
  only its font changes.
- Horizontal scrolling of a surface. A surface never exceeds the available width, so nothing scrolls.

## Decisions

**D1 — Width flows *down* from the editor; height flows *up* from content. Nothing reads its own
width while measuring.** This is the rule that makes a responsive surface safe here, and it is the one
the reference implementation follows. The feedback loop that pegged the CPU was created by a layout
*output* (the card's actual width) feeding a measure *input* (`getPreferredSize`). A watcher-supplied
width is not an output — it is an input computed from the editor's viewport, which no surface
influences. So the cycle has no edge to close.

Critically, this **preserves** the existing invariant rather than trading it away: `getPreferredSize`
and `doLayout` still read exactly one width per pass. That number simply stops being frozen at build
time and becomes the watcher's current value.

```
   today:    baseWidth = f(editor)  ─ captured once ─▶  preferred + doLayout   (one number, stale)
   rejected: this.width             ─ read back ─────▶  preferred + doLayout   (the CPU loop)
   chosen:   watcher.width = f(viewport, caps) ──────▶  preferred + doLayout   (one number, live)
```

- _Alternative — lay the header out from `this.width` and leave the body on the captured width:_ the
  narrow fix for the reported symptom. Rejected: it puts two different widths inside one component,
  which is precisely the "two places, one number" shape the previous change identified as the hazard,
  and it leaves the body clipping and the staleness unfixed.
- _Alternative — recompute and rebuild the card (dispose + add) on resize:_ correct but coarse; it
  discards and recreates an inlay on every resize event, and it cannot fix the box, which is not
  rebuilt from the store. Rejected in favour of re-sizing in place (D3).

**D2 — One watcher per editor, owned by `EditorReviewOverlayService`, published on the editor.**
The service already has exactly the lifecycle a watcher needs: it creates an overlay on
`editorCreated` and frees it on `editorReleased`. The watcher is created and disposed alongside, and
published so that any surface on that editor can find it — the card is built by `EditorReviewOverlay`
(per editor), but the box is built by `CommentDraftController` (per project), so a shared, editor-keyed
lookup is what lets both read one width without a new dependency edge between them.

Surfaces register with the watcher on build and unregister on dispose. When no watcher is present —
an editor the service never saw, or a test fixture — `InlineWidth` falls back to computing the width
directly from the editor at call time, which is today's behaviour minus the staleness.

- _Alternative — each surface installs its own `ComponentListener`:_ simplest, but a file with many
  stored comments then attaches one listener per card to the same editor and recomputes the same
  number N times per resize. The reference implementation keeps one watcher and a map of managed
  components for this reason. Rejected.
- _Alternative — a project- or application-level service:_ the quantity being watched is per *editor*
  (viewport, scrollbar, gutter, font size), so a wider scope would need an editor→width map and a
  disposal story it does not otherwise have. Rejected.

**D3 — On a width change, re-size in place and `revalidate()`; do not rebuild.** The watcher pushes
the new width into each registered surface and revalidates it. That reaches the inlay through
`EditorEmbeddedComponentManager$MyRenderer` → `synchronizeBoundsWithInlay`, which re-reads the
preferred size and calls `Inlay.update()` itself. This is not a new mechanism: it is exactly the path
`comment-box-editing-fidelity` D2-R established for content changes, and Open Question 7 there
confirmed in a running IDE that `revalidate()` is what drives it — with the deferred revalidate
removed, the box did not re-size. Re-using it keeps one re-measure path for both triggers (content
changed, width changed) instead of two.

**D4 — The reading measure becomes UI-font-relative, replacing `BASE_COLUMNS = 80` editor columns.**
Once the body renders in a proportional UI font, sizing the surface in *editor columns* measures
proportional text with a monospace ruler — the two are no longer the same units. The reference
implementation's measure is `round(DEF_SYSTEM_FONT_SIZE × 42) + 52`, scaled. In practice the two land
close together (80 mono columns ≈ 560–640px; theirs ≈ 556dp), so this is a coherence fix rather than a
visible resize.

The full rule becomes: `width = min(available, readingMeasure, rightMargin when configured)`, where
`available = max(viewport.width − verticalScrollbar − gutter, 0)`.

- _Note on a spec correction this forces:_ both surfaces' specs said they "fall back to spanning the
  full editor width" when no right margin is configured. That has not been true since
  `comment-box-sizing` introduced `baseWidthPx`, which caps at 80 columns regardless. The delta specs
  replace that scenario with the reading-measure cap, which is what the code already does and what
  this change keeps doing.

**D5 — The header reserves the icons and truncates the label.** With the header laid out across a
width that can now be genuinely small, the author label and the trailing icons can collide. The icons
win: they are the card's only actions, and the label is a fixed presentation constant (`"You"`) that
carries no information. The header keeps its hand-rolled `doLayout` — the "identical height at rest
and on hover" invariant depends on positioning hidden children explicitly — and gains a reserve step:
compute the icons' width first, give the label what remains.

**D6 — The shared body font lives in `RelayStyle`.** `RelayStyle` already exists precisely so the two
surfaces cannot drift apart, and its KDoc gives the reason: they are one object in two states. Its
current scope line says *colors and the surface fill only — no components, no borders, no layout*,
with the rationale that handing out borders would create a second home for the card's geometry (its
insets are where `contentWidth` comes from). A font is a shared visual token like a color, not
geometry: it introduces no second place from which a width is derived. The KDoc's scope sentence is
updated to name the font rather than being quietly contradicted.

The card sets that font on its body explicitly, so it stops inheriting `TextArea.font`. The box needs
no change in mechanism — `EditorTextField` already forwards its Swing font to the inner editor — but
sets the same value explicitly rather than relying on inheritance, so the two are pinned to one
source rather than agreeing by coincidence.

## Risks / Trade-offs

- **[Reintroducing the CPU-pegging layout loop]** → the mitigation is D1's rule, stated as an
  invariant a reviewer can check mechanically: *no measure path may read the surface's own width*.
  `getPreferredSize` and `doLayout` must both read the watcher value, and the body must be measured
  and laid out at the same number in the same pass — the property that already holds today.
- **[A resize storm: dragging a window edge fires many `componentResized` events, each revalidating
  every card in the file]** → the watcher stores the last computed width and does nothing when it is
  unchanged, so only genuine width changes propagate; and `synchronizeBoundsWithInlay` already
  re-bounds only when the height actually changed.
- **[Watcher lifecycle]** → it is created and disposed by `EditorReviewOverlayService` on
  `editorCreated`/`editorReleased`, the same seam the overlay uses; surfaces unregister on their own
  dispose. A leaked registration would retain a disposed inlay's component.
- **[Headless tests cannot lay out an undisplayable hierarchy]** → same limitation this repo already
  works under. Tests assert the *inputs and the plumbing* — the width the watcher computes for a given
  viewport, that a surface re-sizes and revalidates when the watcher changes, that the header reserves
  the icons, and that both bodies carry the same font — not rendered pixels. Actual reflow stays a
  running-IDE check.
- **[Changing the reading measure changes every existing surface's width slightly]** → accepted and
  small (D4); it is a units fix that follows from the font change.

## Migration Plan

None required. No persisted data, no API and no user setting changes shape; the surfaces are rebuilt
from the store on every editor open. Rollback is reverting the change.

## Open Questions

Nothing below can be settled on this machine — no display, so `runIde` is impossible and the only
local evidence is `compileKotlin`, the unit suite and the platform bytecode. Each must be answered in
a running IDE and recorded back here.

1. Does the card actually re-wrap and grow taller when the editor narrows, or does the inlay keep the
   height it last reported? This is D3's assumption — that a width change reaches
   `synchronizeBoundsWithInlay` the same way a content change does.
2. Is one `revalidate()` per width change enough for the *box*, whose body is an `EditorTextField`
   that recomputes soft wraps asynchronously? The content path needed an `invokeLater` for exactly
   this reason (`comment-box-editing-fidelity` D2-R); the width path may need the same deferral.
3. Does dragging the window edge or the splitter feel smooth with several cards open in one file, or
   is per-event revalidation visible as lag?
4. Does the UI font read acceptably for comment bodies that quote code — identifiers, paths, short
   snippets — given that this is a code-review surface? If not, the alternative is the opposite
   parity: move the *box* to the editor font instead of the card to the UI font.
5. ~~Where exactly does `available` need to come from — `editor.scrollingModel.visibleArea.width`, or
   the scroll pane's viewport width less the real scrollbar and gutter as the reference implementation
   computes it? The current `VISIBLE_MARGIN_DP = 16` fudge should disappear either way.~~
   **Decided at implementation time (2026-08-06): `editor.scrollingModel.visibleArea.width`, unadjusted**
   (`InlineWidth.availableWidthPx`), with the `VISIBLE_MARGIN_DP` fudge deleted as agreed. Two reasons,
   neither of which needs a display to state:
   - That rectangle is the scroll pane's *viewport* rect, so the gutter (the scroll pane's row header)
     and the vertical scrollbar are already outside it. `EditorTextWidthWatcher` subtracts them because
     it starts from the raw viewport *component*; subtracting them again here would under-size the
     surface by ~30px at every width.
   - It is the same quantity the platform sizes a `fullWidth` block inlay's row from, so a surface
     capped at it is capped at exactly the row it is laid out in — the two numbers cannot drift.
   A non-zero value is required for it to cap at all: a not-yet-laid-out editor reports a zero-area
   viewport, and treating that as `available = 0` would collapse every surface rather than leaving it at
   the reading measure until the first resize arrives.
   **Still to confirm in a running IDE:** whether the IDE's *overlay* scrollbar mode leaves the
   scrollbar inside `visibleArea`, in which case the header's trailing icons would sit under it at
   viewport-bound widths. If so the fix is local — subtract
   `editor.scrollPane.verticalScrollBar.width` in `availableWidthPx` — and changes nothing else.

**Answered in practice while implementing (2026-08-06), and worth recording against the questions above:**

- The watcher reacts to a `VisibleAreaListener` as well as the three component events (a deviation from
  D2's letter, kept because the *spec* requires a surface to follow "a change to the editor font size or
  the right-margin setting", and neither resizes the editor's component). Whether a Ctrl+scroll font
  change actually fires `visibleAreaChanged` is unverified here — it is question 3's neighbour and
  belongs on the same running-IDE pass.
- Nothing in questions 1–4 became decidable on this machine; they stay exactly as written and are the
  content of the task 7.3 handover.
