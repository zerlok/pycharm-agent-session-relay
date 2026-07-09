# Agent Event Gateway — proposal

## Why

Relay's shipped MVP is one-directional in the machine sense: the review surface carries
human → agent, but Relay has no machine-readable signal of what the agent is doing — the
developer tab-checks a terminal to learn that a turn finished or that the agent is waiting
for input. There is also no notion of *which* sessions exist: the `Session` concept from
`docs/ARCHITECTURE.md` §3 was designed but never built. This change makes agent sessions
observable from the IDE — harness-agnostically (Claude Code first, any agent CLI or custom
agent by contract), and environment-agnostically (local, docker, remote sandbox).

## What Changes

- Relay exposes an **HTTP event gateway** on the IDE's built-in web server: agents (via
  thin adapters) POST normalized JSON lifecycle events (`session.registered`,
  `turn.completed`, `needs.input`, `session.ended`) with a per-session bearer token.
  Events are **fire-and-forget** — no durable event log, no replay; registry state is
  in-memory and rebuilt from live registrations.
- **Built-in agent adapters**, starting with Claude Code: launch-time-injected hook
  configuration (`claude --settings <json>`) that translates native hook payloads into
  gateway POSTs. Adapters are **environment-blind** (always POST to
  `http://127.0.0.1:$RELAY_PORT`); the **launcher owns topology** (local = direct,
  docker = host-gateway mapping, remote = ssh reverse tunnel). Hard rule: **Relay never
  modifies the user's existing local or global agent settings.**
- A **session registry** service plus a Sessions tool window: sessions of the current
  project with live state (working / idle / needs input / ended), agent kind, environment
  badge, and an attach action that opens a terminal tab running the session's attach hint
  (e.g. `claude-connect <sandbox>`).
- **IDE notifications** when a session finishes a turn or needs input.
- The registry models two session kinds from day one: **push sessions** (launched CLIs
  that webhook in — implemented now) and **agent servers** (remotes exposing their own
  HTTP/WS/gRPC API that Relay connects out to — schema seam only, not implemented).

Explicit non-goal: sessions Relay (or a Relay-aware launcher) did not launch are not
observed unless the user wires an adapter to the gateway manually.

## Capabilities

### New Capabilities

- `agent-event-gateway`: the HTTP endpoint, token auth, the versioned normalized event
  schema with per-adapter capability declaration, and the in-memory session registry it
  feeds.
- `agent-adapters`: the per-agent adapter + launch-time injection contract (Claude Code
  implemented; Codex/OpenCode/Gemini/Cursor/custom documented as contract), including the
  never-touch-user-settings requirement.
- `session-registry`: the Sessions tool window — list, state, environment, attach action.
- `agent-notifications`: IDE notification balloons on `turn.completed` and `needs.input`
  for sessions of the open project.

### Modified Capabilities

None — the shipped review capabilities (`review-annotation`, `review-batch`,
`review-export`, `review-delivery`) are untouched. Deferred follow-ons that will build on
this foundation (out of scope here, seams reserved): session stats, sandbox management
(configure/launch docker & ssh sandboxes from the IDE), agent-server connectors, targeted
multi-session review delivery (ARCHITECTURE.md §6), typed terminal delivery.

## Impact

- **New code**: `gateway/` (HttpRequestHandler + auth + schema DTOs), `registry/`
  (domain + storage + `@Service` logic + MessageBus topic, per ARCHITECTURE.md §3.1
  layering), `ui/` additions (Sessions tool window, notifier), bundled adapter resources
  (Claude Code hooks settings JSON + POST script).
- **Platform dependencies**: IntelliJ built-in web server (`HttpRequestHandler` EP) — MVP
  transport, swappable later for a plugin-owned daemon behind the same gateway seam;
  `org.jetbrains.plugins.terminal` for the attach action.
- **External contract**: launchers (reference: the user's `claude-connect` CLI) inject
  `$RELAY_PORT`/`$RELAY_TOKEN` + adapter config at spawn time and open the reverse tunnel
  for remote sandboxes. The event schema is versioned so custom agents can target it.
- **Docs**: `docs/ARCHITECTURE.md` gains the gateway/registry sections (additive; existing
  layer rules apply unchanged).
