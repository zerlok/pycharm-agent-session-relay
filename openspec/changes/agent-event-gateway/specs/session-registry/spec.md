# session-registry — delta spec

## ADDED Requirements

### Requirement: Agent Sessions tool window scoped to the current project
The plugin SHALL provide a dedicated **Agent Sessions** tool window listing the sessions
launched for the open project. Each entry SHALL show the agent label, the environment
(`local` / `docker` / `ssh` / `custom`), the current state (registered / working / idle /
needs input / ended / unknown), and the time of the session's last received event. Sessions
launched from other projects MUST NOT appear.

#### Scenario: Only this project's sessions listed
- **WHEN** two sessions are launched, one from the open project and one from another open
  project
- **THEN** the tool window lists only the session launched from the open project

### Requirement: The tool window hosts each session's terminal
The Agent Sessions tool window SHALL host the terminal of each live session it launched
(via the platform Terminal integration), so the user interacts with the agent's own TUI
inside the tool window. Selecting or activating a session SHALL reveal its terminal.

#### Scenario: Session terminal reachable from its entry
- **WHEN** the user activates a listed live session
- **THEN** that session's terminal is shown in the Agent Sessions tool window

### Requirement: Live, capability-honest state updates via the registry topic
The tool window SHALL render exclusively from the registry service via its MessageBus topic,
updating on the EDT as events arrive, and SHALL hold no storage handle. Rendering MUST be
capability-honest: no needs-input indicator for a session whose config did not declare
`needs_input`, no working state without `turn_started` — the last-event timestamp carries
the residual signal.

#### Scenario: State change renders without user action
- **WHEN** a listed session transitions from working to needs-input(permission)
- **THEN** the tool window entry updates to needs-input without a manual refresh

#### Scenario: Capability-honest rendering
- **WHEN** a session whose config declares no `needs_input` capability is idle
- **THEN** its entry presents turn-completion info only and shows no needs-input indicator

### Requirement: Restored sessions render as unknown
Sessions restored from persisted registrations after an IDE restart SHALL be rendered in the
unknown state (no live signal since IDE start) with their last-event time, until their next
event, rather than implying a live state the plugin cannot know. Reviving a restored
session's terminal is out of scope for this change.

#### Scenario: Unknown until proven alive
- **WHEN** the IDE restarts with a persisted registration whose agent has not yet emitted an
  event
- **THEN** the entry shows the unknown state and its last-event time, and updates on the
  session's next event

### Requirement: Any session is dismissable, locally only
The user SHALL be able to dismiss any session entry at any time (not only ended ones — e.g.
a stale session whose agent died without `session.ended`). Dismissal SHALL remove the
session from the registry and its persistence and MUST have no effect on the agent or its
environment: the plugin observes sessions; it does not own or reclaim the resources behind
them.

#### Scenario: Stale session dismissed
- **WHEN** the user dismisses a session stuck in the unknown state
- **THEN** the entry disappears, the registration is dropped, and nothing is sent to the
  agent or its host
