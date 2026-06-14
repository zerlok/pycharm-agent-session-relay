## 1. Scaffold the plugin (IntelliJ Platform Plugin Template, Kotlin/Gradle)

- [ ] 1.1 Bootstrap from the IntelliJ Platform Plugin Template: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and the Gradle wrapper
- [ ] 1.2 Set `pluginGroup`/plugin ID to `io.github.zerlok.agentsessionrelay`, plugin name "Agent Session Relay", vendor, and a target IntelliJ platform/since-build
- [ ] 1.3 Create `src/main/resources/META-INF/plugin.xml` with name, description, vendor, and a dependency on `org.jetbrains.plugins.terminal`
- [ ] 1.4 Create the Kotlin source root `src/main/kotlin/io/github/zerlok/agentsessionrelay/`
- [ ] 1.5 Verify the skeleton builds and launches: `./gradlew buildPlugin` and `./gradlew runIde`
- [ ] 1.6 Add build/run/test commands to README; keep the GitHub CI workflow from the template

## 2. Comment model and store

- [ ] 2.1 Define `ReviewComment` (id, relativePath, startLine, endLine, anchorText, contextHash, body, status) and a `RangeMarker` reference
- [ ] 2.2 Implement a `Project`-scoped comment store service holding the pending batch with add/edit/delete and a change-listener mechanism
- [ ] 2.3 Implement context-hash computation over the surrounding lines at author time (no fuzzy re-anchoring yet)

## 3. Authoring comments

- [ ] 3.1 Implement the "Add review comment" `AnAction`: read active file + selection (fallback to caret line), capture range and anchor text
- [ ] 3.2 Show a popup text area to enter the body; on confirm, create the comment and a `RangeMarker`; on cancel, do nothing
- [ ] 3.3 Register the action with a default keybinding and add it to the editor popup menu
- [ ] 3.4 Allow authoring on any file (no dependency on VCS-changed status)

## 4. Displaying comments (gutter + tool window)

- [ ] 4.1 Implement gutter markers via `LineMarkerProvider`/`GutterIconRenderer` for commented line ranges, refreshing on store changes
- [ ] 4.2 Implement a `ToolWindowFactory` listing pending comments grouped by file, showing line range + body snippet
- [ ] 4.3 Wire navigate-to-line (open/focus file, move caret to the comment's start line) on double-click
- [ ] 4.4 Add edit and delete actions in both the gutter and the tool window; keep gutter/tool window in sync with the store

## 5. Change-view entry and VFS refresh

- [ ] 5.1 Implement a "Refresh & review" action that triggers an async VFS refresh so mutagen-synced edits become visible
- [ ] 5.2 Surface the change-view as the convenient entry point (use `ChangeListManager` for changed files) without making it a precondition for authoring

## 6. Export and preview

- [ ] 6.1 Implement the Exporter as a pure function `List<ReviewComment> -> markdown` using `@path#Lstart-end` references + body
- [ ] 6.2 Render the single-line reference form and handle the empty-batch case
- [ ] 6.3 Implement a live preview (in the tool window) driven by the same Exporter so preview and file never diverge

## 7. Submit / relay

- [ ] 7.1 On submit, write `REVIEW.md` at the project base path on a background thread (no-op + notify if the batch is empty)
- [ ] 7.2 Obtain the active terminal widget via `TerminalToolWindowManager` and type the instruction line referencing `REVIEW.md` + Enter
- [ ] 7.3 If no terminal widget is available, still write `REVIEW.md` and notify the user to read it manually
- [ ] 7.4 Clear the pending batch (store, gutter, tool window) after a successful submit
- [ ] 7.5 Ensure all submit I/O and terminal interaction run off the EDT

## 8. Verification

- [ ] 8.1 Run `./gradlew test` and `./gradlew verifyPlugin`; fix any plugin-verifier issues
- [ ] 8.2 Manually verify the end-to-end flow in `runIde`: author comments → see gutter/tool window → preview → submit writes REVIEW.md and types into the terminal → batch cleared
