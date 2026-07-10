# agent-event-gateway — delta spec

## ADDED Requirements

### Requirement: Launcher session registration
The gateway SHALL expose `POST /relay/v1/sessions` on its loopback interface, through
which a launcher (or a local custom agent acting as its own launcher) registers a session
before spawning it: `{ schema: 1, agent, kind (push, the default, or server with an
opaque endpoint), local_path, environment: local | docker | ssh:<host>, remote_path?,
capabilities?, session_id? }`. Registration requires no token — it is a localhost,
same-user call. On success the gateway SHALL assign and return a unique opaque
per-session id and store the registration; a registration that carries a prior
`session_id` SHALL rebind that existing registration (resume continuity) rather than
create a second entry. The gateway MUST reject with `409` a registration whose
`local_path` is not under any open project's content roots, so launchers can walk
multiple IDE descriptors to find the right IDE. Unknown agent strings SHALL be accepted
with their declared capabilities (defaulting to `turn_completed` only); registrations
naming a built-in adapter without declaring capabilities inherit that adapter's
capability set.

#### Scenario: Registration assigns a session id
- **WHEN** a launcher POSTs a valid registration for a `local_path` under an open project
- **THEN** the gateway responds with a unique per-session id and the session appears
  in the registry in the registered state

#### Scenario: Resume reuses the prior session
- **WHEN** a registration carries a `session_id` that matches an existing registration
- **THEN** the gateway rebinds that registration and returns its id rather than creating
  a second registry entry

#### Scenario: Wrong IDE rejected
- **WHEN** a registration's `local_path` is not under any project open in this IDE
- **THEN** the gateway responds `409` and stores nothing

#### Scenario: Server-kind session accepted as data
- **WHEN** a registration declares kind `server` with an `endpoint`
- **THEN** the registry stores the session with its kind and endpoint, and no outbound
  connection is attempted (connectors are out of scope)

### Requirement: HTTP event ingestion endpoint
The gateway SHALL accept lifecycle events at `POST /relay/v1/sessions/<id>/ingest/<agent>/
<native-event>` (raw native hook payloads for built-in adapters) and `POST
/relay/v1/sessions/<id>/events` (already-normalized events from custom agents), where
`<id>` is the per-session id from registration. The HTTP handler MUST accept POST requests
(overriding the built-in server's GET/HEAD default) and MUST hand validated events to a
transport-agnostic gateway service so the HTTP layer can later be replaced without
changing consumers.

#### Scenario: Native Claude payload accepted
- **WHEN** a request POSTs a Claude Code `Stop` hook payload to
  `/relay/v1/sessions/<id>/ingest/claude/stop`
- **THEN** the gateway normalizes it to `turn.completed` for that session and
  responds `2xx`

#### Scenario: Normalized custom-agent event accepted
- **WHEN** a request POSTs a valid `v1` normalized event to
  `/relay/v1/sessions/<id>/events`
- **THEN** the gateway processes it identically to an event produced by a built-in
  normalizer

#### Scenario: Handler never disturbs the agent or the IDE server
- **WHEN** a request carries a malformed or unparseable body
- **THEN** the gateway responds with a `4xx` status without throwing into the built-in
  server, and no registry state changes

### Requirement: Per-session addressing over a trusted transport
The gateway SHALL NOT perform application-level authentication. Each session is addressed
by the opaque per-session id in the request route; the id is a routing key, not a secret,
and the gateway MUST NOT rely on its confidentiality. Event bodies carry no session
identifier of their own — the route's id is the sole identifier, and an event can affect
only the session it addresses. The gateway's built-in web server SHALL bind the loopback
interface; remote sessions reach it only through a launcher-established transport (ssh
reverse tunnel), which is the trust boundary. An event whose id matches no registered
session MUST be acknowledged and dropped without changing any registry state.

#### Scenario: Event scoped to the addressed session
- **WHEN** an event for session A's id arrives while session B also exists
- **THEN** only session A's state can change

#### Scenario: Unknown session id dropped safely
- **WHEN** an event is posted to an id-scoped route matching no registration
- **THEN** the gateway responds without error and no registry state changes

### Requirement: Versioned normalized event schema
The gateway SHALL define a versioned event schema (`schema: 1`) with event types
`session.started`, `turn.started`, `turn.completed`, `needs.input` (`kind: permission |
idle | question`), and `session.ended`. The event vocabulary is what the gateway offers;
each session's declared capabilities state which events its adapter emits. The gateway
MUST ignore unknown fields and MUST acknowledge-and-drop unknown event types so older
plugin versions tolerate newer adapters.

#### Scenario: Unknown event type tolerated
- **WHEN** a request posts a `schema: 1` event of an unrecognized type to a session's route
- **THEN** the gateway responds `2xx` and registry state is unchanged

### Requirement: Session state machine
The registry SHALL derive session state exclusively from gateway events: registration →
registered; `session.started` and `turn.completed` → idle; `turn.started` → working;
`needs.input` → needs-input(kind); `session.ended` → ended. A `needs.input` state SHALL
be cleared by the next `turn.started` or `turn.completed` (the user answers in the
agent's own terminal; no IDE callback exists or is required). Registrations restored
after an IDE restart SHALL be in the unknown state until their next event. Registry
mutations and listener callbacks SHALL be dispatched on the EDT, published on a
MessageBus topic; consumers MUST NOT receive a storage handle.

#### Scenario: Needs-input cleared by the next turn
- **WHEN** a session in needs-input(permission) receives `turn.started`
- **THEN** its state becomes working and topic subscribers are notified on the EDT

#### Scenario: Resume refreshes a restored session
- **WHEN** a session restored in the unknown state receives `session.started` (e.g. a
  Claude resume)
- **THEN** its state becomes idle

### Requirement: Registrations persist; events and state do not
The gateway SHALL persist session registrations (id, agent, kind, paths, environment,
capabilities) across IDE restarts so a still-running agent stays addressable and
project-scoped, and SHALL restore them (state unknown) on startup, dropping ended
sessions. No secret is persisted — the id is a non-secret routing key. The gateway
SHALL NOT persist events or live state and MUST NOT attempt replay, polling, or
reconciliation beyond applying the next incoming event.

#### Scenario: Surviving session reports after restart
- **WHEN** the IDE restarts while a registered agent session keeps running, and that
  session later emits `turn.completed` to its original id-scoped route
- **THEN** the restored registration matches the event, the session leaves the unknown
  state, and the tool window shows it idle

#### Scenario: No state resurrection
- **WHEN** the IDE restarts
- **THEN** restored sessions show the unknown state (not their pre-restart state) until
  their next event

### Requirement: Gateway descriptor for launchers
On gateway start the plugin SHALL write a descriptor `~/.relay/gateway/<port>.json` (a
per-user location) containing the port, the IDE process pid, and an IDE identifier — no
secret — and SHALL remove it on shutdown. On startup the gateway SHALL sweep descriptors
whose pid is no longer alive, so crashed IDEs leave no stale descriptors behind. The
descriptor MUST stay on the IDE host only — nothing may place it inside a project
directory where file sync could carry it to a sandbox.

#### Scenario: Launcher discovers the gateway
- **WHEN** an external launcher starts while the IDE gateway is running
- **THEN** it obtains the port from the descriptor file without any IDE interaction

#### Scenario: Crash leftovers collected
- **WHEN** a gateway starts and finds a descriptor whose pid is not a live process
- **THEN** that descriptor is deleted
