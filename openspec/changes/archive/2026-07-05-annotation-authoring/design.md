## Context

Greenfield IntelliJ Platform plugin (Kotlin, `io.github.zerlok.agentsessionrelay`). Technical
design: [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md). This change is the first relay
stage — the annotation surface — and bootstraps the buildable plugin to host it. It builds only
on trusted, public editor APIs (never `com.intellij.collaboration.*`).

## Decisions

### D1 — Bootstrap from the IntelliJ Platform Plugin Template (Kotlin, Gradle)
Use the official template's structure (`build.gradle.kts`, `gradle.properties`, wrapper,
`plugin.xml`). *Why:* it bakes in plugin verification, `runIde`, and Marketplace publishing —
re-deriving these by hand is pure cost.

### D9 — Author via a hover-gutter "+" and an inline block-inlay box (supersedes the popup + keybinding plan)
The original plan (`mvp-review-surface`) authored comments through a keybound `AnAction` that
opened a floating **popup**. Implementation replaced this with a **hover-revealed "+" gutter icon**
that opens an **inline, full-width comment box rendered as a block inlay** below the target lines
(commit `de40cc5`). *Why:*

- **Discoverability + directness** — the "+" appears exactly where you point, matching the
  GitHub/GitLab review affordance; no keymap to learn or document.
- **In-flow, not floating** — a block inlay pushes the following code down and stays anchored to
  the range as you scroll/edit, rather than a popup that floats over and dismisses on focus loss.

The inline box lives *inside* the editor's content component, which creates two races the popup
never had — both handled and worth recording so they are not "simplified" back into bugs:

- **Focus race** — the inlay is not laid out when it is added, so an immediate focus request
  no-ops and the editor keeps the keyboard (keystrokes edit code, not the box). Focus is deferred
  via `invokeLater` and routed through `IdeFocusManager`, which wins over the editor re-grabbing
  focus after the gutter click.
- **Key-dispatch race** — Enter/Ctrl+Enter/Esc are seen first by the IDE key dispatcher, which
  resolves the surrounding editor and runs the *editor's* action (Enter → newline in code) before
  Swing delivers the key. A plain Swing `registerKeyboardAction` loses that race, so the box binds
  these keys as `AnAction`s registered on the focused text area itself (dispatched ahead of
  keymap/editor actions while the box has focus).

*Trade-off:* more Swing/inlay plumbing than a popup, and the box is bound to the editor lifecycle.
Accepted for the in-flow UX; the plumbing is documented at its call sites.

### D10 — Submit is a stub in the baseline (report, don't yet store)
On submit the baseline only logs the captured comment and shows a confirmation notification. *Why:*
the comment model + store is the next stage (`comment-batch`); wiring submit to a real batch is an
additive change to this same surface. The `review-annotation` requirement here therefore specifies
*capturing and surfacing* the comment, and the `comment-batch` change MODIFIES submit to persist it.

## Risks / Trade-offs

- **Box bound to editor lifecycle** → if the hosting editor closes while a box is open, the inlay
  and highlighter are released with it. Acceptable; the single-active-box controller also disposes
  the current box when a new one opens.
- **Platform API churn** (`EditorEmbeddedComponentManager` is a semi-public API) → mitigated by the
  plugin verifier from the template and by keeping the inlay usage minimal and centralized.
