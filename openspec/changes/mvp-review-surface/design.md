## Context

Agent Session Relay is a greenfield IntelliJ Platform plugin (Kotlin, `io.github.zerlok.agentsessionrelay`). The technical design lives in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md); the product requirements (capture modes, the user flow, scope phasing) live in this change's [`proposal.md`](./proposal.md). This change implements the single-session review surface and bootstraps the buildable plugin to host it.

Environment constraints that shape this design:
- **Local-only** is the baseline (agent + IDE on one host). A **remote** agent (e.g. tmux on a sandbox) is the more complex case; either way Relay only reads/writes the **local working tree** and never uses git as a cross-host transport. Cross-host file sync (e.g. mutagen) is the session's own responsibility, not Relay's.
- Build on **trusted** editor APIs only; avoid the internal `com.intellij.collaboration.*` review framework.

## Goals / Non-Goals

**Goals:**
- A buildable, `runIde`-able plugin scaffolded from the IntelliJ Platform Plugin Template.
- Author / view / delete line-anchored comments (line / line range); gutter markers + tool window.
- Submit → write `REVIEW.md` (Claude-format) at the project root → notify the user it is ready to hand to the idle session.
- A comment data model whose subject is open (so multi-file is not foreclosed) and that already carries `anchorText` + `contextHash` (so later re-anchoring is additive, not a rewrite).

**Non-Goals (deferred to follow-on changes):**
- Capture modes beyond the open-file base (the changed-files diff / change-view entry and the plan-file entry); whole-file and batch-level comment authoring; multi-file comment authoring; in-place comment editing (delete + re-add only); live export preview; multi-session/worktree-aware delivery (the MVP targets a single project root); automatic relay (typing the instruction into the active agent terminal widget); terminal launcher; persistence across IDE restarts; `ssh + tmux send-keys` fallback; remote plan capture; non-Claude exporters; fuzzy re-anchoring of drifted comments.

## Decisions

### D1 — Bootstrap from the IntelliJ Platform Plugin Template (Kotlin, Gradle)
Use the official template's structure (`build.gradle.kts`, `gradle.properties` with `pluginGroup`/`pluginName`/platform version, wrapper, `plugin.xml`, GitHub CI). *Why:* it bakes in plugin verification, `runIde`, and Marketplace publishing — re-deriving these by hand is pure cost. *Alternative considered:* hand-rolled Gradle — rejected (no upside, loses CI/verifier wiring).

### D2 — In-memory comment store as a project-level service (no persistence yet)
A `Project`-scoped service holds the pending `ReviewComment` list and notifies listeners (gutter, tool window) on change. *Why:* persistence is a deferred non-goal; keeping the store in memory avoids `PersistentStateComponent` + re-anchoring complexity in the MVP. *Trade-off:* comments are lost on IDE restart — acceptable because the workflow is "annotate while agent idle → submit promptly". The model fields needed for persistence (`anchorText`, `contextHash`) are stored now so adding persistence later is additive.

### D3 — Anchor with a `RangeMarker` plus stored text + context hash
Each comment holds a live `RangeMarker` (tracks in-IDE edits automatically) **and** the `anchorText` + a `contextHash` of surrounding lines. *Why:* the marker handles edits made inside the IDE for free; the stored text/hash is the seed for out-of-IDE (agent) re-anchoring in a later change. The export reads the marker's *current* line range at submit time, so a review submitted while the agent is idle is accurate. *Alternative:* store only line numbers — rejected (breaks on any in-session edit; no path to re-anchoring).

### D4 — Gutter via `LineMarkerProvider` / `GutterIconRenderer`; list via `ToolWindowFactory`
Standard trusted APIs. The tool window subscribes to the store and renders comments grouped by file with navigate-to-line (open file + move caret to start line). *Why:* these are the documented, stable extension points and match the architecture doc's reuse list.

### D5 — Exporter is a pure function `ReviewBatch → text`
Serialization (Claude `@path#Lstart-end` + body; markdown file) is isolated from delivery so the output feeds the written `REVIEW.md` today and the deferred live preview later, and so non-Claude exporters can slot in later behind the same seam. *Why:* keeps the agent-specific bit small and swappable; when the preview lands it reuses the exact exporter, so it can never diverge from the file.

### D6 — Delivery (MVP) = write `REVIEW.md`, then notify the user
`REVIEW.md` is written at the project base path on the local filesystem (the MVP targets a single project root — no worktree inference); for a remote agent the session's own sync carries it to the sandbox. The plugin then shows a notification that the file is ready. The user returns to their idle session and asks the agent to read it. *Why:* writing the file is the whole deliverable; the `@path#L` refs inside resolve natively for Claude, so a human typing `read REVIEW.md` is enough to close the loop. Deferring the typed delivery removes the terminal-widget dependency (and its targeting ambiguity) from the MVP without blocking the workflow. *Follow-on:* automatic relay — obtain the active terminal widget via `TerminalToolWindowManager` and send the instruction + Enter (falling back to the notification when no widget is available); and, later, an `ssh tmux send-keys` bring-your-own-terminal path.

### D7 — Comment subject is an open type; the MVP authors only the line/range scope
Model a comment's **subject** as an open type (per the architecture domain model): `Line` / `LineRange` (path + lines), `File` (path, no range), `Files` (several paths), `Project` (no path). The MVP **authors and exports only the line/range scope**; `File` (whole-file), `Project` (batch-level), and `Files` (multi-file) exist in the type but their authoring and export are deferred (see non-goals). *Why:* keeping the subject open costs almost nothing now (a sealed hierarchy with the other cases stubbed) and makes each deferred scope an additive case + render branch later, never a model rewrite — line/range alone is the irreducible review primitive. *Trade-off:* the type carries cases the MVP doesn't exercise — acceptable, and it keeps the domain model aligned with the architecture doc.

### D8 — Threading discipline
File I/O runs on a background thread (`Task.Backgroundable` / pooled executor). VFS refresh uses async refresh. PSI/document reads use read actions; store/UI mutations happen on the EDT. *Why:* the architecture doc's cross-cutting rule; blocking the EDT on I/O is the classic new-plugin freeze.

## Risks / Trade-offs

- **File-sync race** (REVIEW.md written locally but not yet on the sandbox when the agent reads it) → Out of Relay's hands in the MVP: sync is the session's responsibility and the user triggers submit, then reads the file deliberately. Becomes relevant only with the automatic-relay follow-on (mitigate with a brief wait or a poll in the typed command).
- **No persistence** → comments lost on restart → Acceptable per the idle-review workflow; model is persistence-ready (D2/D3).
- **Anchor drift from out-of-IDE edits** → a comment could point at moved lines if the agent edits after annotation → Mitigated by loop discipline (submit before resuming) now; programmatic re-anchoring is a follow-on. The `RangeMarker` covers in-IDE edits today.
- **Platform API churn** → mitigated by sticking to the trusted APIs in the architecture doc's reuse list and the plugin verifier from the template.

## Open Questions

- Build tool: scaffold with the Gradle template for the MVP; a Maven build was floated and is parked (the template is Gradle-only, so Maven would mean dropping it). Revisit only on a concrete need.
- Single-line reference rendering: `@path#L10` vs `@path#L10-10` — pick whichever Claude resolves most reliably during implementation; the spec allows either.
- Whether "Refresh & review" should also be wired to frame-activation refresh now or left manual — default manual for the MVP.
