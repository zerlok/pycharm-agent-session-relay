## Context

Agent Session Relay is a greenfield IntelliJ Platform plugin (Kotlin, `io.github.zerlok.agentsessionrelay`). The full product design lives in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md); this change implements its §8 "first change" — the single-session review surface — and bootstraps the buildable plugin to host it.

Environment constraints (from the architecture doc) that shape this design:
- The agent runs **remotely** in tmux; the IDE runs **locally**. Files move by **mutagen** sync, never git transport.
- The PyCharm terminal tab is already inside `ssh → tmux → claude`, so delivery is "type into that widget", not a new connection.
- Build on **trusted** editor APIs only; avoid the internal `com.intellij.collaboration.*` review framework.

## Goals / Non-Goals

**Goals:**
- A buildable, `runIde`-able plugin scaffolded from the IntelliJ Platform Plugin Template.
- Author / view / edit / delete line-anchored comments on any file; gutter markers + tool window.
- Submit → write `REVIEW.md` (Claude-format) → relay an instruction into the active agent terminal.
- A comment data model that already carries `anchorText` + `contextHash` (so later re-anchoring is additive, not a rewrite).

**Non-Goals (deferred to follow-on changes):**
- Terminal launcher; persistence across IDE restarts; multi-session/worktree-aware delivery; `ssh + tmux send-keys` fallback; remote plan capture; non-Claude exporters; fuzzy re-anchoring of drifted comments.

## Decisions

### D1 — Bootstrap from the IntelliJ Platform Plugin Template (Kotlin, Gradle)
Use the official template's structure (`build.gradle.kts`, `gradle.properties` with `pluginGroup`/`pluginName`/platform version, wrapper, `plugin.xml`, GitHub CI). *Why:* it bakes in plugin verification, `runIde`, and Marketplace publishing — re-deriving these by hand is pure cost. *Alternative considered:* hand-rolled Gradle — rejected (no upside, loses CI/verifier wiring).

### D2 — In-memory comment store as a project-level service (no persistence yet)
A `Project`-scoped service holds the pending `ReviewComment` list and notifies listeners (gutter, tool window) on change. *Why:* persistence is a deferred non-goal; keeping the store in memory avoids `PersistentStateComponent` + re-anchoring complexity in the MVP. *Trade-off:* comments are lost on IDE restart — acceptable because the workflow is "annotate while agent idle → submit promptly". The model fields needed for persistence (`anchorText`, `contextHash`) are stored now so adding persistence later is additive.

### D3 — Anchor with a `RangeMarker` plus stored text + context hash
Each comment holds a live `RangeMarker` (tracks in-IDE edits automatically) **and** the `anchorText` + a `contextHash` of surrounding lines. *Why:* the marker handles edits made inside the IDE for free; the stored text/hash is the seed for out-of-IDE (agent) re-anchoring in a later change. The export reads the marker's *current* line range at submit time, so a review submitted while the agent is idle is accurate. *Alternative:* store only line numbers — rejected (breaks on any in-session edit; no path to re-anchoring).

### D4 — Gutter via `LineMarkerProvider` / `GutterIconRenderer`; list via `ToolWindowFactory`
Standard trusted APIs. The tool window subscribes to the store and renders comments grouped by file with navigate-to-line (open file + move caret to start line). *Why:* these are the documented, stable extension points and match the architecture doc's reuse list.

### D5 — Exporter is a pure function `List<ReviewComment> → markdown`
Serialization (Claude `@path#Lstart-end` + body) is isolated from delivery so the same output feeds both the live preview and the written `REVIEW.md`, and so non-Claude exporters can slot in later behind the same seam. *Why:* preview and file must never diverge; keeps the agent-specific bit small and swappable.

### D6 — Delivery = write file, then type into the terminal widget
`REVIEW.md` is written at the project base path (mutagen carries it to the sandbox). Then the plugin obtains the active terminal widget via `TerminalToolWindowManager` and sends the instruction text + Enter. *Why:* matches the resolved Submit decision (file payload, typed-into-tab channel) with no second SSH. If no widget is available, write the file anyway and inform the user (never silently drop). *Alternative:* `ssh tmux send-keys` — deferred to a follow-on as the bring-your-own-terminal fallback.

### D7 — Threading discipline
File I/O and terminal send run on a background thread (`Task.Backgroundable` / pooled executor). VFS refresh uses async refresh. PSI/document reads use read actions; store/UI mutations happen on the EDT. *Why:* the architecture doc's cross-cutting rule; blocking the EDT on I/O is the classic new-plugin freeze.

## Risks / Trade-offs

- **Mutagen sync race** (file written locally but not yet on the sandbox when the agent reads it) → For the MVP, accept it: mutagen latency is typically sub-second and the user triggers submit deliberately. Mitigation is deferred (e.g. a brief wait or a poll in the typed command).
- **No persistence** → comments lost on restart → Acceptable per the idle-review workflow; model is persistence-ready (D2/D3).
- **Anchor drift from out-of-IDE edits** → a comment could point at moved lines if the agent edits after annotation → Mitigated by loop discipline (submit before resuming) now; programmatic re-anchoring is a follow-on. The `RangeMarker` covers in-IDE edits today.
- **Terminal widget targeting** → identifying "the active agent terminal" is ambiguous if multiple terminals are open → MVP targets the selected/active terminal widget; robust per-session targeting comes with the multi-session change.
- **Platform API churn** → mitigated by sticking to the trusted APIs in the architecture doc's reuse list and the plugin verifier from the template.

## Open Questions

- Exact instruction wording typed into the terminal (`read REVIEW.md and address the comments` vs a configurable string) — default to a constant for the MVP; make configurable later.
- Single-line reference rendering: `@path#L10` vs `@path#L10-10` — pick whichever Claude resolves most reliably during implementation; the spec allows either.
- Whether "Refresh & review" should also be wired to frame-activation refresh now or left manual — default manual for the MVP.
