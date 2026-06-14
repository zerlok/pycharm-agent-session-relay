## Why

When an agent edits files remotely (tmux on a sandbox) and they sync back locally via mutagen, PyCharm shows the diff but offers no way to attach several line-anchored comments and hand them to the agent as one batch. Today the developer retypes feedback into the terminal, losing the file/line anchoring the agent could otherwise resolve. This change delivers the first shippable slice of Agent Session Relay: review the agent's changes in the IDE and relay batched, line-anchored comments straight into its idle session.

## What Changes

- **Bootstrap the plugin** from the IntelliJ Platform Plugin Template (Kotlin, Gradle, `io.github.zerlok.agentsessionrelay`) so there is a buildable, runnable (`runIde`) plugin to host the feature.
- **Author comments**: a keybound editor action grabs the current file + selection range and opens a popup to capture a comment, stored as a line-anchored model object (path, line range, anchor text, context hash, body).
- **See comments**: gutter icons mark commented lines; a tool window lists pending comments grouped by file with navigate-to-line and a live preview of the export.
- **Manage comments**: edit and delete pending comments from the gutter or the tool window.
- **Submit/relay**: serialize the batch to `REVIEW.md` in Claude's native reference format (`@path#Lstart-end` + body), let mutagen sync it, then type `read REVIEW.md and address the comments` into the active agent terminal widget so the idle agent resumes.
- Refresh the IDE's view of synced files (VFS refresh) so agent edits show up before review.

Out of scope for this change (follow-on): the terminal launcher, persistence across IDE restarts, multi-session/worktree-aware delivery, `ssh + tmux send-keys` fallback, remote plan capture, and non-Claude exporters.

## Capabilities

### New Capabilities
- `review-annotation`: Create, display, edit, and delete line-anchored comments on any file in the project — keybound authoring on a selection, gutter markers, and a tool window listing comments grouped by file with navigate-to-line.
- `review-delivery`: Batch the pending comments, export them to `REVIEW.md` in Claude-format markdown (`@path#L` references), preview the export, and relay it into the active agent terminal widget so the idle session resumes.

### Modified Capabilities
<!-- None — this is the first change; no existing specs. -->

## Impact

- **New project scaffolding**: Gradle build (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, wrapper), `src/main/kotlin`, `src/main/resources/META-INF/plugin.xml`, plugin ID `io.github.zerlok.agentsessionrelay`.
- **IntelliJ Platform APIs**: editor actions/keymap, `RangeMarker`, `GutterIconRenderer`/`LineMarkerProvider`, `ToolWindowFactory`, `ChangeListManager` (change-view entry), `LocalFileSystem`/`VirtualFileManager` (VFS refresh), `org.jetbrains.plugins.terminal` / `TerminalToolWindowManager` (relay channel).
- **Filesystem**: writes `REVIEW.md` at the project/worktree root (carried to the sandbox by the existing mutagen session — no git transport).
- **No runtime services or external deps** beyond the IntelliJ Platform; relies on the user's existing mutagen + tmux + agent CLI setup.
- **README**: add build/run/test commands once the Gradle template lands.
