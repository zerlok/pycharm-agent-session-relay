## Why

When an agent edits files and they land in the local working tree (locally, or synced back from a remote sandbox), PyCharm shows the diff but offers no way to attach several line-anchored comments and hand them to the agent as one batch. Today the developer retypes feedback into the terminal, losing the file/line anchoring the agent could otherwise resolve. This change delivers the first shippable slice of Agent Session Relay: review the agent's changes in the IDE and hand it batched, line-anchored comments as a single `REVIEW.md`.

The simplest environment is **local-only** (agent and IDE on the same host, no file sync). A **remote** agent (e.g. tmux on a sandbox, files synced to the local host via mutagen) is the more complex case; Relay treats the local working tree and a local `REVIEW.md` as its only surface and leaves cross-host file sync to the session itself.

## What Changes

- **Bootstrap the plugin** from the IntelliJ Platform Plugin Template (Kotlin, Gradle, `io.github.zerlok.agentsessionrelay`) so there is a buildable, runnable (`runIde`) plugin to host the feature.
- **Author comments** at three subject scopes: a keybound editor action grabs the current file + selection range and opens a popup to capture a comment, stored against a subject (path, line range, anchor text, context hash, body). Comments may also be anchored to a **whole file** (no line range) or to the **batch/project** (detached from any file — general feedback). The comment model leaves the subject open so multi-file subjects are not foreclosed, but authoring them is deferred (see out of scope).
- **See comments**: gutter icons mark commented lines; a tool window lists pending comments grouped by file (with a group for batch-level comments) with navigate-to-line and a live preview of the export.
- **Manage comments**: edit and delete pending comments from the gutter or the tool window.
- **Submit**: serialize the batch to `REVIEW.md` at the project root in Claude's native reference format (`@path#Lstart-end` + body), then inform the user it is ready. The user returns to their idle session and asks the agent to read it. (Automatic relay into the terminal is deferred — see below.)
- **Refresh** the IDE's view of files on disk (VFS refresh) so agent edits (local, or synced in from a sandbox) show up before review.

The single entry point for authoring is **any open file + selection**. Richer **capture modes** (the changed-files diff view, a plan file) are a pluggable seam in the architecture but are **postponed** — see out of scope.

### Canonical flow (the MVP happy path)

```
 0. agent edits files (locally, or synced back from a remote sandbox)
 1. Refresh & review     → VFS refresh so edits on disk show up
 2. open a file, select lines, comment → popup → stored comment (or whole-file / batch-level)
 3. see / edit / delete   → gutter icons + tool window, kept in sync with the store
 4. open tool window      → comments grouped by file; live REVIEW.md preview; add more
 5. press Submit          → write REVIEW.md at the project root; notify it is ready
 6. (the user returns to the idle session and asks the agent to read REVIEW.md)
```

### Out of scope (follow-on changes, each layered on the same core)

- **Capture modes beyond the open-file base** — the changed-files diff / change-view entry point and the plan-file (`.agent/plan.md`) entry. The seam exists in the architecture; these entry points are postponed.
- **Multi-file comments** — authoring one comment that spans several files. The model allows it; the authoring/UX is deferred.
- **Multi-session / worktree-aware** delivery + session registry. The MVP targets a single project root and makes no worktree assumptions.
- **Automatic relay into the terminal** — typing `read REVIEW.md …` into the active agent terminal widget via `TerminalToolWindowManager`. The MVP stops at writing `REVIEW.md`; this lowers MVP scope without blocking the workflow.
- Terminal **launcher** (open a terminal, run a per-profile launch command).
- **Persistence** across IDE restarts (`PersistentStateComponent` + re-anchoring).
- `ssh + tmux send-keys` delivery fallback (bring-your-own-terminal / headless).
- Remote **plan capture** via a sandbox Claude Code hook → `.agent/plan.md`.
- **Non-Claude exporters** (the Exporter seam exists; only the Claude profile ships now).
- **Fuzzy re-anchoring** of comments drifted by out-of-IDE edits.

## Capabilities

### New Capabilities
- `review-annotation`: Create, display, edit, and delete line-anchored comments on any file in the project — keybound authoring on a selection, plus whole-file and batch-level (detached) comments, gutter markers, and a tool window listing comments grouped by file with navigate-to-line.
- `review-delivery`: Batch the pending comments, export them to `REVIEW.md` in Claude-format markdown (`@path#L` references), preview the export, and notify the user the file is ready to hand to the idle session.

### Modified Capabilities
<!-- None — this is the first change; no existing specs. -->

## Impact

- **New project scaffolding**: Gradle build (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, wrapper), `src/main/kotlin`, `src/main/resources/META-INF/plugin.xml`, plugin ID `io.github.zerlok.agentsessionrelay`.
- **IntelliJ Platform APIs**: editor actions/keymap, `RangeMarker`, `GutterIconRenderer`/`LineMarkerProvider`, `ToolWindowFactory`, `LocalFileSystem`/`VirtualFileManager` (VFS refresh). (`ChangeListManager` enters with the changed-files capture mode, and `org.jetbrains.plugins.terminal` / `TerminalToolWindowManager` with the automatic-relay follow-on — neither in this change.)
- **Filesystem**: writes `REVIEW.md` at the project root on the local filesystem. For a remote agent, the session's own file sync (e.g. mutagen) carries it to the sandbox — no git transport, and Relay does not manage that sync.
- **No runtime services or external deps** beyond the IntelliJ Platform; relies on the user's existing agent CLI setup (and, for remote agents, their own file sync + terminal).
- **README**: add build/run/test commands once the Gradle template lands.
