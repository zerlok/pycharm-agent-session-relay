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

- Relay exposes an **HTTP event gateway** on the IDE's built-in web server with two
  surfaces: a **launcher registration API** (local-only trust: the launcher registers a
  session with its local project path, environment, and remote-path mapping, and receives
  a **per-session bearer token**) and an **event ingestion API** (agents, via thin
  adapters, POST lifecycle events — raw native hook payloads or normalized events —
  authenticated by that session token). Events are **fire-and-forget** — no durable event
  log, no replay. Session *registrations* (token + metadata) persist across IDE restarts
  so surviving sessions keep reporting; live *state* does not persist.
- **Trust boundary**: nothing local (project paths, credentials) is ever sent to the
  agent's environment except the gateway URL and the session token. The gateway holds the
  remote↔local path mapping; sandboxes only ever see data that already lives inside the
  synced project plus those two env vars.
- **Built-in agent adapters**, starting with Claude Code: launch-time-injected hook
  configuration (`claude --settings <json>`) whose hook commands POST to `$RELAY_URL`
  with `$RELAY_TOKEN`. Adapters are **environment-blind**; the **launcher owns topology**
  (local = direct, docker = host-gateway/host-network URL, remote = ssh reverse tunnel).
  Hard rule: **Relay never modifies the user's existing local or global agent settings.**
- A **session registry** service plus a Sessions tool window: sessions of the current
  project with live state (working / idle / needs input / ended / unknown), agent kind,
  and environment badge.
- **IDE notifications** when a session finishes a turn or needs input.
- The registry models two session kinds from day one: **push sessions** (launched CLIs
  that webhook in — implemented now) and **agent servers** (remotes exposing their own
  HTTP/WS/gRPC API that Relay connects out to — schema seam only, not implemented).

Explicit non-goals: sessions Relay (or a Relay-aware launcher) did not launch are not
observed unless the user wires an adapter to the gateway manually; no attach/execute
actions from the registry in this change (deferred — a registry entry must never carry a
command the IDE would execute).

## Capabilities

### New Capabilities

- `agent-event-gateway`: the launcher registration API, per-session token auth, the
  versioned normalized event schema with per-adapter capability declaration, and the
  session registry it feeds (persistent registrations, ephemeral state).
- `agent-adapters`: the per-agent adapter + launch-time injection contract (Claude Code
  implemented; Codex/OpenCode/Gemini/Cursor/custom documented as contract), including the
  never-touch-user-settings requirement.
- `session-registry`: the Sessions tool window — list, state, environment, dismissal.
- `agent-notifications`: IDE notification balloons on `turn.completed` and `needs.input`
  for sessions of the open project.

### Modified Capabilities

None — the shipped review capabilities (`review-annotation`, `review-batch`,
`review-export`, `review-delivery`) are untouched. Deferred follow-ons that will build on
this foundation (out of scope here, seams reserved): session stats, sandbox management
(configure/launch docker & ssh sandboxes from the IDE, attach-from-registry), agent-server
connectors, targeted multi-session review delivery (ARCHITECTURE.md §6), typed terminal
delivery.

## Impact

- **New code**: gateway (HttpRequestHandler + auth + schema DTOs + per-agent
  normalizers), session registry (domain + storage + `@Service` logic + MessageBus topic,
  per ARCHITECTURE.md §3.1 layering; package placement follows the existing by-layer
  convention — see design), `ui/` additions (Sessions tool window, notifier), bundled
  Claude adapter resource (hooks settings JSON).
- **Platform dependencies**: IntelliJ built-in web server (`HttpRequestHandler` EP) — MVP
  transport, swappable later for a plugin-owned daemon behind the same gateway seam;
  `PersistentStateComponent` for the registration table.
- **External contract**: launchers (reference: the user's `claude-connect` CLI) read the
  gateway descriptor, register the session (local path, environment, remote path), and
  inject `$RELAY_URL`/`$RELAY_TOKEN` + adapter config at spawn time, opening the reverse
  tunnel for remote sandboxes. The event schema is versioned so custom agents can target
  it.
- **Docs**: `docs/ARCHITECTURE.md` gains the gateway/registry sections (additive; existing
  layer rules apply unchanged).
