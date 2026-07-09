# agent-event-gateway — delta spec

## ADDED Requirements

### Requirement: HTTP event ingestion endpoint
The plugin SHALL expose an HTTP endpoint on the IDE's built-in web server that accepts
agent lifecycle events: `POST /relay/v1/ingest/<agent>/<native-event>` for raw native
hook payloads of built-in adapters, and `POST /relay/v1/events` for already-normalized
events from custom agents. The handler MUST hand validated events to a transport-agnostic
gateway service so the HTTP layer can later be replaced without changing consumers.

#### Scenario: Native Claude payload accepted
- **WHEN** a request POSTs a Claude Code `Stop` hook payload to
  `/relay/v1/ingest/claude/stop` with a valid bearer token
- **THEN** the gateway normalizes it to a `turn.completed` event and responds `2xx`

#### Scenario: Normalized custom-agent event accepted
- **WHEN** a request POSTs a valid `v1` normalized event to `/relay/v1/events` with a
  valid bearer token
- **THEN** the gateway processes it identically to an event produced by a built-in
  normalizer

#### Scenario: Handler never disturbs the agent or the IDE server
- **WHEN** a request carries a malformed or unparseable body
- **THEN** the gateway responds with a `4xx` status without throwing into the built-in
  server, and no registry state changes

### Requirement: Bearer-token authentication
Every gateway route SHALL require a bearer token. The gateway MUST generate a random
token per gateway instance at startup and MUST reject requests without a valid token
before any parsing of the body.

#### Scenario: Missing or wrong token rejected
- **WHEN** a request arrives without an `Authorization` header or with a token that does
  not match the current gateway token
- **THEN** the gateway responds `401` and no event is processed

### Requirement: Versioned normalized event schema
The gateway SHALL define a versioned event schema (`schema: 1`) with event types
`session.registered`, `turn.completed`, `needs.input`, and `session.ended`.
`session.registered` MUST carry `session_id`, `agent`, `cwd`, `environment`
(`local` | `docker` | `ssh:<host>`), an optional `attach_hint`, an optional session kind
(`push`, the default, or `server` with an opaque `endpoint`), and a `capabilities`
declaration. The gateway MUST ignore unknown fields and MUST acknowledge-and-drop unknown
event types so older plugin versions tolerate newer adapters.

#### Scenario: Unknown event type tolerated
- **WHEN** an authenticated request posts a `schema: 1` event of an unrecognized type
- **THEN** the gateway responds `2xx` and registry state is unchanged

#### Scenario: Server-kind session accepted as data
- **WHEN** a `session.registered` event declares kind `server` with an `endpoint`
- **THEN** the registry stores the session with its kind and endpoint, and no outbound
  connection is attempted (connectors are out of scope)

### Requirement: In-memory session registry
The gateway SHALL maintain an application-level, in-memory session registry keyed by
`(agent, session_id)`, updated only via gateway events, with registry mutations and
listener callbacks dispatched on the EDT. Lifecycle events SHALL drive the session state
machine: `session.registered` → working/idle, `turn.completed` → idle, `needs.input` →
needs-input (with kind), `session.ended` → ended. Registry changes MUST be published on a
MessageBus topic; consumers MUST NOT receive a storage handle.

#### Scenario: Registration is idempotent
- **WHEN** a `session.registered` event arrives for an `(agent, session_id)` already in
  the registry
- **THEN** the existing entry is updated in place and a change event is published, not a
  duplicate entry

#### Scenario: State transition published
- **WHEN** a `needs.input` event with kind `permission` arrives for a registered session
- **THEN** the session's state becomes needs-input(permission) and topic subscribers are
  notified on the EDT

### Requirement: Fire-and-forget semantics
The gateway SHALL NOT persist events or registry state. After an IDE restart the registry
MUST be empty until sessions re-register; the gateway MUST NOT attempt replay, polling, or
reconciliation from any on-disk state.

#### Scenario: Restart clears the registry
- **WHEN** the IDE restarts while agent sessions are still running elsewhere
- **THEN** the registry is empty until a session re-registers (e.g. the user re-attaches
  via a Relay-aware launcher)

### Requirement: Launcher discovery descriptor
On gateway start the plugin SHALL write a descriptor file `~/.relay/gateway/<port>.json`
(permissions `0600`) containing at least the port and current token, and SHALL remove it
on gateway shutdown. The descriptor MUST stay on the IDE host only — nothing in the
gateway or adapters may place the token inside a project directory where file sync could
carry it to a sandbox.

#### Scenario: Launcher reads the descriptor
- **WHEN** an external launcher starts while the IDE gateway is running
- **THEN** it can obtain the current port and token from the descriptor file without any
  IDE interaction

#### Scenario: Stale descriptor removed
- **WHEN** the gateway shuts down cleanly
- **THEN** its descriptor file no longer exists
