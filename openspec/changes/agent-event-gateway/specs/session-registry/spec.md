# session-registry — delta spec

## ADDED Requirements

### Requirement: Sessions tool window scoped to the current project
The plugin SHALL provide a Sessions tool window listing registered sessions whose `cwd`
lies under one of the project's content roots. Each entry SHALL show the agent kind, the
environment (`local` / `docker` / `ssh:<host>`), and the current state (working / idle /
needs input / ended). Sessions belonging to other projects MUST NOT appear.

#### Scenario: Only this project's sessions listed
- **WHEN** two sessions are registered, one with `cwd` under the open project and one
  under an unrelated path
- **THEN** the tool window lists only the first session

#### Scenario: Entry shows identity and state
- **WHEN** a remote Claude session registered via `ssh:devbox` is idle
- **THEN** its entry shows the agent (claude), the environment badge `ssh:devbox`, and
  the idle state

### Requirement: Live state updates via the registry topic
The tool window SHALL render exclusively from the registry service via its MessageBus
topic, updating on the EDT as events arrive, and SHALL hold no storage handle. Sessions
whose adapter did not declare a capability MUST be rendered without implying that signal
(e.g. show last-turn-completed time rather than "not waiting for input" when
`needs_input` is undeclared).

#### Scenario: State change renders without user action
- **WHEN** a listed session transitions from working to needs-input(permission)
- **THEN** the tool window entry updates to needs-input without a manual refresh

#### Scenario: Capability-honest rendering
- **WHEN** a session registered with `needs_input: false` is idle
- **THEN** its entry presents turn-completion info only and does not display a
  needs-input indicator state

### Requirement: Attach action
Each session entry SHALL offer an attach action that opens an IDE terminal tab running
the session's `attach_hint` command (e.g. `claude-connect <sandbox>`). The action MUST be
disabled when the session carries no `attach_hint`.

#### Scenario: Attach opens a terminal
- **WHEN** the user invokes attach on a session whose `attach_hint` is
  `claude-connect devbox`
- **THEN** a terminal tab opens in the project and runs that command

#### Scenario: No hint, no action
- **WHEN** a session registered without an `attach_hint`
- **THEN** its attach action is disabled

### Requirement: Ended sessions remain visible until dismissed
A session that reaches the ended state SHALL remain listed, marked ended, until the user
dismisses it or the IDE restarts (the registry is not persisted).

#### Scenario: Ended session dismissable
- **WHEN** a session ends and the user dismisses its entry
- **THEN** the entry disappears from the tool window and the registry no longer reports it
