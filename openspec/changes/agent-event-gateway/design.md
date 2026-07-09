# Agent Event Gateway — design

## Context

The shipped MVP relays human → agent (review batch → `REVIEW.md`). Nothing flows agent →
IDE in machine-readable form: `Session` exists in `docs/ARCHITECTURE.md` §3 as a concept
only. Meanwhile the environments an agent runs in vary — local process, docker container,
remote sandbox reached via ssh + tmux with mutagen file sync (reference launcher: the
user's `claude-connect` CLI) — and the agent harnesses vary too (Claude Code, Codex,
OpenCode, Gemini CLI, Cursor CLI, custom agents).

Hook-surface research (2026-07, verified against vendor docs) that this design builds on:

| Agent | Turn-complete | Needs-input | Multiple hooks coexist? | Launch-time injection |
|---|---|---|---|---|
| Claude Code | `Stop` hook | `Notification` (`idle_prompt`, `agent_needs_input`, `permission_prompt`) | yes — hooks merge additively across all settings levels | `--settings <file-or-json>` |
| Gemini CLI | `AfterAgent` | `Notification` (ToolPermission) | yes — arrays + layer merge | project `.gemini/settings.json` |
| Cursor CLI | `stop` (CLI emission unreliable, undocumented subset) | partial | yes — arrays, all layers run | project `.cursor/hooks.json` |
| OpenCode | plugin `session.idle` | plugin `permission.asked` | yes — plugins additive | project `.opencode/plugins/` |
| Codex | `notify` → `agent-turn-complete` (argv JSON) | not exposed | **no — single slot, wrap & chain** | `codex -c notify=[…]` per invocation |

Other verified facts: every hook mechanism executes an arbitrary command with a JSON
payload and knows the project dir; Claude Code hook payloads carry `session_id`,
`transcript_path`, `cwd` on stdin; transcript JSONL is documented as internal/unstable
(never parse it); Claude Code and Gemini have mature OTel export (deferred stats tier).

## Goals / Non-Goals

**Goals:**

- One IDE-side ingestion point for agent lifecycle events, agnostic to harness and to
  where the agent runs.
- Sessions visible in the IDE (registry + tool window) with live state; IDE notifications
  on turn-complete and needs-input.
- Adapters injectable at launch time with zero mutation of user agent configuration.
- Schema seams for the deferred follow-ons: stats, sandbox management from the IDE,
  agent-server (pull) connectors, per-session review delivery.

**Non-Goals:**

- Observing sessions Relay-aware launchers did not start (no auto-discovery, no config
  patching, no terminal scraping).
- Durability or replay of events; historical stats.
- Launching/configuring sandboxes from the IDE (deferred `sandbox-management`).
- Implementing non-Claude adapters (contract documented; implementations follow-on).
- ACP (Zed's Agent Client Protocol): rejected for now — an ACP client owns the agent's
  stdio and conversation UI, which conflicts with Relay's terminal-first philosophy (the
  CLI's own TUI in a tmux-attachable session *is* the agent UI). Revisit if ACP grows a
  sidecar/observer mode.

## Decisions

### D1. Transport: HTTP webhooks into the IDE; the launcher owns topology

Agents notify Relay by POSTing JSON to a local HTTP endpoint. Adapters are
**environment-blind**: they always POST to `http://127.0.0.1:$RELAY_PORT` with
`$RELAY_TOKEN`, both injected by the launcher. Making `127.0.0.1` route to the IDE is the
launcher's job per environment:

```
 local   → identity (same host)
 docker  → host-gateway mapping (host.docker.internal / --add-host)
 remote  → ssh reverse tunnel (-R $RELAY_PORT:127.0.0.1:$RELAY_PORT), opened by the
           launcher that already owns the ssh session (claude-connect)
```

This invariant is also the seam for deferred `sandbox-management`: "launch from IDE"
adds launchers, never touches adapters, gateway, or schema. The custom-agent story is one
sentence: POST this JSON to `$RELAY_URL` with `$RELAY_TOKEN` (curl is the reference
adapter).

*Alternative considered — status files in the project dir carried by mutagen/bind-mount:*
transport-universal and crash-durable, but rejected by product decision: fire-and-forget
semantics chosen (see D3), and HTTP gives sub-second notification latency plus a natural
API for custom agents without polluting the working tree.

### D2. MVP server: IntelliJ built-in web server, behind a gateway seam

The endpoint rides the IDE's built-in web server via the `HttpRequestHandler` extension
point — no thread/lifecycle management, port already allocated (63342+, one per IDE
instance). The handler is a thin shell: it authenticates, parses, and hands a validated
event to a transport-agnostic `EventGateway` service. A later change may swap in a
plugin-owned daemon (own ephemeral port) without touching anything behind the seam.

*Alternative — plugin-owned server now:* better isolation from the IDE's other HTTP
surface, but more moving parts; deferred by product decision.

### D3. Fire-and-forget events; in-memory registry

No durable event log, no last-status files, no replay. If the IDE is closed when an event
fires, the POST fails silently (adapters MUST tolerate connection failure and never block
or fail the agent's own flow — hook timeouts matter). The registry is an in-memory map
rebuilt from live `session.registered` events; on IDE restart the user re-attaches or
relaunches the session and it re-registers. Accepted trade-off: a running remote session
that never re-registers is invisible until re-attached — mitigated at the launcher level
(re-attach re-runs injection) and by deferred pull connectors (D6).

### D4. Thin adapters, fat gateway: normalization happens in plugin code

Adapters do **not** normalize payloads. The gateway exposes two route families:

```
 POST /relay/v1/ingest/<agent>/<native-event>   raw native hook payload, forwarded as-is
 POST /relay/v1/events                          already-normalized Relay events (custom agents)
```

Per-agent normalizers (Kotlin, unit-testable) map native payloads to normalized events.
The Claude Code adapter therefore collapses to a `--settings` JSON whose hook commands are
one-line `curl -s -m 5 -X POST -H "Authorization: Bearer $RELAY_TOKEN" --data-binary @- \
http://127.0.0.1:$RELAY_PORT/relay/v1/ingest/claude/<event>` invocations — no scripts to
ship into sandboxes, no jq/python dependency there; `curl` is the only remote requirement.

*Alternative — shell-side normalization scripts synced to the sandbox:* rejected —
untestable, adds remote runtime dependencies and a script-versioning problem.

### D5. Normalized event schema, versioned, with capability declaration

`v1` events (JSON, `schema: 1` field; unknown fields ignored, unknown event types
acknowledged-and-dropped so old plugins tolerate new adapters):

- `session.registered` — `{ session_id, agent, cwd, environment: local|docker|ssh:<host>,
  attach_hint?, capabilities: { turn_completed, needs_input, session_end } }`
- `turn.completed` — `{ session_id, summary? }`
- `needs.input` — `{ session_id, kind: permission|idle|question }`
- `session.ended` — `{ session_id, reason? }`

Capability declaration exists because harnesses are asymmetric (Codex cannot signal
needs-input; Cursor CLI hook emission is unreliable). The UI degrades honestly: a session
whose adapter lacks `needs_input` shows "last turn completed …" instead of implying it
would have said so.

Claude Code mapping: `SessionStart → session.registered`, `Stop → turn.completed`,
`Notification(idle_prompt|agent_needs_input|permission_prompt) → needs.input`,
`SessionEnd → session.ended`. Session identity = Claude's `session_id`; `cwd` from the
hook payload.

### D6. Registry models two session kinds from day one

`SessionKind = PUSH | SERVER`. **Push** sessions are launched CLIs that webhook in — fully
implemented here. **Server** sessions are agents exposed as long-running services with
their own API (HTTP/WS/gRPC) that Relay connects out to or polls — *schema seam only*:
the domain carries the kind and an opaque `endpoint?` field, and the registry API is
written against the kind, but no connector is implemented. This keeps the deferred
`agent-server connectors` change additive.

### D7. Registry is an application-level service; views are project-scoped

The built-in server (and thus the gateway) is application-wide, and a session's `cwd` may
belong to a project not currently open — so the registry store + service live at
application level, holding all registered sessions. Project-level consumers (tool window,
notifier) subscribe to the registry topic and filter to sessions whose `cwd` is under one
of the project's content roots. This is the one deliberate deviation from the
project-`@Service` pattern of the review surface; every other §3.1 rule holds: inert
serializable-shaped domain records (no platform objects), dumb storage, logic as the only
API the view sees, MessageBus topic as the seam, store mutations + listener callbacks on
EDT (HTTP handler thread hops to EDT before mutating).

### D8. Launcher discovery: gateway descriptor file on the IDE host

External launchers (claude-connect) need the port + a token before spawning. Relay writes
a descriptor `~/.relay/gateway/<port>.json` (`0600`; `{ port, token, pid, ide }`) on
gateway start and removes it on shutdown — the same pattern as the official Claude Code
JetBrains plugin's lockfile. This file lives on the IDE host only and is never synced into
sandboxes (the token travels to remotes via the launcher's env injection over ssh). The
Sessions tool window also offers "Copy relay env" (`RELAY_PORT=… RELAY_TOKEN=…`) for
manual wiring. Token: one per gateway instance in MVP, random per IDE start.

*Alternative — per-session tokens minted via a registration handshake:* stronger
isolation, but requires an unauthenticated mint endpoint or IDE-initiated launch; revisit
with `sandbox-management`.

### D9. Never modify user agent settings — launch-time injection only

The adapter contract requires injection to be per-invocation and side-effect-free:
`claude --settings <json>` (hooks merge additively with the user's own — verified), and
for future adapters `codex -c notify=[…]` (per-invocation override; the gateway-side
Codex normalizer will additionally document wrap-and-chain for users who want persistent
config), Gemini/Cursor project-level files only when the user opts in, OpenCode via a
project plugin. Relay itself never writes to `~/.claude/settings.json`,
`~/.codex/config.toml`, or any user/global config.

## Risks / Trade-offs

- [IDE closed / tunnel down ⇒ events lost] → accepted (D3); adapters use short curl
  timeouts so the agent never blocks; re-attach re-registers.
- [Built-in server port is shared IDE surface; any local process could probe it] → bearer
  token required on every route; 401 without it; descriptor file `0600`.
- [Two IDE instances ⇒ two gateways/ports] → descriptor per port; launcher picks the
  descriptor whose `ide`/project matches, or the newest; document the ambiguity.
- [Cursor CLI hook emission unreliable] → capability declaration + best-effort tier in
  the contract docs; no Cursor adapter shipped in this change.
- [Codex single-slot `notify`] → per-invocation `-c` injection avoids clobbering user
  config; needs-input simply not a Codex capability.
- [Session identity collisions across agents] → registry key = `(agent, session_id)`;
  `session.registered` is idempotent (re-register updates in place).
- [Hook floods / malformed payloads] → normalizers validate strictly, drop unknowns;
  handler is non-blocking and never throws into the built-in server.
- [`--settings` JSON with env-var interpolation] → hook *commands* expand `$RELAY_PORT`
  / `$RELAY_TOKEN` at execution time from the injected env, so the settings JSON itself
  is static — no templating step in the launcher beyond exporting two vars.

## Migration Plan

Purely additive — no existing capability changes. Ship dark: the gateway EP registers but
does nothing observable until a launcher injects an adapter. Rollback = remove the new
extension points; review surface unaffected. `docs/ARCHITECTURE.md` gains gateway/registry
sections after implementation (additive; §1 "what Relay is not" updated to note the
gateway is still not file sync nor an agent UI).

## Open Questions

- Exact route prefix on the built-in server (avoid collisions with other plugins'
  handlers) — resolve during implementation (`/relay/v1/…` assumed).
- Whether `session.registered` from an *unknown* agent string is accepted (generic
  capabilities) or rejected — leaning accept, capabilities defaulting to declared fields.
- claude-connect changes (descriptor read, env export, `-R` tunnel, `--settings`
  injection) are tracked in that repo, not here; only the contract is specified here.
