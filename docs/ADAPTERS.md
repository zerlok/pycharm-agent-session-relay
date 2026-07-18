# Agent adapters — the integration contract

> This is the **integration contract** any agent's hooks target to make a session visible in
> the IDE. Relay ships **no per-agent code**: no native-payload normalizer, no hook
> auto-injection, no writing of any agent settings file (design
> [D3](../openspec/changes/agent-event-gateway/design.md)/[D12](../openspec/changes/agent-event-gateway/design.md)).
> Mapping a harness's native hook to a normalized event is done by the **hook command the
> user wires** in their own start script — the Claude Code example below is documentation, not
> shipped configuration.
>
> Canonical requirements: [`agent-adapters`](../openspec/changes/agent-event-gateway/specs/agent-adapters/spec.md),
> [`agent-event-gateway`](../openspec/changes/agent-event-gateway/specs/agent-event-gateway/spec.md),
> [`agent-environments`](../openspec/changes/agent-event-gateway/specs/agent-environments/spec.md).
> This doc references them and the in-code constants rather than restating; where a name
> appears (env var, route, event type) the code is the single source of truth.

---

## 1. The environment contract

On launch, Relay injects exactly four environment variables into the terminal it opens (and
nothing else — see the trust model in §6). Names are defined once in
[`RelayEnvVars`](../src/main/kotlin/io/github/zerlok/agentsessionrelay/domain/RelayEnvContract.kt)
(design [D2](../openspec/changes/agent-event-gateway/design.md)):

| Variable                          | Meaning                                                                                             |
|-----------------------------------|-----------------------------------------------------------------------------------------------------|
| `AGENT_SESSION_RELAY_URL`         | Base gateway URL reachable **locally** (`http://127.0.0.1:<port>`). The connection tool rewrites the far side to the tunnel port. |
| `AGENT_SESSION_RELAY_ID`          | Opaque per-session id — the **sole** route key, not a secret.                                       |
| `AGENT_SESSION_RELAY_PORT`        | IDE loopback port, so the connection tool can build the `ssh -R` reverse tunnel.                    |
| `AGENT_SESSION_RELAY_PROJECT_DIR` | The launching project's base path (for `-v ${AGENT_SESSION_RELAY_PROJECT_DIR}:/project` etc.).      |

The `AGENT_SESSION_RELAY_` prefix is deliberately long to avoid colliding with any other
tooling's `RELAY_*` variables. A start-script command may also reference
`${AGENT_SESSION_RELAY_*}` placeholders; Relay substitutes them before running the command.

## 2. The normalized webhook

One ingestion shape, defined once in
[`RelayRoute`](../src/main/kotlin/io/github/zerlok/agentsessionrelay/gateway/RelayRoute.kt) +
[`AgentEventType`](../src/main/kotlin/io/github/zerlok/agentsessionrelay/domain/AgentEvent.kt)
(schema `1`, design [D3](../openspec/changes/agent-event-gateway/design.md)/[D5](../openspec/changes/agent-event-gateway/design.md)):

```
POST {AGENT_SESSION_RELAY_URL}/relay/v1/sessions/{id}/events/{type}[?kind=permission|idle|question]
```

- `{id}` is `$AGENT_SESSION_RELAY_ID` — session **identity is the route id**; event bodies
  carry no session id of their own.
- `{type}` is one of the normalized event names below.
- A zero-body `POST` is a complete event. An optional JSON body MAY carry `summary` / `reason`;
  unknown fields are ignored. `?kind=` refines `needs.input`.
- Unknown `{type}` is **acknowledged and dropped** (`2xx`) so old IDEs tolerate new hooks;
  malformed routes get `4xx`; the handler never blocks or throws.

### Event types and the state they drive

| `{type}`          | `?kind=`                        | Session goes to                                            |
|-------------------|---------------------------------|------------------------------------------------------------|
| `session.started` | —                               | idle                                                       |
| `turn.started`    | —                               | working                                                    |
| `turn.completed`  | —                               | idle (also clears a prior needs-input)                     |
| `needs.input`     | `permission` \| `idle` \| `question` (absent → question) | needs-input(kind) — higher-urgency notification |
| `session.ended`   | —                               | ended (terminal)                                           |

Full transition table: [`agent-event-gateway` spec → *Session state machine*](../openspec/changes/agent-event-gateway/specs/agent-event-gateway/spec.md)
and design [D5](../openspec/changes/agent-event-gateway/design.md). `needs.input` clears on the
next `turn.*` — the user answers in the agent's own terminal; the plugin needs no callback.

### The hook rule — short timeout, exit zero

Events are **never** persisted or replayed (design [D6](../openspec/changes/agent-event-gateway/design.md)).
A hook command MUST use a short timeout and **exit successfully even when the gateway is
unreachable**, so the agent's own flow is never blocked (IDE closed, tunnel down → the event
is simply lost; the persisted registration recovers on the next event). The canonical shape:

```bash
curl -sf -m 5 -X POST \
  "$AGENT_SESSION_RELAY_URL/relay/v1/sessions/$AGENT_SESSION_RELAY_ID/events/turn.completed" \
  || true
```

`-m 5` bounds the wait; `-sf` stays quiet and returns non-zero on HTTP errors; `|| true`
guarantees exit zero. A custom agent that wires just this one line against `turn.completed`
already updates live in the IDE — **no plugin code change** (agent-adapters spec: *Custom
agent integrates from docs alone*).

> Sandbox without `curl`? A `/dev/tcp` bash builtin or `wget` fallback is a wiring choice, not
> plugin code (design R6).

## 3. Worked example — Claude Code

Claude Code exposes lifecycle hooks. Map them to normalized events **in your own start
script** via `claude --settings <inline JSON>` — a per-invocation flag that **does not touch
any `~/.claude/settings.json` or `.claude/settings.json`** (design
[D12](../openspec/changes/agent-event-gateway/design.md); agent-adapters spec: *Claude example
is documentation only*). The mapping:

| Claude hook                       | Normalized event                          |
|-----------------------------------|-------------------------------------------|
| `SessionStart`                    | `session.started`                         |
| `UserPromptSubmit`                | `turn.started`                            |
| `Stop`                            | `turn.completed`                          |
| `Notification` (by matcher)       | `needs.input?kind=permission\|idle\|question` |
| `SessionEnd`                      | `session.ended`                           |

A `local` start-script config (Relay Settings → the `command` field) that wires this end to
end. `$R` shortens the base events URL for one session:

```bash
#!/usr/bin/env bash
# A Relay 'local' start-script config command. Relay injects AGENT_SESSION_RELAY_* first.
set -euo pipefail
R="$AGENT_SESSION_RELAY_URL/relay/v1/sessions/$AGENT_SESSION_RELAY_ID/events"
post() { curl -sf -m 5 -X POST "$R/$1" || true; }   # short timeout, always exit zero (§2)

read -r -d '' RELAY_SETTINGS <<JSON || true
{
  "hooks": {
    "SessionStart":     [{ "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/session.started\" || true" }] }],
    "UserPromptSubmit": [{ "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/turn.started\" || true" }] }],
    "Stop":             [{ "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/turn.completed\" || true" }] }],
    "Notification": [
      { "matcher": "permission", "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/needs.input?kind=permission\" || true" }] },
      { "matcher": "idle",       "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/needs.input?kind=idle\"       || true" }] },
      { "matcher": "",           "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/needs.input?kind=question\"   || true" }] }
    ],
    "SessionEnd":       [{ "hooks": [{ "type": "command", "command": "curl -sf -m 5 -X POST \"$R/session.ended\" || true" }] }]
  }
}
JSON

exec claude --settings "$RELAY_SETTINGS"
```

Nothing above is shipped by the plugin; the wiring lives entirely in this per-invocation
command. A session launched **without** such wiring still appears (Relay launched it) but
stays in the state its absent hooks imply — read its honesty via the last-event timestamp
(design [D12](../openspec/changes/agent-event-gateway/design.md)).

## 4. Launcher & reachability duties

Relay is **environment-blind past the moment it injects the env contract** (design
[D1](../openspec/changes/agent-event-gateway/design.md)). It opens a terminal, exports the
four variables, and runs the command. Everything below belongs to the **user's start script /
connection tooling** (reference: `hermod` does ssh + `-R` + tmux + mutagen) — Relay ships no
scripts or templates into a sandbox:

- **Forward the variables into the agent's environment**, including across `tmux`/`ssh` (a new
  tmux session does not inherit the launching shell's env — export them explicitly).
- **Compose a reachable `AGENT_SESSION_RELAY_URL` per environment:**
  - **local** — loopback `http://127.0.0.1:$AGENT_SESSION_RELAY_PORT` works as injected.
  - **docker** — reach the host via the host-gateway, e.g.
    `--add-host=host.docker.internal:host-gateway` and rewrite the URL host to
    `host.docker.internal`; mount the project with
    `-v "$AGENT_SESSION_RELAY_PROJECT_DIR:/project"`.
  - **remote (ssh)** — open a reverse tunnel to the IDE loopback port and point the URL at the
    tunnel's far end:
    ```bash
    ssh -R "$AGENT_SESSION_RELAY_PORT:127.0.0.1:$AGENT_SESSION_RELAY_PORT" sandbox
    # inside the sandbox, AGENT_SESSION_RELAY_URL → http://127.0.0.1:$AGENT_SESSION_RELAY_PORT
    # then: curl -sf -m 5 -X POST "$AGENT_SESSION_RELAY_URL/relay/v1/sessions/$AGENT_SESSION_RELAY_ID/events/turn.completed" || true
    ```
- **Own the sandbox topology** — tmux, sync daemons, containers, tunnels. Relay does not
  create, revive, or garbage-collect any of it (a stale session is surfaced honestly by its
  last-event time and is dismissable; design [D6](../openspec/changes/agent-event-gateway/design.md)/R4).

## 5. Capability honesty

A start-script config declares which events its agent can actually report
([`SessionCapabilities`](../src/main/kotlin/io/github/zerlok/agentsessionrelay/domain/AgentSession.kt):
`turnStarted`, `needsInput`); the tool window renders **only** what was declared (design
[D11](../openspec/changes/agent-event-gateway/design.md)) — no false "working" or needs-input
dot for an agent that cannot signal it. Declare honestly:

- An agent that cannot emit `turn.started` simply never shows **working** (it toggles
  idle ⇄ needs-input) — that is correct, not a bug.
- Known asymmetries to account for: **Codex** cannot signal needs-input; **Cursor** hook
  emission is unreliable. Set the declared capabilities to match, so a silent session reads as
  silent (via its last-event time), never as falsely idle.

## 6. Trust model

Design [D8](../openspec/changes/agent-event-gateway/design.md), summarized:

- **Loopback bind.** The IDE built-in web server binds loopback; nothing off-host reaches the
  gateway directly.
- **Transport is the boundary.** Remote agents reach the gateway only through the user's `ssh`
  reverse tunnel, whose host-key verification handles MITM and whose forwarded port is
  reachable only by the connecting user. There is **no application-level auth** and no secret
  on the port — the id is a non-secret route key.
- **Received events are non-executable.** A POST produces a notification or a registry-state
  change and nothing the IDE ever executes; registry entries carry nothing runnable. The worst
  case of a forged event is a **misleading notification / state dot** for that one session.
- **Executable content originates only from local Settings** (start-script configs), exactly
  like an IDE Run Configuration — which is why launch-from-IDE is safe.
- **Residual risk (accepted):** a party already inside a session's trust (same user, root, or
  someone on the open tunnel) can post forged events for *that session only*. On shared
  multi-user hosts, an optional `0600` Unix-domain-socket forward is a launcher choice — still
  no plugin-side auth.
</content>
</invoke>
