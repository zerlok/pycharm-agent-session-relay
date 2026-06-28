## 1. Scaffold the plugin (IntelliJ Platform Plugin Template, Kotlin/Gradle)

- [x] 1.1 Bootstrap the Gradle build: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, and the Gradle wrapper (IntelliJ Platform Gradle Plugin 2.x; Maven is parked — see the design's open questions)
- [x] 1.2 Set `pluginGroup`/plugin ID to `io.github.zerlok.agentsessionrelay`, plugin name "Agent Session Relay", vendor, and target platform (PyCharm Community 2024.2, since-build 242)
- [x] 1.3 Create `src/main/resources/META-INF/plugin.xml` with name, description, and vendor (no terminal dependency yet — automatic relay is a follow-on)
- [x] 1.4 Create the Kotlin source root `src/main/kotlin/io/github/zerlok/agentsessionrelay/`
- [x] 1.5 Verify the skeleton builds: `./gradlew buildPlugin` produces the zip and `./gradlew verifyPlugin` reports **Compatible** (PC-242). `runIde` is a manual step — it needs a display.
- [x] 1.6 Add build/run/test commands + manual-install instructions to README (CI workflow deferred — scaffolded by hand, not from the GitHub template)

> **Ahead of plan:** a thin vertical slice of authoring (§3) and gutter display (§4.1) is already
> wired as a build smoke-test — hover a line → `+` gutter icon → inline comment popup. The popup's
> **Add** only logs + notifies; the real comment model/store (§2) and persistence are still to do.

## 2. Comment model and store

- [ ] 2.1 Define `ReviewComment` (id, subject, anchorText?, contextHash?, body, status) with an optional `RangeMarker`, where `subject` is an open type — Line/LineRange/File/Files/Project. The MVP authors only the line/range subject; model File/Files/Project now (stubbed) so adding them later is additive, even though authoring them is deferred
- [ ] 2.2 Implement a `Project`-scoped comment store service holding the pending batch with add/edit/delete and a change-listener mechanism
- [ ] 2.3 Implement context-hash computation over the surrounding lines at author time for line-anchored comments (no fuzzy re-anchoring yet)

## 3. Authoring comments

- [ ] 3.1 Implement the "Add review comment" `AnAction`: read active file + selection (fallback to caret line), capture range and anchor text
- [ ] 3.2 Show a popup text area to enter the body; on confirm, create the comment and a `RangeMarker`; on cancel, do nothing
- [ ] 3.3 Register the action with a default keybinding and add it to the editor popup menu
- [ ] 3.4 Allow authoring on any file (no dependency on VCS-changed status)

## 4. Displaying comments (gutter + tool window)

- [ ] 4.1 Implement gutter markers via `LineMarkerProvider`/`GutterIconRenderer` for commented line ranges, refreshing on store changes
- [ ] 4.2 Implement a `ToolWindowFactory` listing pending comments grouped by file (line range + body snippet)
- [ ] 4.3 Wire navigate-to-line (open/focus file, move caret to the comment's start line) on double-click
- [ ] 4.4 Add a delete action in both the gutter and the tool window; keep gutter/tool window in sync with the store

## 5. VFS refresh

- [ ] 5.1 Implement a "Refresh & review" action that triggers an async VFS refresh so edits written to disk (locally or synced from a sandbox) become visible (the change-view/diff capture-mode entry point is postponed)

## 6. Export

- [ ] 6.1 Implement the Exporter as a pure function `ReviewBatch -> text` (markdown), isolated from delivery
- [ ] 6.2 Render the line-anchored scope: `@path#Lstart-end` and the single-line form; handle the empty-batch case

## 7. Submit

- [ ] 7.1 On submit, write `REVIEW.md` at the project base path on a background thread (no-op + notify if the batch is empty; single project root — no worktree inference)
- [ ] 7.2 Notify the user that `REVIEW.md` is ready at the project root (no terminal interaction in this change)
- [ ] 7.3 Clear the pending batch (store, gutter, tool window) after a successful submit
- [ ] 7.4 Ensure submit file I/O runs off the EDT

## 8. Verification

- [ ] 8.1 Run `./gradlew test` and `./gradlew verifyPlugin`; fix any plugin-verifier issues
- [ ] 8.2 Manually verify the end-to-end flow in `runIde`: author line-anchored comments → see gutter/tool window → submit writes REVIEW.md and notifies → batch cleared
