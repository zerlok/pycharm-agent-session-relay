# agent-adapters — delta spec

Documentation-only capability for this change: it defines the integration **contract** that
any agent's hooks target. No built-in adapter code (no per-agent normalizer, no auto hook
injection) ships here — mapping a native hook to a normalized event is done by the hook
command the user wires, described here as a worked example.

## ADDED Requirements

### Requirement: Documented event and environment contract
The plugin SHALL document the integration contract so any agent or launcher can integrate
without plugin code changes: the injected environment variables (`AGENT_SESSION_RELAY_URL`,
`AGENT_SESSION_RELAY_ID`, `AGENT_SESSION_RELAY_PORT`, `AGENT_SESSION_RELAY_PROJECT_DIR`); the
normalized webhook `POST {AGENT_SESSION_RELAY_URL}/relay/v1/sessions/{id}/events/{type}` with
its event types and the `kind` refinement for `needs.input`; and the rule that hook commands
MUST use a short timeout and exit successfully even when the gateway is unreachable so the
agent's own flow is never blocked.

#### Scenario: Custom agent integrates from docs alone
- **WHEN** a developer wires a custom agent's turn-boundary hook to
  `curl -sf -m 5 -X POST "$AGENT_SESSION_RELAY_URL/relay/v1/sessions/$AGENT_SESSION_RELAY_ID/events/turn.completed"`
- **THEN** the session updates in the IDE with no plugin code change

#### Scenario: Gateway down does not disturb the agent
- **WHEN** a hook command runs while the IDE gateway is unreachable
- **THEN** the command times out quickly, exits zero, and the agent session continues
  unaffected

### Requirement: Worked Claude Code wiring example
The contract doc SHALL include a Claude Code wiring example that maps its hooks to normalized
events (`SessionStart → session.started`, `UserPromptSubmit → turn.started`, `Stop →
turn.completed`, `Notification` by matcher to the `needs.input` kinds `permission` / `idle` /
`question`, `SessionEnd → session.ended`), wired via `claude --settings <inline JSON>` in the
user's own start script. The example MUST be documentation, not plugin-shipped configuration,
and MUST NOT modify any user Claude settings file.

#### Scenario: Claude example is documentation only
- **WHEN** a user follows the documented Claude wiring in their start script
- **THEN** the plugin ships and writes no Claude settings file, and the wiring lives entirely
  in the user's per-invocation command

### Requirement: Documented launcher and reachability duties
The contract doc SHALL describe the duties that belong to the user's start script /
connection tooling, not the plugin: forwarding the `AGENT_SESSION_RELAY_*` variables into the
agent's environment (including tmux sessions); composing a reachable `AGENT_SESSION_RELAY_URL`
per environment (local loopback; docker host-gateway; remote via an `ssh -R` reverse tunnel
to `AGENT_SESSION_RELAY_PORT`); and the trust model (no application auth; loopback bind plus
the ssh-tunnel transport as the boundary; optional `0600` Unix-domain-socket forward on
shared remote hosts; received events are non-executable). Capability asymmetries across
harnesses (e.g. Codex cannot signal needs-input; Cursor hook emission is unreliable) SHALL be
noted so integrators declare capabilities honestly.

#### Scenario: Remote wiring documented end-to-end
- **WHEN** a user reads the contract to wire a remote agent
- **THEN** the doc states how to export the variables into the sandbox, open the `ssh -R`
  tunnel to `AGENT_SESSION_RELAY_PORT`, and curl the normalized webhook from inside
