# agent-adapters — delta spec

## ADDED Requirements

### Requirement: Claude Code adapter via launch-time settings injection
The plugin SHALL provide a Claude Code adapter as a static settings JSON fragment,
injectable at launch via `claude --settings <file-or-json>`, whose hook commands forward
the native payloads of `SessionStart`, `UserPromptSubmit`, `Stop`, `Notification`
(matchers `permission_prompt`, `idle_prompt`, `agent_needs_input`), and `SessionEnd` to
the gateway's `/relay/v1/sessions/<id>/ingest/claude/<event>` routes. Hook commands MUST
read the gateway address and session id exclusively from `$RELAY_URL` and `$RELAY_SESSION`
at execution time, MUST use a short timeout, and MUST exit successfully even when the
gateway is unreachable so the agent's own flow is never blocked. Before the adapter ships, the
implementation MUST verify empirically (against a pinned Claude Code version) that
`--settings` hooks merge additively with the user's own hooks rather than replacing
them, and record the finding in the adapter contract doc; if it replaces, the documented
fallback is a launcher-side merged settings file (still zero mutation of user files).

#### Scenario: Full lifecycle relayed
- **WHEN** a Claude Code session launched with the injected settings starts, receives a
  prompt, requests permission, finishes the turn, and ends
- **THEN** the gateway receives `session.started`, `turn.started`,
  `needs.input(permission)`, `turn.completed`, and `session.ended` for that session

#### Scenario: Gateway down does not disturb the agent
- **WHEN** a hook command runs while the IDE gateway is unreachable
- **THEN** the command times out quickly, exits zero, and the Claude session continues
  unaffected

### Requirement: Adapters are environment-blind
Adapter configuration SHALL depend on exactly two injected values — `$RELAY_URL` (the
gateway base URL as reachable from the agent's environment) and `$RELAY_SESSION` (the
non-secret per-session id) — and nothing else about the host, project, or user. Composing a
`$RELAY_URL` that routes to the IDE is the launcher's responsibility (local: the IDE
port directly; docker: a host-gateway hostname or host networking; remote: an ssh
reverse tunnel), as is delivering both variables into the environment the agent's hook
commands actually inherit (e.g. into the tmux session it creates). Adapter content MUST
be identical across all environments.

#### Scenario: Same adapter works remotely
- **WHEN** a launcher registers a session, opens a reverse tunnel, and spawns a remote
  sandbox agent with `RELAY_URL` and `RELAY_SESSION` exported into its tmux environment
- **THEN** the unmodified adapter delivers events to the IDE gateway exactly as a local
  session would

### Requirement: No local data crosses the trust boundary
Beyond `$RELAY_URL` and `$RELAY_SESSION`, adapter injection MUST NOT carry any local
information into the agent's environment — no local paths, no user identifiers, no
credentials. Everything the IDE needs to know about a session (local project path,
environment, remote↔local path mapping) travels launcher → gateway on the IDE host via
registration, never through the sandbox.

#### Scenario: Sandbox sees only the two variables
- **WHEN** a launcher prepares a remote session's environment and adapter injection
- **THEN** the only Relay-originated values present in the sandbox are `RELAY_URL` and
  `RELAY_SESSION`

### Requirement: User agent settings are never modified
Relay SHALL NOT write to, patch, or delete any user, global, or project agent
configuration file (e.g. `~/.claude/settings.json`, `~/.codex/config.toml`,
`.claude/settings.json`). All built-in adapter injection MUST be per-invocation
(CLI flags or environment of the spawned process). Sessions launched without a
Relay-aware launcher are consequently not observed unless the user wires an adapter to
the gateway themselves.

#### Scenario: No config mutation on launch
- **WHEN** a Relay-aware launcher spawns an agent session with adapter injection
- **THEN** no user, global, or project agent configuration file is created or modified

### Requirement: Gateway-side normalization per agent
Normalization from native payloads to the versioned event schema SHALL live in plugin
code (one normalizer per agent), not in adapter shell commands. The Claude Code
normalizer SHALL map `SessionStart → session.started`, `UserPromptSubmit →
turn.started`, `Stop → turn.completed`, `Notification` by matcher (`permission_prompt →
permission`, `idle_prompt → idle`, `agent_needs_input → question`), and `SessionEnd →
session.ended`; the Claude adapter's capability set declares all of `turn_started`,
`turn_completed`, `needs_input`, `session_end`. Normalizers MUST validate payloads
strictly and drop events they cannot map.

#### Scenario: Claude Notification mapped by matcher
- **WHEN** a native Claude `Notification` payload with matcher `permission_prompt`
  arrives at the ingest route
- **THEN** the normalizer emits `needs.input` with kind `permission` for that session

#### Scenario: Unmappable payload dropped safely
- **WHEN** a native payload arrives that the agent's normalizer cannot map to a `v1`
  event
- **THEN** the gateway responds without error and no registry state changes

### Requirement: Documented contract for other agents and launchers
The plugin SHALL document the integration contract: the registration handshake and
normalized `/relay/v1/sessions/<id>/events` API for custom agents; the launch-time
injection path per
surveyed harness (Codex `-c notify=[…]` single-slot caveat, Gemini and Cursor
project-level hook files, OpenCode project plugin) with each harness's capability
asymmetries; launcher duties (descriptor discovery with pid check, registration,
composing `$RELAY_URL` per environment, exporting both variables into the spawned
process's environment — including tmux sessions — and opening the reverse tunnel); and
the trust model (no application auth; loopback bind plus ssh-tunnel transport as the
boundary; optional `0600` Unix-domain-socket forward on shared remote hosts; events are
non-executable).

#### Scenario: Custom agent integrates from docs alone
- **WHEN** a developer follows the documented contract to wire a custom agent (register
  via the descriptor, then a `curl` call on its turn boundary)
- **THEN** the session registers and updates in the IDE with no plugin code changes
