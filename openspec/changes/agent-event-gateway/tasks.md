# agent-event-gateway — tasks

## 1. Domain & registry core

- [ ] 1.1 Add `session` domain records (pure Kotlin, no platform imports): `AgentSession`
      (`SessionKey(agent, sessionId)`, cwd, environment, kind `PUSH|SERVER` + optional
      endpoint, optional attachHint, capabilities, state) and `SessionState`
      (working / idle / needs-input(kind) / ended) with unit tests for the state machine
      transitions driven by event types.
- [ ] 1.2 Add the versioned normalized event model (`schema: 1`): `session.registered`,
      `turn.completed`, `needs.input(kind)`, `session.ended`; strict JSON parsing that
      ignores unknown fields and classifies unknown event types as tolerated no-ops
      (tests: valid events, unknown type, unknown fields, malformed JSON).
- [ ] 1.3 Implement the application-level in-memory `SessionRegistryStorage` (dumb CRUD,
      keyed by `SessionKey`) and `SessionRegistryService` (`@Service(APP)`) applying
      events on the EDT and publishing on a new `SessionRegistryListener` MessageBus
      topic; idempotent re-registration updates in place (tests via storage + service,
      mirroring the review-batch layering).

## 2. Gateway endpoint

- [ ] 2.1 Implement the transport-agnostic `EventGateway` seam: token check → parse →
      normalize → hand to registry service; per-agent normalizer registry with the
      Claude Code normalizer (SessionStart/Stop/Notification-by-matcher/SessionEnd →
      normalized events, capability set attached on registration); unit tests per
      mapping and for unmappable-payload drop.
- [ ] 2.2 Implement the `HttpRequestHandler` extension for `POST /relay/v1/ingest/
      <agent>/<native-event>` and `POST /relay/v1/events`: bearer-token auth before body
      parsing (401 on missing/mismatch), 4xx on malformed input, never throws into the
      built-in server, hops to EDT for registry mutation.
- [ ] 2.3 Token + descriptor lifecycle: generate a random token per gateway instance;
      write `~/.relay/gateway/<port>.json` (0600, `{port, token, pid, ide}`) on startup
      and delete on shutdown (app lifecycle listener); tests for descriptor
      write/permissions/cleanup logic (pure parts).

## 3. Claude Code adapter

- [ ] 3.1 Author the injectable Claude settings JSON resource: hooks for SessionStart,
      Stop, Notification (idle_prompt / agent_needs_input / permission_prompt matchers),
      SessionEnd, each a one-line `curl` with short timeout, `$RELAY_PORT`/`$RELAY_TOKEN`
      expanded at execution time, exit-zero on failure; bundle it in plugin resources and
      expose a "Copy adapter injection" / "Copy relay env" action so launchers and users
      can wire it.
- [ ] 3.2 Write the adapter contract doc (`docs/ADAPTERS.md`): normalized `/relay/v1/
      events` API for custom agents; per-harness injection matrix (Claude `--settings`,
      Codex `-c notify` single-slot caveat, Gemini/Cursor project files, OpenCode
      plugin); capability asymmetries; the never-touch-user-settings rule; launcher
      topology duties (docker host-gateway, ssh `-R` reverse tunnel, descriptor file).

## 4. Sessions tool window

- [ ] 4.1 Implement the Sessions tool window (factory + panel) rendering from
      `SessionRegistryService` via the topic only: project-scoped filter (session cwd
      under project content roots), agent + environment badge + state per entry,
      capability-honest rendering (no needs-input indicator when undeclared), live EDT
      updates, ended-until-dismissed entries with a dismiss action.
- [ ] 4.2 Implement the attach action: opens a project terminal tab
      (`TerminalToolWindowManager`) running the session's `attach_hint`; disabled when
      absent.

## 5. Notifications

- [ ] 5.1 Implement the session notifier: IDE notification on `turn.completed`
      (session-identifying) and `needs.input` (kind-stating, higher urgency), filtered to
      sessions of the open project; clicking opens the Sessions tool window at that
      session.

## 6. Wiring, docs & verification

- [ ] 6.1 Register all new extensions/services/topics in `plugin.xml`; run the full test
      suite and `buildPlugin`.
- [ ] 6.2 End-to-end check without a real agent: launch the gateway in `runIde` (or a
      handler-level integration test), POST the Claude lifecycle sequence with `curl`
      against the descriptor's port/token, and verify registry state, tool window
      rendering, and notifications; verify 401 and malformed-payload paths.
- [ ] 6.3 Update `docs/ARCHITECTURE.md` (additive gateway/registry sections, app-level
      registry deviation noted) and `README.md` (observable-sessions feature, explicit
      non-goal: unlaunched sessions are not observed).
