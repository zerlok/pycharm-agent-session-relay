# agent-event-gateway — tasks

## 1. Domain & registry core

- [x] 1.1 Add `session` domain records (pure Kotlin, no platform imports, by-layer package
      placement per design D9): `AgentSession` (opaque id as identity, agent label,
      environment `local|docker|ssh|custom`, launching project, start-script reference,
      declared capabilities, state, lastEventAt) and `SessionState` (registered / working /
      idle / needs-input(kind) / ended / unknown), with unit tests for every state-machine
      transition in the gateway spec — including needs-input cleared by `turn.*` and
      restored-session unknown state.
- [x] 1.2 Add the versioned normalized event model (`schema: 1`): `session.started`,
      `turn.started`, `turn.completed`, `needs.input(kind)`, `session.ended`; strict parsing
      that ignores unknown fields and classifies unknown types as tolerated no-ops (tests:
      each type, kind refinement, unknown type, malformed input).
- [x] 1.3 Implement the application-level `SessionRegistryStorage` (dumb CRUD keyed by id)
      and `SessionRegistryService` (`@Service(APP)`) applying in-process registrations and
      events on the EDT and publishing on a `SessionRegistryListener` MessageBus topic;
      dismissal API (drops entry + persistence, no side effects); tests mirroring the
      review-batch layering.
- [x] 1.4 Persist registrations (not state, not events) via `PersistentStateComponent` (flat
      DTO bean, `@State` — see ARCHITECTURE.md §5.4 and the persistence gotchas), restoring
      sessions in the unknown state on startup and dropping ended sessions; tests for DTO
      mapping and the restore path.

## 2. Gateway webhook

- [x] 2.1 Implement the transport-agnostic `EventGateway` seam: resolve session by the id in
      the route, parse `<type>` + optional `kind`/body into a normalized event, apply to the
      registry (EDT hop); unknown id → acknowledged no-op; unit tests for each event, kind
      refinement, unknown-id drop, and session-id scoping.
- [x] 2.2 Implement the `HttpRequestHandler` extension for `POST
      /relay/v1/sessions/<id>/events/<type>`: override `isSupported` to accept POST, 4xx on
      malformed input, acknowledge-and-drop unknown ids and unknown types, never throw into
      the built-in server; handler is a thin shell over `EventGateway`.

## 3. Environment configs, launch & Settings

- [x] 3.1 Add the `EnvironmentConfig` domain + app-level `PersistentStateComponent` store
      (`name`, `command`, `environment`, optional `capabilities`); tests for persistence and
      `${AGENT_SESSION_RELAY_*}` placeholder substitution (incl. `PROJECT_DIR`).
- [x] 3.2 Implement the launch service: register in-process (mint id, tag project), resolve
      the gateway loopback port, substitute placeholders, and open a terminal in the Agent
      Sessions tool window with `AGENT_SESSION_RELAY_URL|ID|PORT|PROJECT_DIR` exported, then
      run the command. No sandbox-side files, no reachability handling (design D1/D10).
- [x] 3.3 Implement the Relay Settings `Configurable`: CRUD for start-script configs and the
      per-event sound toggles (both on by default); persistence round-trip test.

## 4. Agent Sessions tool window

- [x] 4.1 Implement the dedicated Agent Sessions tool window (factory + panel) rendering from
      `SessionRegistryService` via the topic only: project-scoped filter (launching project),
      agent label + environment badge + state + last-event time per entry, capability-honest
      rendering, unknown-state rendering for restored sessions, live EDT updates, and a
      dismiss-any-session action wired to the registry dismissal API.
- [x] 4.2 Host each live session's terminal in the tool window via
      `org.jetbrains.plugins.terminal` (new `<depends>`); activating an entry reveals its
      terminal. Validate the exact hosting API against the pinned platform version; fall back
      to a platform Terminal tab if dedicated hosting is unsupported (design R7).

## 5. Notifications & sound

- [x] 5.1 Implement the session notifier: IDE notification on `turn.completed`
      (session-identifying) and `needs.input` (kind-stating, higher urgency), filtered to the
      open project; clicking opens the Agent Sessions tool window at that session; dismissal
      is local-only.
- [x] 5.2 Play a short beep (`Toolkit.getDefaultToolkit().beep()`) on `turn.completed` and
      `needs.input`, each gated by its Settings toggle; test the toggle gating (pure decision
      function).

## 6. Wiring, docs & verification

- [x] 6.1 Register all new extensions/services/topics (+ the terminal `<depends>`) in
      `plugin.xml`; run the full test suite and `buildPlugin`.
- [ ] 6.2 **N/A in this environment** (headless — no display, `runIde` unavailable). The
      interactive end-to-end pass is captured as a checklist in `manual-qa.md`, to run in a
      `runIde` sandbox before release; every assertion it covers is unit-tested (201 tests).
      End-to-end check without a real agent: in `runIde`, add a `local` start-script
      config, launch it, and drive the normalized webhook with `curl` — POST the lifecycle
      sequence to the id-scoped route and verify registry state transitions, tool-window
      rendering, notifications + sound, unknown-session and malformed-input paths, and
      restart restore (unknown state, same id still routes).
- [x] 6.3 Write `docs/ADAPTERS.md` (integration contract: env vars, normalized webhook,
      Claude wiring example, launcher/reachability duties, trust model) and update
      `docs/ARCHITECTURE.md` (additive gateway/registry sections; app-level registry
      deviation and trust boundary noted) and `README.md` (launch-and-observe feature;
      explicit non-goals).
