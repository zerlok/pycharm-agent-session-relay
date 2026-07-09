# agent-adapters — delta spec

## ADDED Requirements

### Requirement: Claude Code adapter via launch-time settings injection
The plugin SHALL provide a Claude Code adapter as a static settings JSON fragment,
injectable at launch via `claude --settings <file-or-json>`, whose hook commands forward
the native payloads of `SessionStart`, `Stop`, `Notification` (matchers `idle_prompt`,
`agent_needs_input`, `permission_prompt`), and `SessionEnd` to the gateway's
`/relay/v1/ingest/claude/<event>` routes. Hook commands MUST read the gateway address
from `$RELAY_PORT` and `$RELAY_TOKEN` at execution time, MUST use a short timeout, and
MUST exit successfully even when the gateway is unreachable so the agent's own flow is
never blocked.

#### Scenario: Full lifecycle relayed
- **WHEN** a Claude Code session launched with the injected settings starts, finishes a
  turn, requests permission, and ends
- **THEN** the gateway receives `session.registered`, `turn.completed`,
  `needs.input(permission)`, and `session.ended` for that session

#### Scenario: Gateway down does not disturb the agent
- **WHEN** a hook command runs while the IDE gateway is unreachable
- **THEN** the command times out quickly, exits zero, and the Claude session continues
  unaffected

### Requirement: Adapters are environment-blind
Adapter configuration SHALL depend only on `$RELAY_PORT` and `$RELAY_TOKEN` and SHALL
always target `http://127.0.0.1:$RELAY_PORT`. Routing `127.0.0.1` to the IDE is the
launcher's responsibility (local: same host; docker: host-gateway mapping; remote: ssh
reverse tunnel). Adapter content MUST be identical across all three environments.

#### Scenario: Same adapter works remotely
- **WHEN** a launcher spawns a remote sandbox session, exporting `RELAY_PORT` and
  `RELAY_TOKEN` and opening a reverse tunnel for that port
- **THEN** the unmodified adapter delivers events to the IDE gateway exactly as a local
  session would

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
code (one normalizer per agent), not in adapter shell commands. Each built-in adapter
SHALL declare its capability set (Claude Code: `turn_completed`, `needs_input`,
`session_end` all true) and the normalizer MUST attach it to the session on
registration. Normalizers MUST validate payloads strictly and drop events they cannot
map.

#### Scenario: Claude Notification mapped by matcher
- **WHEN** a native Claude `Notification` payload with matcher `idle_prompt` arrives at
  the ingest route
- **THEN** the normalizer emits `needs.input` with kind `idle` for that session

#### Scenario: Unmappable payload dropped safely
- **WHEN** a native payload arrives that the agent's normalizer cannot map to a `v1`
  event
- **THEN** the gateway responds without error and no registry state changes

### Requirement: Documented contract for other agents
The plugin SHALL document the adapter contract for agents without a built-in adapter: the
normalized `/relay/v1/events` API for custom agents, and the launch-time injection path
per surveyed harness (Codex `-c notify=[…]` single-slot caveat, Gemini and Cursor
project-level hook files, OpenCode project plugin), including each harness's capability
asymmetries.

#### Scenario: Custom agent integrates from docs alone
- **WHEN** a developer follows the documented contract to wire a custom agent (e.g. a
  `curl` call on its turn boundary)
- **THEN** the session registers and updates in the IDE with no plugin code changes
