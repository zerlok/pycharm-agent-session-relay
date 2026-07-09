# session-registry — delta spec

## ADDED Requirements

### Requirement: Sessions tool window scoped to the current project
The plugin SHALL provide a Sessions tool window listing registered sessions whose
registered `local_path` lies under one of the project's content roots. Each entry SHALL
show the agent kind, the environment (`local` / `docker` / `ssh:<host>`), the current
state (registered / working / idle / needs input / ended / unknown), and the time of the
session's last received event. Sessions belonging to other projects MUST NOT appear.

#### Scenario: Only this project's sessions listed
- **WHEN** two sessions are registered, one with `local_path` under the open project and
  one under an unrelated path
- **THEN** the tool window lists only the first session

#### Scenario: Remote session scoped by its local path
- **WHEN** a remote session registered with environment `ssh:devbox` and a `local_path`
  under the open project emits events whose native payload cwd is a sandbox-side path
- **THEN** the session appears in this project's tool window with the `ssh:devbox` badge

### Requirement: Live state updates via the registry topic
The tool window SHALL render exclusively from the registry service via its MessageBus
topic, updating on the EDT as events arrive, and SHALL hold no storage handle. Rendering
MUST be capability-honest: no needs-input indicator for sessions whose adapter did not
declare `needs_input`, no working state without `turn_started` — the last-event timestamp
carries the residual signal.

#### Scenario: State change renders without user action
- **WHEN** a listed session transitions from working to needs-input(permission)
- **THEN** the tool window entry updates to needs-input without a manual refresh

#### Scenario: Capability-honest rendering
- **WHEN** a session registered with `needs_input: false` is idle
- **THEN** its entry presents turn-completion info only and does not display a
  needs-input indicator state

### Requirement: Restored sessions render as unknown
Sessions restored from persisted registrations after an IDE restart SHALL be rendered in
the unknown state (no live signal since IDE start) until their next event, rather than
implying a live state the plugin cannot know.

#### Scenario: Unknown until proven alive
- **WHEN** the IDE restarts with a persisted registration whose agent has not yet emitted
  an event
- **THEN** the entry shows the unknown state and its last-event time, and updates on the
  session's next event

### Requirement: Any session is dismissable, locally only
The user SHALL be able to dismiss any session entry at any time (not only ended ones —
e.g. a stale session whose agent died without `session.ended`). Dismissal SHALL remove
the session from the registry and its persistence and MUST have no effect on the agent
or its environment: the plugin observes sessions; it does not own or reclaim the
resources behind them.

#### Scenario: Stale session dismissed
- **WHEN** the user dismisses a session stuck in the unknown state
- **THEN** the entry disappears from the tool window, the registration is dropped, and
  nothing is sent to the agent or its host

#### Scenario: Ended session dismissed
- **WHEN** a session ends and the user dismisses its entry
- **THEN** the entry disappears and the registry no longer reports it
