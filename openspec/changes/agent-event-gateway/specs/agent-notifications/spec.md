# agent-notifications — delta spec

## ADDED Requirements

### Requirement: Notification on turn completion
The plugin SHALL show an IDE notification when a session belonging to the open project
emits `turn.completed`, identifying the session (agent + environment). Events for
sessions of other projects MUST NOT notify in this project.

#### Scenario: Turn completion notifies
- **WHEN** a registered session of the open project emits `turn.completed`
- **THEN** an IDE notification appears naming that session

#### Scenario: Foreign project stays silent
- **WHEN** a session whose registered `local_path` is outside the open project emits
  `turn.completed`
- **THEN** no notification appears in this project

### Requirement: Notification on needs-input
The plugin SHALL show an IDE notification when a session of the open project emits
`needs.input`, stating the kind (permission / idle / question). Needs-input notifications
SHALL be visually distinct from turn-completion notifications (higher urgency).
Dismissing or ignoring a notification is local-only: the user responds in the agent's own
terminal, and the plugin MUST NOT send anything back to the agent.

#### Scenario: Permission request notifies with kind
- **WHEN** a session emits `needs.input` with kind `permission`
- **THEN** a notification appears indicating the session is waiting for a permission
  decision

### Requirement: Notification activation opens the Sessions tool window
Activating (clicking) a Relay session notification SHALL open the Sessions tool window
with the corresponding session visible.

#### Scenario: Click-through to the session
- **WHEN** the user clicks a needs-input notification for a session
- **THEN** the Sessions tool window opens showing that session's entry
