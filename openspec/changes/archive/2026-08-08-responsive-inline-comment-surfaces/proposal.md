## Why

Both inline review surfaces size themselves from a width sampled **once, at build time**, and nothing
recomputes it. Narrow the editor after that — split right, shrink the window — and the stored card's
Edit/Delete icons are laid out past the viewport's right edge, where the user cannot reach them. The
same staleness clips the card's body text and leaves the authoring box measuring at a width it no
longer has.

Separately, a saved comment and the same comment being edited render in **different typefaces**
(Monospaced vs the UI font), so clicking Edit makes the object look like a different object —
contrary to the shipped requirement that the box is the card's editing state.

## What Changes

- The inline surfaces' width becomes **live rather than frozen**: a width watcher on the editor
  recomputes the available width on resize and pushes it into every surface, capped at a reading
  measure. Width flows *down* from the editor as an input; height stays an output of content. Nothing
  reads its own width while measuring — that is the rule that keeps the layout feedback loop closed
  (see design D1).
- The card's Edit/Delete affordances stay **reachable at any editor width**, with an explicit
  collision rule when the row is too narrow for both the author label and the icons.
- The card's body text stops being clipped when the editor is narrower than the surface's preferred
  width: it re-wraps to the width actually available.
- Both surfaces render their body in **one shared font**, defined in a single place alongside the
  existing shared accent/surface values.
- The reading measure moves off *editor columns* onto a UI-font-relative measure, because a
  proportional body font makes a monospace column count the wrong ruler.
- Alignment note: all of the above follows `com.intellij.collaboration.ui.codereview`, the platform
  module JetBrains' own GitHub and GitLab plugins use to render review comments as editor inlays.
  Details and citations in design.md "## Context".

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `review-batch`: "Render stored comments as an inline card" — the card's width requirement changes
  from a build-time cap to tracking the editor's available width; its Edit/Delete affordances gain a
  reachability guarantee at any width; its body gains the shared-font requirement.
- `review-annotation`: "Author a line-anchored comment in an inline box" — the box's width
  requirement changes the same way, so the two surfaces stay identical. "Present the authoring box as
  the stored card's editing state" — the body font joins the values that must be defined in exactly
  one place, so card and box cannot drift apart.

## Impact

- Code, presentation layer only (`ui/`), per `docs/ARCHITECTURE.md`: `InlineWidth.kt` (the width
  source becomes live), `StoredCommentCard.kt` (header collision rule, body wrap width, font),
  `CommentDraft.kt` (box width source, body font), `RelayStyle.kt` (the shared font joins
  `surface()`/`ACCENT`), `EditorReviewOverlay.kt` (watcher ownership and lifecycle). No domain,
  storage, logic, export or delivery change.
- Dependencies: none new. The watcher is a `java.awt.event.ComponentAdapter` on the editor; every
  API used already ships with the 2024.2.5 platform on the classpath.
- Risk concentrated in one place: the previous card change recorded a layout feedback loop that
  pegged the CPU, and the fixed width was what stopped it. This change keeps that invariant (one
  shared width per pass) and changes only where the number comes from. D1 carries the argument.
- Deliberately **not** in scope: any change to the surfaces' vertical behavior, the range/anchoring
  geometry, the tool window, or the box's undo/keystroke ownership.
