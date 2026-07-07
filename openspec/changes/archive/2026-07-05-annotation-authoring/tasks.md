## 1. Scaffold the plugin (IntelliJ Platform Plugin Template, Kotlin/Gradle)

- [x] 1.1 Bootstrap the Gradle build: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, wrapper (IntelliJ Platform Gradle Plugin 2.x)
- [x] 1.2 Set plugin ID `io.github.zerlok.agentsessionrelay`, name "Agent Session Relay", vendor, target PyCharm Community 2024.2 (since-build 242)
- [x] 1.3 Create `META-INF/plugin.xml` with name, description, vendor, `postStartupActivity`, and `notificationGroup`
- [x] 1.4 Create the Kotlin source root `src/main/kotlin/io/github/zerlok/agentsessionrelay/`
- [x] 1.5 Verify the skeleton builds (`buildPlugin`) and `verifyPlugin` reports Compatible (PC-242)
- [x] 1.6 Add build/run/test commands + manual-install instructions to README

## 2. Hover affordance

- [x] 2.1 Register editor mouse-motion/mouse listeners on the editor-factory multicaster, scoped to the project (`RelayHoverService` touched on project open by `RelayHoverInstaller`)
- [x] 2.2 Render a single "+" gutter icon (`AddCommentGutterIconRenderer`) on the hovered line; remove it on line change or mouse-exit; at most one at a time

## 3. Inline comment box (authoring)

- [x] 3.1 On "+" click, resolve the target range: current selection (trailing-line-trimmed) or the clicked line
- [x] 3.2 Open a full-width inline comment box as a block inlay below the range, with the range highlighted (`CommentDraft`)
- [x] 3.3 Route focus into the text area after layout (deferred `invokeLater` + `IdeFocusManager`)
- [x] 3.4 Bind keys on the focused text area: Enter/Shift+Enter newline, Ctrl/Cmd+Enter submit, Esc cancel; swallow editor click/drag over the box chrome
- [x] 3.5 Keep at most one box open at a time (`CommentDraftController`); opening another closes the previous
- [x] 3.6 On submit, close the box and surface the captured file + line range + body via a confirmation notification and log entry (store/export are later stages)

## 4. Verification

- [x] 4.1 `./gradlew buildPlugin` and `verifyPlugin` pass
- [x] 4.2 Manually verify in `runIde`: hover → "+" → inline box → type → Ctrl/Cmd+Enter submits (notification shown) / Esc cancels
