# agent-notifications — delta spec

## ADDED Requirements

### Requirement: Notification on turn completion
The plugin SHALL show an IDE notification when a session launched for the open project emits
`turn.completed`, identifying the session (agent label + environment). Events for sessions
of other projects MUST NOT notify in this project.

#### Scenario: Turn completion notifies
- **WHEN** a session of the open project emits `turn.completed`
- **THEN** an IDE notification appears naming that session

#### Scenario: Foreign project stays silent
- **WHEN** a session launched from another project emits `turn.completed`
- **THEN** no notification appears in this project

### Requirement: Notification on needs-input
The plugin SHALL show an IDE notification when a session of the open project emits
`needs.input`, stating the kind (permission / idle / question). Needs-input notifications
SHALL be visually distinct from turn-completion notifications (higher urgency). Dismissing or
ignoring a notification is local-only: the user responds in the agent's own terminal, and
the plugin MUST NOT send anything back to the agent.

#### Scenario: Permission request notifies with kind
- **WHEN** a session emits `needs.input` with kind `permission`
- **THEN** a notification appears indicating the session is waiting for a permission decision

### Requirement: Optional sound on turn completion and needs-input
The plugin SHALL play a short sound when a session of the open project emits `turn.completed`
or `needs.input`. The sound SHALL be independently toggleable per event in the Relay Settings
page, with both toggles ON by default, and MUST be suppressible entirely. The sound plays
IDE-side only; nothing is sent to the agent.

#### Scenario: Both sounds on by default
- **WHEN** a fresh install session emits `turn.completed` and later `needs.input`
- **THEN** a short sound plays on each event

#### Scenario: A disabled sound is silent
- **WHEN** the user turns off the turn-completion sound and a session emits `turn.completed`
- **THEN** the turn-completion notification still appears but no sound plays

### Requirement: Notification activation opens the Agent Sessions tool window
Activating (clicking) a Relay session notification SHALL open the Agent Sessions tool window
with the corresponding session visible.

#### Scenario: Click-through to the session
- **WHEN** the user clicks a needs-input notification for a session
- **THEN** the Agent Sessions tool window opens showing that session's entry
