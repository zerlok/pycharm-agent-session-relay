# Agent Event Gateway — design

## Context

The shipped MVP relays human → agent (review batch → `REVIEW.md`). Nothing flows agent →
IDE in machine-readable form. This change makes agent sessions **launchable and
observable** from the IDE. The agent harnesses vary (Claude Code, Codex, Gemini, custom)
and the environments vary (local process, docker, ssh, and the user's own sandbox tooling
`hermod`: ssh + `-R` tunnel + tmux + mutagen sync). The design keeps the plugin **entirely
free of agent-specific code**: it launches a user-authored command, injects a tiny env
contract, and exposes one normalized event webhook that any agent's hooks can `curl`.

An earlier draft of this change was observe-only (an external launcher owned everything;
the plugin only watched). That was the wrong direction — the plugin is the launcher now.
Several subsequent simplifications (recorded in D-notes below) removed the per-agent
normalizers, native-payload ingestion, hook auto-injection, and the external
registration/descriptor surface.

## Goals / Non-Goals

**Goals:**

- Launch agent sessions from the IDE into terminals in a dedicated Agent Sessions tool
  window, from user-authored start scripts.
- One IDE-side ingestion point for normalized agent lifecycle events, agnostic to harness
  and to where the agent runs.
- Sessions visible with live state; IDE notifications + optional beep on turn-complete and
  needs-input.
- A hard trust boundary: executable content comes only from local Settings, never from a
  received event; no local data crosses into a sandbox beyond the injected env contract.

**Non-Goals (deferred; seams reserved):**

- Per-agent native-payload normalizers and auto-injection of agent hook config.
- External self-registration API + gateway descriptor file (the plugin registers only what
  it launches).
- Detached-session re-attach after an IDE restart; plugin-managed tunnels; per-connection
  templates; alternate transports (file-drop, PTY back-channel).
- Agent-server (pull) connectors; targeted multi-session review delivery.
- Owning or garbage-collecting sandbox resources (tmux, sync daemons, containers) — the
  connection tooling's job.

## Decisions

### D1. The plugin launches sessions into terminals; the connection tooling owns topology

A start script is a literal command chosen by the user in Settings. On launch the plugin:
(1) registers the session in-process (mints an opaque id, tags it with the launching
project), (2) opens a terminal tab in the Agent Sessions tool window, (3) exports the env
contract into that terminal, (4) runs the script. The agent's TUI in that terminal is the
agent UI (terminal-first philosophy).

The plugin is **environment-blind past step 3**. Reachability — making
`AGENT_SESSION_RELAY_URL` route home from a sandbox (docker host-gateway, `ssh -R` reverse
tunnel to `AGENT_SESSION_RELAY_PORT`) — and forwarding the env vars into the sandbox are the
user's start script / connection tool's job (reference: `hermod` does ssh + `-R` + tmux +
mutagen). The plugin ships **no scripts or templates into a sandbox**.

*Deferred here (was L1-with-templates):* the plugin provides only the scheme + the injected
vars; per-connection start-script templates and any tunnel management are out of scope.

### D2. Env contract injected into the terminal

```
 AGENT_SESSION_RELAY_URL         base gateway URL reachable locally (http://127.0.0.1:<port>);
                                 the connection tool rewrites the far side to the tunnel port
 AGENT_SESSION_RELAY_ID          opaque per-session id — the sole route key (D5), not a secret
 AGENT_SESSION_RELAY_PORT        IDE loopback port, so the connection tool can build `-R`
 AGENT_SESSION_RELAY_PROJECT_DIR the launching project's base path (for `-v ${...}:/project` etc.)
```

The `AGENT_SESSION_RELAY_` prefix is deliberately long to avoid collisions with any other
tooling's `RELAY_*` variables. Start scripts may also reference `${AGENT_SESSION_RELAY_*}`
placeholders, which the plugin substitutes before running.

### D3. One simple normalized webhook — no native payloads, no per-agent normalizers

The gateway exposes exactly one ingestion shape:

```
 POST {AGENT_SESSION_RELAY_URL}/relay/v1/sessions/{id}/events/{type}[?kind=...]
```

`{type}` is a normalized event name (D5). A zero-body `curl -sf -m 5 -X POST` is a complete
hook; an optional JSON body may carry `summary`/`reason`, and `?kind=permission|idle|
question` refines `needs.input`. The handler resolves the session by `{id}`, validates, and
hands a normalized event to a transport-agnostic `EventGateway` service (so the HTTP layer
can later be swapped without touching consumers).

**No agent-specific code lives in the plugin.** Mapping an agent's native hook to one of
these events is done by the *hook command the user wires*, not by a plugin normalizer. The
Claude Code wiring (which hook → which event) is documented in `docs/ADAPTERS.md` as an
example, not shipped as code.

*Deferred (was D4 "fat gateway"/native ingest):* the `/ingest/<agent>/<native-event>` route
and Kotlin per-agent normalizers. If a built-in normalizer is ever wanted, it slots behind
the same `EventGateway` seam additively.

### D4. Built-in web server, behind a gateway seam

The webhook rides the IDE built-in web server via `HttpRequestHandler` — no thread or port
lifecycle to manage (port 63342+, one per IDE). Two implementation facts: `isSupported`
must be overridden to accept POST; and a raw `HttpRequestHandler` bypasses `RestService`
trust checks — fine here (loopback + user's tunnel is the boundary, D8; the handler owns
request handling entirely). The handler is a thin shell over `EventGateway`.

### D5. Versioned simple event schema; the route id is the identity

`schema: 1`. Event types: `session.started`, `turn.started`, `turn.completed`,
`needs.input` (`kind: permission | idle | question`), `session.ended`. **Session identity
is the id in the route** — event bodies carry no session id of their own. Unknown fields
ignored; unknown `{type}` acknowledged-and-dropped (old plugins tolerate new hooks).

State machine (driven only by events; `unknown` on restore per D6):

```
 REGISTERED ──session.started | turn.completed──▶ IDLE ──turn.started──▶ WORKING
 WORKING ──turn.completed──▶ IDLE     WORKING ──needs.input──▶ NEEDS_INPUT(kind)
 NEEDS_INPUT ──turn.started | turn.completed──▶ (working | idle)   any ──session.ended──▶ ENDED
```

`needs.input` clears on the next `turn.*` — the user answers in the agent's own terminal;
the plugin needs no callback (dismissing a notification is local-only). Agents that cannot
emit `turn.started` simply never show `WORKING` (IDLE ⇄ NEEDS_INPUT) — honest per what the
agent reports.

### D6. Registrations persist; events and state are ephemeral

- **Events**: never persisted, never replayed. Hooks MUST tolerate connection failure and
  never block the agent (short `curl` timeout, exit zero).
- **Registrations** (id, agent label, environment, project, start-script reference,
  optional declared capabilities): persisted via `PersistentStateComponent`. No secret is
  stored — the id is a non-secret route key. A session that survives an IDE restart keeps
  its injected `AGENT_SESSION_RELAY_ID` in its process env and cannot receive a new one, so
  the id MUST stay valid across restarts.
- **Live state**: in-memory only. Restored sessions come back `unknown` and correct
  themselves on their next event. Ended sessions are dropped from persistence.

*Deferred:* reviving the terminal + tunnel of a detached session (e.g. a surviving tmux)
after a restart. The registration persists and is dismissable; an in-IDE re-attach action
is a follow-on. Until then a restored session shows `unknown` with its last-event time.

### D7. In-process registration; no external registration endpoint, no descriptor

Because the plugin launches every session it tracks, registration is an **in-process call**
at launch — the plugin already knows the project and mints the id locally. The connection
tool learns the gateway port from the injected `AGENT_SESSION_RELAY_PORT`, so **no
descriptor file** (`~/.relay/gateway/<port>.json`) and **no `POST /relay/v1/sessions`
endpoint** are needed. Consequence (accepted): a session the plugin did *not* launch cannot
self-register; supporting externally-launched/observed sessions is a documented future
extension that re-adds a registration surface behind the same registry.

### D8. Trust model — loopback + the user's tunnel; nothing executable from events

- The built-in web server **binds loopback**; nothing off-host reaches it directly.
- **Remote** agents reach the gateway only through the user's **ssh reverse tunnel**, whose
  host-key verification handles MITM and whose forwarded port is reachable only by the
  connecting user.
- Received events are **non-executable** — a POST produces a notification or a
  registry-state change and nothing the IDE ever executes. Registry entries carry nothing
  runnable. The worst case of a forged event is a *misleading notification*.
- **Executable content originates only from local Settings** (start scripts), exactly like
  an IDE Run Configuration. Launch-from-IDE is safe for that reason.

*Residual risk (accepted):* a party already inside a session's trust (same user, root, or
someone on the open tunnel) can post forged events for *that session only* — impact ceiling
is a wrong notification/state dot; no code runs, nothing leaks. Optional `0600` UDS forward
for shared multi-user hosts is a launcher choice, documented, still no plugin-side auth.

### D9. Layering — app-level registry, project-scoped views

The built-in server (thus the gateway) is application-wide and sessions outlive project
open/close, so the registry store + `@Service` live at application level (the one
deliberate deviation from the review surface's project-`@Service` pattern). A session is
tagged with the **project it was launched from**; the Agent Sessions tool window and
notifier filter to their own project. Every other §3.1 rule holds: inert serializable-shaped
domain records (no platform objects), dumb storage, logic as the only API the view sees,
MessageBus topic as the seam, store mutations + listener callbacks on the EDT (the HTTP
handler thread hops to the EDT before mutating). Package placement follows the by-layer
convention (`domain/`, `storage/`, `logic/`, `ui/`) with the HTTP handler in a new
`gateway/` package at the transport edge (presentation-of-transport: depends on logic,
never the reverse).

### D10. Environment configs are local user data; the plugin executes only them

Named start-script configs (`name`, command with `${AGENT_SESSION_RELAY_*}` placeholders,
environment kind `local | docker | ssh | custom`, optional declared capabilities) live in
an app-level `PersistentStateComponent`, edited in the Settings `Configurable`. They are the
*only* source of anything the plugin executes. Registrations and events never carry anything
runnable (this is the D8 invariant that keeps launch-from-IDE safe and is why no executable
field exists on a registration).

### D11. Agent Sessions tool window hosts the terminals

A dedicated tool window (not tabs in the platform Terminal tool window) owns the session
list and each live session's terminal, via the Terminal plugin API
(`org.jetbrains.plugins.terminal`, a new `<depends>`). Layout (master-detail list+terminal,
or per-session tabs) is an implementation choice. Rendering is **capability-honest**: no
needs-input indicator or working state for a session whose config did not declare it; every
entry shows its last-event time so a silent session reads as silent, not falsely idle.

### D12. Never modify user agent settings

Relay never writes to, patches, or deletes any user/global/project agent config
(`~/.claude/settings.json`, `~/.codex/config.toml`, `.claude/settings.json`, …). All hook
wiring is done by the user's start script per-invocation. Sessions launched without such
wiring simply report no events — they still appear (launched by the plugin) but stay in the
state their (absent) hooks imply, readable via the last-event timestamp.

## Risks / Trade-offs

- [R1: IDE closed / tunnel down ⇒ events lost] → accepted (D6); hooks use short timeouts so
  the agent never blocks; persisted registrations recover on the next event.
- [R2: built-in server port is a shared IDE surface] → loopback bind; events non-executable;
  no secret on the port.
- [R3: multi-user sandbox reaches the reverse tunnel] → bounded blast radius (spoof
  state/notifications for that session only; nothing executes); optional `0600` UDS forward.
- [R4: stale sessions — agent killed, no `session.ended`] → the plugin does not probe remote
  resources (non-goal); mitigated honestly by last-event time, `unknown` after restart, and
  dismiss-any-session.
- [R5: hook floods / malformed payloads] → handler validates strictly, drops unknowns, never
  throws into the built-in server. Notification dedup/throttling deferred.
- [R6: sandbox lacks `curl`] → documented requirement; a `/dev/tcp` or `wget` fallback is a
  doc note, not plugin code.
- [R7: Terminal plugin API surface for hosting an arbitrary command in a tool window] →
  validate the exact API (`TerminalToolWindowManager` / widget creation) during
  implementation; fall back to a platform Terminal tab if the dedicated-tool-window hosting
  proves unsupported.

## Migration Plan

Purely additive — no existing capability changes. Ships dark until the user defines a start
script and launches it. Rollback = remove the new extension points; the persisted
registration + environment tables are inert without them; the review surface is unaffected.
`docs/ARCHITECTURE.md` gains gateway/registry sections after implementation.

## Open Questions

- Exact route prefix on the built-in server (`/relay/v1/…` assumed) — confirm no collision
  with other plugins' handlers during implementation.
- Terminal-hosting API specifics (dedicated tool window vs. platform Terminal tab) —
  resolve against the pinned platform version (R7).
