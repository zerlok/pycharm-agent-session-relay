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
| Claude Code | `Stop` hook | `Notification` (`idle_prompt`, `agent_needs_input`, `permission_prompt`) | yes — hooks merge additively across settings levels (see R8 for the `--settings` caveat) | `--settings <file-or-json>` |
| Gemini CLI | `AfterAgent` | `Notification` (ToolPermission) | yes — arrays + layer merge | project `.gemini/settings.json` |
| Cursor CLI | `stop` (CLI emission unreliable, undocumented subset) | partial | yes — arrays, all layers run | project `.cursor/hooks.json` |
| OpenCode | plugin `session.idle` | plugin `permission.asked` | yes — plugins additive | project `.opencode/plugins/` |
| Codex | `notify` → `agent-turn-complete` (argv JSON) | not exposed | **no — single slot, wrap & chain** | `codex -c notify=[…]` per invocation |

Other verified facts: every hook mechanism executes an arbitrary command with a JSON
payload; Claude Code hook payloads carry `session_id`, `transcript_path`, `cwd` on stdin
and hook commands run via `/bin/sh -c` (env vars expand at execution time); Claude also
fires `UserPromptSubmit` when the user submits a prompt (a turn-start signal); transcript
JSONL is documented as internal/unstable (never parse it); Claude Code and Gemini have
mature OTel export (deferred stats tier).

## Goals / Non-Goals

**Goals:**

- One IDE-side ingestion point for agent lifecycle events, agnostic to harness and to
  where the agent runs.
- Sessions visible in the IDE (registry + tool window) with live state; IDE notifications
  on turn-complete and needs-input.
- Adapters injectable at launch time with zero mutation of user agent configuration.
- A hard trust boundary: no local data (paths, credentials) crosses into the agent's
  environment beyond the gateway URL and the session token.
- Schema seams for the deferred follow-ons: stats, sandbox management from the IDE,
  agent-server (pull) connectors, per-session review delivery.

**Non-Goals:**

- Observing sessions Relay-aware launchers did not start (no auto-discovery, no config
  patching, no terminal scraping).
- Durability or replay of *events*; historical stats. (Session *registrations* do
  persist — see D3.)
- Executing anything a registry entry carries (no attach/run actions in this change).
- Managing remote/sandbox resources (tmux sessions, sync daemons, containers): the plugin
  observes sessions; owning or garbage-collecting the resources behind them is the
  launcher's and the environment's job.
- Launching/configuring sandboxes from the IDE (deferred `sandbox-management`).
- Implementing non-Claude adapters (contract documented; implementations follow-on).
- ACP (Zed's Agent Client Protocol): rejected for now — an ACP client owns the agent's
  stdio and conversation UI, which conflicts with Relay's terminal-first philosophy (the
  CLI's own TUI in a tmux-attachable session *is* the agent UI). Revisit if ACP grows a
  sidecar/observer mode.

## Decisions

### D1. Transport: HTTP webhooks into the IDE; the launcher owns topology

Agents notify Relay by POSTing JSON to the gateway. Adapters are **environment-blind**:
they depend on exactly two injected env vars — `$RELAY_URL` (the gateway base URL as
reachable *from the agent's environment*) and `$RELAY_TOKEN` (the per-session token) —
and never anything else. Composing a `$RELAY_URL` that routes home is the launcher's job:

```
 local   → http://127.0.0.1:<ide-port>
 docker  → http://host.docker.internal:<ide-port>  (--add-host=host.docker.internal:
           host-gateway on Linux), or 127.0.0.1 with --network=host
 remote  → http://127.0.0.1:<tunnel-port> via ssh -R <tunnel-port>:127.0.0.1:<ide-port>,
           opened by the launcher that already owns the ssh session (claude-connect)
```

(An earlier draft hardcoded `127.0.0.1` into adapters and claimed docker could remap it
with a hosts entry — wrong: hosts entries remap hostnames, not IPs. `$RELAY_URL` fixes
docker without changing the adapter's environment-blindness: the *content* of the URL
varies, the adapter's behavior doesn't.)

This invariant is also the seam for deferred `sandbox-management`: "launch from IDE" adds
launchers, never touches adapters, gateway, or schema. The custom-agent story is one
sentence: POST this JSON to `$RELAY_URL` with `$RELAY_TOKEN` (curl is the reference
adapter).

*Alternative considered — status files in the project dir carried by mutagen/bind-mount:*
transport-universal and crash-durable, but rejected by product decision: fire-and-forget
event semantics chosen (see D3), and HTTP gives sub-second notification latency plus a
natural API for custom agents without polluting the working tree.

### D2. MVP server: IntelliJ built-in web server, behind a gateway seam

The endpoint rides the IDE's built-in web server via the `HttpRequestHandler` extension
point — no thread/lifecycle management, port already allocated (63342+, one per IDE
instance). Two implementation facts the tasks must honor: `HttpRequestHandler.isSupported`
accepts only GET/HEAD by default, so the handler must override it to accept POST; and a
raw `HttpRequestHandler` bypasses the platform's `RestService` origin/trust checks — the
plugin owns authentication entirely (D8), which is fine for loopback + bearer-token use.

The handler is a thin shell: it authenticates, parses, and hands a validated event to a
transport-agnostic `EventGateway` service. A later change may swap in a plugin-owned
daemon (own ephemeral port) without touching anything behind the seam.

*Alternative — plugin-owned server now:* better isolation from the IDE's other HTTP
surface, but more moving parts; deferred by product decision.

### D3. Events are fire-and-forget; registrations persist; state is ephemeral

Three different lifetimes, deliberately:

- **Events**: never persisted, never replayed. If the IDE is closed or the tunnel is down
  when an event fires, the POST fails silently (adapters MUST tolerate connection failure
  and never block or fail the agent's own flow — hook timeouts matter).
- **Registrations** (session token + metadata: agent, local path, environment, mapping,
  capabilities): persisted application-side (`PersistentStateComponent`). A session that
  survives an IDE restart keeps its injected `$RELAY_TOKEN` in its process environment
  and cannot receive a new one (re-exporting env into a running tmux process is
  impossible) — so tokens MUST remain valid across restarts. Persisting the registration
  table is what makes that work.
- **Live state**: in-memory only. After a restart, restored sessions come back in state
  `UNKNOWN` ("no live signal since IDE start") and correct themselves on their next
  event. Ended sessions are dropped from persistence.

This also answers "what does the gateway do with an event whose token it knows but whose
session isn't live in memory": the registration is restored from persistence and the
event applies to it — the common path after every IDE restart.

### D4. Thin adapters, fat gateway: normalization happens in plugin code

Adapters do **not** normalize payloads. The gateway exposes:

```
 POST /relay/v1/sessions                        launcher registration (D8) → session token
 POST /relay/v1/ingest/<agent>/<native-event>   raw native hook payload, forwarded as-is
 POST /relay/v1/events                          already-normalized Relay events (custom agents)
```

Per-agent normalizers (Kotlin, unit-testable) map native payloads to normalized events.
The Claude Code adapter therefore collapses to a `--settings` JSON whose hook commands are
one-line `curl -s -m 5 -X POST -H "Authorization: Bearer $RELAY_TOKEN" --data-binary @- \
"$RELAY_URL/relay/v1/ingest/claude/<event>"` invocations — no scripts to ship into
sandboxes, no jq/python dependency there; `curl` is the only remote requirement.

*Alternative — shell-side normalization scripts synced to the sandbox:* rejected —
untestable, adds remote runtime dependencies and a script-versioning problem.

### D5. Normalized event schema, versioned, with capability declaration

`v1` events (JSON, `schema: 1` field; unknown fields ignored, unknown event types
acknowledged-and-dropped so old plugins tolerate new adapters). **Session identity is the
bearer token** — event bodies carry no session identifier of their own; agent-native ids
(e.g. Claude's `session_id`) are stored as informational attributes.

- `session.started` — the agent process came up (or resumed)
- `turn.started` — the agent began working on a prompt
- `turn.completed` — `{ summary? }`
- `needs.input` — `{ kind: permission | idle | question }`
- `session.ended` — `{ reason? }`

The event vocabulary is what the gateway *offers*; adapters declare via `capabilities`
(at registration: `turn_started`, `turn_completed`, `needs_input`, `session_end`) which
of it they actually emit — harnesses are asymmetric (Codex cannot signal needs-input;
Cursor CLI hook emission is unreliable; a minimal custom agent might emit only
`turn.completed`). The UI degrades honestly per capability (D7a).

State machine (driven only by events; `UNKNOWN` on restore per D3):

```
 REGISTERED ──session.started/turn.completed──▶ IDLE ──turn.started──▶ WORKING
 WORKING ──turn.completed──▶ IDLE      WORKING ──needs.input──▶ NEEDS_INPUT(kind)
 NEEDS_INPUT ──turn.started | turn.completed──▶ (working | idle)   any ──session.ended──▶ ENDED
```

`needs.input` clears on the next `turn.*` event — answering happens in the agent's own
terminal; the plugin needs no callback (dismissing an IDE notification is local-only and
never signals the agent). For adapters without `turn_started`, `WORKING` is simply never
shown — the session oscillates IDLE ⇄ NEEDS_INPUT, which is exactly what that adapter can
truthfully report.

Claude Code mapping: `SessionStart → session.started` (fires on startup *and* resume —
a resume therefore refreshes a restored session), `UserPromptSubmit → turn.started`,
`Stop → turn.completed`, `Notification → needs.input` with matcher mapping
`permission_prompt → permission`, `idle_prompt → idle`, `agent_needs_input → question`,
`SessionEnd → session.ended`.

### D6. Registry models two session kinds from day one

`SessionKind = PUSH | SERVER`. **Push** sessions are launched CLIs that webhook in — fully
implemented here. **Server** sessions are agents exposed as long-running services with
their own API (HTTP/WS/gRPC) that Relay connects out to or polls — *schema seam only*:
launcher registration may declare kind `server` with an opaque `endpoint`, the domain and
registry API carry it, but no connector is implemented. This keeps the deferred
`agent-server connectors` change additive. The registration's remote↔local mapping is the
same seam future targeted review delivery (ARCHITECTURE.md §6) will use.

**Deferred sandbox-management, shape clarified: session commands are user config, not
session data.** The launch command of a session (agent binary + args) is chosen when the
session *starts*, from a user-authored environment config (e.g. `sandbox1 → ssh devbox,
claude --dangerously-skip-permissions`; `sandbox2 → codex --deny-tool kubectl`).
Attaching to an already-running detached session cannot change its command — attach only
reuses the environment's connection. Commands therefore only ever originate from local
user config; registrations and events never carry anything executable (this is why the
earlier `attach_hint` field was removed, and it holds for future launch/attach features
too). A registration may later reference its environment config by name — the v1
ignore-unknown-fields rule keeps that additive.

### D7. Registry is an application-level service; views are project-scoped

The built-in server (and thus the gateway) is application-wide, and sessions outlive
project open/close — so the registry store + service live at application level.
Project-level consumers (tool window, notifier) subscribe to the registry topic and
filter to sessions whose **registered local project path** lies under the project's
content roots. (Filtering by the *hook payload's* cwd would break remote sessions — that
cwd is a sandbox path; the local path is known only from launcher registration, which is
exactly why registration carries it. See D8.)

This is the one deliberate deviation from the project-`@Service` pattern of the review
surface; every other §3.1 rule holds: inert serializable-shaped domain records (no
platform objects), dumb storage, logic as the only API the view sees, MessageBus topic as
the seam, store mutations + listener callbacks on EDT (HTTP handler thread hops to EDT
before mutating). Package placement follows the repo's existing by-layer convention
(`domain/`, `storage/`, `logic/`, `ui/`), with the HTTP handler + normalizers in a new
`gateway/` package at the transport edge (it is presentation-of-transport: depends on
logic, never the reverse).

### D7a. Capability-honest UI

The tool window and notifier render only what the adapter declared: no needs-input
indicator for a session whose adapter lacks `needs_input`; no working state without
`turn_started`. Every entry shows its last-event timestamp so a silent session is
readable as silent rather than lying "idle".

### D8. Launcher registration handshake; per-session tokens; trust boundary

The launcher — which runs on the IDE host and is the only party that knows the full
picture — registers each session before spawning it:

```
 launcher                                    gateway (IDE)
    │  read ~/.relay/gateway/<port>.json        │  descriptor: { port, launcher_token,
    │  (launcher_token = registration cred)     │               pid, ide }
    │                                           │
    ├─ POST /relay/v1/sessions ────────────────▶│  validate launcher_token;
    │  { schema:1, agent, kind, local_path,     │  local_path under an open project?
    │    environment: local|docker|ssh:<host>,  │    no → 409 (launcher tries the
    │    remote_path?, capabilities?,           │          next IDE's descriptor)
    │    endpoint? (kind=server) }              │    yes → mint per-session token,
    │◀── 201 { session_token } ─────────────────┤          store registration
    │                                           │
    └─ spawn agent with RELAY_URL + RELAY_TOKEN(session) + adapter injection
```

Consequences, each load-bearing:

- **Per-session tokens.** The token *is* the session identity (D5) and the authorization
  scope: a leaked or abused token can only speak for its own session, never forge events
  for others. (A shared gateway-wide token was considered and rejected — on a multi-user
  sandbox the reverse tunnel is reachable by any local user, and env vars are readable by
  same-user processes; per-session scope bounds that blast radius.)
- **Trust boundary.** Local project paths, environment descriptions, and mappings travel
  launcher → gateway, entirely on the IDE host. The agent's environment receives exactly
  two values: `$RELAY_URL`, `$RELAY_TOKEN`. Nothing a sandbox reports is trusted to
  describe the local machine; the gateway's own registration record is authoritative for
  environment, paths, and project scoping — which is also what makes remote sessions
  scopable at all (D7).
- **Remote↔local remapping lives in the daemon.** `remote_path` (the sandbox-side
  project root, when it differs) is stored alongside `local_path`, so future features
  (targeted delivery, path-bearing events) can remap sandbox paths to local ones
  IDE-side.
- **Multi-IDE disambiguation.** One descriptor per running IDE; registration is rejected
  (`409`) when `local_path` is not under any open project, so a launcher walks
  descriptors until one accepts — no project field needed in the descriptor itself.
- **Descriptor hygiene.** Written on gateway start (`0600` on POSIX; best-effort ACL on
  Windows), deleted on shutdown, and on startup the gateway sweeps sibling descriptors
  whose `pid` is dead — stale files from crashes are collected by the next IDE start.
  Launchers SHOULD validate `pid` liveness before trusting a descriptor.
- **Custom local agents** without a separate launcher may perform the same handshake
  themselves (they run on the IDE host and can read the descriptor), then use the token
  for their own events.
- The agent string in a registration is not restricted to known adapters: unknown agents
  are accepted with whatever capabilities they declare (default: `turn_completed` only);
  built-in agent names get their adapter's capability set when the registration omits it.

*Alternative — anonymous/self registration from the agent side (an earlier draft's
`session.registered` event):* rejected — the agent side cannot know environment or local
paths without the launcher leaking them into the sandbox, and anything it claims about
the local machine would be untrusted anyway.

### D9. Never modify user agent settings — launch-time injection only

The adapter contract requires injection to be per-invocation and side-effect-free:
`claude --settings <json>`, and for future adapters `codex -c notify=[…]`
(per-invocation override; the gateway-side Codex normalizer will additionally document
wrap-and-chain for users who want persistent config), Gemini/Cursor project-level files
only when the user opts in, OpenCode via a project plugin. Relay itself never writes to
`~/.claude/settings.json`, `~/.codex/config.toml`, or any user/global config.

**Verification obligation (R8):** official docs confirm hooks merge additively across
settings *files*, but do not specify merge-vs-replace for the `--settings` flag's hooks
map. If it replaced, injection would silently disable the user's own hooks — violating
this principle's intent. Implementation MUST verify empirically against a pinned Claude
Code version before the adapter ships, and record the finding in the adapter contract
doc.

## Risks / Trade-offs

- [R1: IDE closed / tunnel down ⇒ events lost] → accepted (D3); adapters use short curl
  timeouts so the agent never blocks; persisted registrations mean the session recovers
  on its next event rather than needing a relaunch.
- [R2: built-in server port is a shared IDE surface; any local process could probe it] →
  per-session bearer tokens on every route; launcher token for registration; 401 before
  body parsing; descriptor `0600`.
- [R3: multi-user sandbox can reach the reverse tunnel's loopback bind] → accepted with
  bounded blast radius: a hostile same-host actor holding a stolen session token can
  spoof state/notifications *for that session only*; registry entries carry nothing the
  IDE executes (attach actions deliberately excluded), so spoofing misleads but cannot
  run code. Trust model documented in the adapter contract.
- [R4: stale sessions — agent killed, tunnel dropped, no `session.ended`] → the plugin
  does not own or probe remote resources (Non-Goal); the UI mitigates honestly:
  last-event timestamps (D7a), `UNKNOWN` state after restarts, and any session is
  dismissable at any time (local-only). Liveness probing may come with agent-server
  connectors.
- [R5: Cursor CLI hook emission unreliable] → capability declaration + best-effort tier
  in the contract docs; no Cursor adapter shipped in this change.
- [R6: Codex single-slot `notify`] → per-invocation `-c` injection avoids clobbering
  user config; needs-input simply not a Codex capability.
- [R7: hook floods / malformed payloads] → normalizers validate strictly, drop unknowns;
  handler never throws into the built-in server. Notification dedup/throttling is
  deliberately postponed (revisit with session-stats).
- [R8: `--settings` hooks merge semantics undocumented] → empirical verification task
  before the adapter ships (D9); if replace-not-merge, fall back to generating a merged
  settings file at launch (launcher-side, still zero mutation of user files).
- [R9: env vars must actually reach hook subprocesses in tmux] → hook commands run via
  `/bin/sh -c` and inherit the claude process env; the launcher must ensure `RELAY_URL`/
  `RELAY_TOKEN` are in that env when it creates the tmux session (e.g. `tmux new-session
  -e`, or exporting before exec) — a documented launcher duty, and claude-connect creates
  its own tmux sessions so it controls this.

## Migration Plan

Purely additive — no existing capability changes. Ship dark: the gateway EP registers but
does nothing observable until a launcher registers a session. Rollback = remove the new
extension points; the persisted registration table is inert without them; review surface
unaffected. `docs/ARCHITECTURE.md` gains gateway/registry sections after implementation
(additive; §1 "what Relay is not" updated to note the gateway is still not file sync nor
an agent UI).

## Open Questions

- Exact route prefix on the built-in server (avoid collisions with other plugins'
  handlers) — resolve during implementation (`/relay/v1/…` assumed).
- claude-connect changes (descriptor read + pid check, registration call, env export
  into tmux, `-R` tunnel, `--settings` injection) are tracked in that repo, not here;
  only the contract is specified here.
