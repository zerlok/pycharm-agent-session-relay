# agent-event-gateway — delta spec

## ADDED Requirements

### Requirement: Normalized event webhook
The gateway SHALL accept lifecycle events at `POST
/relay/v1/sessions/<id>/events/<type>` on its loopback interface, where `<id>` is the
opaque per-session id assigned at launch and `<type>` is a normalized event name. A
zero-body request SHALL be a complete event; an optional query parameter `kind`
(`permission | idle | question`) SHALL refine `needs.input`, and an optional JSON body MAY
carry `summary` or `reason` (ignored when absent). The HTTP handler MUST accept POST
(overriding the built-in server's GET/HEAD default) and MUST hand a validated normalized
event to a transport-agnostic gateway service so the HTTP layer can be replaced without
changing consumers. The gateway SHALL NOT expose a per-agent native-payload ingest route
and SHALL contain no agent-specific normalization.

#### Scenario: Zero-body event accepted
- **WHEN** a hook POSTs with an empty body to `/relay/v1/sessions/<id>/events/turn.completed`
- **THEN** the gateway records `turn.completed` for that session and responds `2xx`

#### Scenario: Needs-input kind carried in the query
- **WHEN** a hook POSTs to `/relay/v1/sessions/<id>/events/needs.input?kind=permission`
- **THEN** the gateway records `needs.input` with kind `permission` for that session

#### Scenario: Handler never disturbs the agent or the IDE server
- **WHEN** a request carries a malformed body or unparseable parameters
- **THEN** the gateway responds `4xx` without throwing into the built-in server, and no
  registry state changes

### Requirement: Per-session addressing over a trusted transport
The gateway SHALL NOT perform application-level authentication. Each session is addressed
by the opaque per-session id in the request route; the id is a routing key, not a secret,
and the gateway MUST NOT rely on its confidentiality. An event can affect only the session
its route addresses. The gateway's built-in web server SHALL bind the loopback interface;
remote sessions reach it only through a user-established transport (ssh reverse tunnel),
which is the trust boundary. An event whose id matches no registered session MUST be
acknowledged and dropped without changing any registry state. Received events MUST be
non-executable — a request may change registry state or raise a notification and nothing
the IDE executes.

#### Scenario: Event scoped to the addressed session
- **WHEN** an event for session A's id arrives while session B also exists
- **THEN** only session A's state can change

#### Scenario: Unknown session id dropped safely
- **WHEN** an event is posted to an id-scoped route matching no registration
- **THEN** the gateway responds without error and no registry state changes

### Requirement: Versioned normalized event schema
The gateway SHALL define a versioned event schema (`schema: 1`) with event types
`session.started`, `turn.started`, `turn.completed`, `needs.input` (`kind: permission |
idle | question`), and `session.ended`. The gateway MUST ignore unknown body fields and
MUST acknowledge-and-drop unknown event types so older plugin versions tolerate newer
hooks.

#### Scenario: Unknown event type tolerated
- **WHEN** a request posts an unrecognized `<type>` to a session's events route
- **THEN** the gateway responds `2xx` and registry state is unchanged

### Requirement: In-process session registration
The plugin SHALL register a session in-process when it launches it — assigning a unique
opaque per-session id, recording the launching project, the agent label, the environment,
and the start-script reference — without any HTTP registration endpoint or gateway
descriptor file. The assigned id SHALL be exported to the launched process as
`AGENT_SESSION_RELAY_ID` and SHALL be the sole identifier the gateway routes events by.

#### Scenario: Launch assigns a session id
- **WHEN** the user launches a start-script config for the open project
- **THEN** the plugin assigns a unique id, the session appears in the registry in the
  registered state tagged with that project, and `AGENT_SESSION_RELAY_ID` carries the id
  into the launched terminal

### Requirement: Session state machine
The registry SHALL derive session state exclusively from gateway events: registration →
registered; `session.started` and `turn.completed` → idle; `turn.started` → working;
`needs.input` → needs-input(kind); `session.ended` → ended. A `needs.input` state SHALL be
cleared by the next `turn.started` or `turn.completed` (the user answers in the agent's own
terminal; no IDE callback exists or is required). Registrations restored after an IDE
restart SHALL be in the unknown state until their next event. Registry mutations and
listener callbacks SHALL be dispatched on the EDT and published on a MessageBus topic;
consumers MUST NOT receive a storage handle.

#### Scenario: Needs-input cleared by the next turn
- **WHEN** a session in needs-input(permission) receives `turn.started`
- **THEN** its state becomes working and topic subscribers are notified on the EDT

#### Scenario: Resume refreshes a restored session
- **WHEN** a session restored in the unknown state receives `session.started`
- **THEN** its state becomes idle

### Requirement: Registrations persist; events and state do not
The gateway SHALL persist session registrations (id, agent label, environment, project,
start-script reference, declared capabilities) across IDE restarts so a still-running agent
stays addressable and project-scoped, and SHALL restore them (state unknown) on startup,
dropping ended sessions. No secret is persisted — the id is a non-secret routing key. The
gateway SHALL NOT persist events or live state and MUST NOT attempt replay, polling, or
reconciliation beyond applying the next incoming event.

#### Scenario: Surviving session reports after restart
- **WHEN** the IDE restarts while a registered agent keeps running, and that session later
  posts `turn.completed` to its original id-scoped route
- **THEN** the restored registration matches the event, the session leaves the unknown
  state, and the tool window shows it idle

#### Scenario: No state resurrection
- **WHEN** the IDE restarts
- **THEN** restored sessions show the unknown state (not their pre-restart state) until
  their next event
