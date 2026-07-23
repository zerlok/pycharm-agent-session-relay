# Agent Event Gateway — proposal

## Why

Relay's shipped MVP relays human → agent (the review batch → `REVIEW.md`) but has no
machine-readable signal of what the agent is *doing*: the developer tab-checks a terminal
to learn a turn finished or the agent is waiting for input. The `Session` concept from
`docs/ARCHITECTURE.md` §3 was designed but never built. This change makes agent sessions
**launchable and observable from the IDE** — agent-agnostically (any CLI or custom agent
whose hooks can `curl`) and environment-agnostically (local, docker, ssh, custom sandbox
tooling), while the plugin itself stays completely agent-specific-code-free.

## What Changes

- **The plugin launches agent sessions.** From user-defined **start scripts** in Settings
  (literal commands — `claude`, `docker run -v ${PROJECT_DIR}:/project img`,
  `hermod sandbox -- claude …`) the plugin registers a session in-process, opens a terminal
  in a dedicated **Agent Sessions tool window**, injects a small env contract, and runs the
  script there. The agent's own TUI in that terminal *is* the agent UI.
- **A small injected env contract, nothing else.** The plugin exports
  `AGENT_SESSION_RELAY_URL`, `AGENT_SESSION_RELAY_ID`, `AGENT_SESSION_RELAY_PORT`, and
  `AGENT_SESSION_RELAY_PROJECT_DIR` into the terminal. The user's connection tooling
  (e.g. the user's `hermod`: ssh + `-R` reverse tunnel to `AGENT_SESSION_RELAY_PORT`, tmux,
  mutagen sync) carries those into the sandbox and **owns reachability**. No code, script,
  or template is ever shipped into a sandbox.
- **One simple normalized event webhook** on the IDE built-in web server (loopback). Any
  agent's hooks POST a normalized lifecycle event to
  `POST {AGENT_SESSION_RELAY_URL}/relay/v1/sessions/{id}/events/{type}` — zero-body `curl`
  is enough; `?kind=permission|idle|question` refines needs-input. There is **no
  per-agent native-payload ingestion and no per-agent normalizer in the plugin** (deferred).
- **A session registry** feeding the Agent Sessions tool window: live state
  (registered / working / idle / needs-input / ended / unknown), agent label, environment
  badge, last-event time, and the session's terminal. Registrations persist across restarts
  (restored as `unknown`); live state and events do not.
- **IDE notifications** on turn-completion and needs-input, with an optional short **beep**
  (both events, both on by default, per-event toggle).
- **A Settings page** hosting the start-script configs and the notification/sound toggles —
  the plugin's first `Configurable`.

**Trust boundary.** Executable content originates *only* from local Settings (start
scripts, exactly like an IDE Run Configuration) — never from a registration or a received
event. Events are non-executable; the gateway binds loopback; remote reaches it only
through the user's own `-R` tunnel. This is the original design's D8 trust model, intact.

**Explicit non-goals / deferred** (seams reserved, no code here): per-agent native payload
normalizers and auto-injection of agent hook config; external self-registration API +
gateway descriptor file (the plugin registers only what it launches); detached-session
re-attach after an IDE restart; plugin-managed tunnels or per-connection templates;
alternate transports (file-drop, PTY back-channel); agent-server (pull) connectors;
targeted multi-session review delivery.

## Capabilities

### New Capabilities

- `agent-event-gateway`: the loopback normalized-event webhook, the versioned simple event
  schema, in-process session registration + the event-driven state machine, and
  persistence (registrations persist; events and state are ephemeral).
- `agent-environments`: named start-script configs in Settings and the launch action
  (register in-process → open terminal in the Agent Sessions tool window → inject the env
  contract → run the script). The plugin executes only local user config.
- `session-registry`: the Agent Sessions tool window — session list with live state,
  environment badge, last-event time, the per-session terminal, and dismissal.
- `agent-notifications`: IDE notification balloons plus an optional beep on `turn.completed`
  and `needs.input` for sessions of the open project.
- `agent-adapters`: the documented integration contract (the env-var + webhook scheme any
  agent's hooks target, with Claude Code wiring as a worked example) and the
  never-modify-user-settings rule. **Documentation only — no built-in adapter code in this
  change.**

### Modified Capabilities

None — the shipped review capabilities (`review-annotation`, `review-batch`,
`review-export`, `review-delivery`) are untouched.

## Impact

- **New code**: gateway (`HttpRequestHandler` for the normalized webhook + a
  transport-agnostic `EventGateway` seam + simple event parser), session registry
  (inert domain + dumb storage + app-level `@Service` logic + `PersistentStateComponent` +
  MessageBus topic, per ARCHITECTURE.md §3.1), environment-config store + launch service,
  `ui/` additions (Agent Sessions tool window hosting terminals, notifier, Settings
  `Configurable`).
- **Platform dependencies**: IntelliJ built-in web server (`HttpRequestHandler` EP);
  the Terminal plugin (`org.jetbrains.plugins.terminal`) for hosting session terminals;
  `PersistentStateComponent` for the registration table and the environment configs.
- **External contract**: the user's connection tooling (reference: `hermod`) reads the
  injected env vars, opens the `-R` tunnel to `AGENT_SESSION_RELAY_PORT`, and wires the
  agent's hooks to `curl` the normalized webhook. The event schema is versioned.
- **Docs**: `docs/ADAPTERS.md` (integration contract + Claude example) and additive
  gateway/registry sections in `docs/ARCHITECTURE.md`.
