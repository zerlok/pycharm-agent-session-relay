# agent-event-gateway — tasks

## 1. Domain & registry core

- [ ] 1.1 Add `session` domain records (pure Kotlin, no platform imports, by-layer
      package placement per design D7): `AgentSession` (opaque per-session id as identity, agent,
      kind `PUSH|SERVER` + optional endpoint, localPath, environment, remotePath?,
      capabilities incl. `turn_started`, state, lastEventAt) and `SessionState`
      (registered / working / idle / needs-input(kind) / ended / unknown) with unit tests
      for every state-machine transition in the gateway spec, including needs-input
      cleared by `turn.*` and restored-session unknown state.
- [ ] 1.2 Add the versioned normalized event model (`schema: 1`): `session.started`,
      `turn.started`, `turn.completed`, `needs.input(kind)`, `session.ended`; strict JSON
      parsing that ignores unknown fields and classifies unknown event types as tolerated
      no-ops (tests: valid events, unknown type, unknown fields, malformed JSON).
- [ ] 1.3 Implement the application-level `SessionRegistryStorage` (dumb CRUD keyed by
      session id) and `SessionRegistryService` (`@Service(APP)`) applying
      registrations and events on the EDT and publishing on a `SessionRegistryListener`
      MessageBus topic; dismissal API (drops entry + persistence, no side effects);
      tests mirroring the review-batch layering.
- [ ] 1.4 Persist registrations (not state, not events) via `PersistentStateComponent`
      (flat DTO bean, `@State` annotation — see ARCHITECTURE.md §5.4 and the project's
      persistence gotchas), restoring sessions in the unknown state on startup and
      dropping ended sessions; tests for the DTO mapping and restore path.

## 2. Gateway endpoint

- [ ] 2.1 Implement the transport-agnostic `EventGateway` seam: localhost registration
      (no token), session-id resolution for events, parse → normalize → registry;
      per-agent normalizer registry with the Claude Code normalizer (SessionStart /
      UserPromptSubmit / Stop / Notification-by-matcher (`permission_prompt→permission`,
      `idle_prompt→idle`, `agent_needs_input→question`) / SessionEnd), capability
      defaulting (built-in adapter set when omitted; `turn_completed`-only for unknown
      agents); unit tests per mapping, unmappable-payload drop, and session-id scoping.
- [ ] 2.2 Implement the registration route `POST /relay/v1/sessions`: localhost (no
      auth), `local_path`-under-open-project validation (`409` otherwise), per-session
      id assignment, and prior-`session_id` rebind for resume continuity; tests for id
      assignment, 409, rebind, and idempotent behavior on re-registration.
- [ ] 2.3 Implement the `HttpRequestHandler` extension for the id-scoped ingest and
      events routes: override `isSupported` to accept POST, resolve the session by the
      id in the route (unknown id → acknowledged no-op), 4xx on malformed input, never
      throws into the built-in server, hops to EDT for registry mutation.
- [ ] 2.4 Descriptor lifecycle: write `~/.relay/gateway/<port>.json` (`{port, pid, ide}`,
      no secret, per-user location) on startup, delete on shutdown, and sweep sibling
      descriptors with dead pids on startup; tests for the pure parts (content, sweep
      decision).

## 3. Claude Code adapter

- [ ] 3.1 Author the injectable Claude settings JSON resource: hooks for SessionStart,
      UserPromptSubmit, Stop, Notification (permission_prompt / idle_prompt /
      agent_needs_input matchers), SessionEnd — each a one-line `curl -m 5` to
      `"$RELAY_URL/relay/v1/sessions/$RELAY_SESSION/ingest/claude/<event>"`, exit-zero on
      failure; bundle in plugin resources.
- [ ] 3.2 Empirically verify `--settings` hook merge semantics against a pinned Claude
      Code version (does an injected hooks map merge with or replace the user's hooks?);
      record the finding in the contract doc; if replace, implement/document the
      launcher-side merged-settings fallback (design R8).
- [ ] 3.3 Write the contract doc (`docs/ADAPTERS.md`): registration handshake +
      normalized events API for custom agents; per-harness injection matrix (Claude
      `--settings`, Codex `-c notify` single-slot caveat, Gemini/Cursor project files,
      OpenCode plugin) with capability asymmetries; launcher duties (descriptor
      discovery + pid check, registration, `$RELAY_URL` composition per environment —
      docker host-gateway / host network, ssh `-R` tunnel — and env delivery into the
      spawned process incl. tmux `-e`); trust model (no application auth; loopback + ssh-
      tunnel transport boundary, optional `0600` UDS forward on shared hosts, events are
      non-executable, no-local-data rule).

## 4. Sessions tool window

- [ ] 4.1 Implement the Sessions tool window (factory + panel) rendering from
      `SessionRegistryService` via the topic only: project-scoped filter (registered
      `local_path` under project content roots), agent + environment badge + state +
      last-event time per entry, capability-honest rendering (no needs-input indicator /
      working state when undeclared), unknown-state rendering for restored sessions,
      live EDT updates, dismiss-any-session action wired to the registry dismissal API.

## 5. Notifications

- [ ] 5.1 Implement the session notifier: IDE notification on `turn.completed`
      (session-identifying) and `needs.input` (kind-stating, higher urgency), filtered by
      registered `local_path` under the open project; clicking opens the Sessions tool
      window at that session; dismissal is local-only (no gateway or agent effect).

## 6. Wiring, docs & verification

- [ ] 6.1 Register all new extensions/services/topics in `plugin.xml`; run the full test
      suite and `buildPlugin`.
- [ ] 6.2 End-to-end check without a real agent: with the gateway in `runIde` (or a
      handler-level integration test), play a scripted launcher + session via `curl` —
      read descriptor → register (expect a session id; expect 409 for a foreign path) →
      POST the Claude lifecycle sequence to the id-scoped route — and verify registry
      state transitions, tool window rendering, notifications, unknown-session and
      malformed-payload paths, and restart restore (unknown state, same id still routes).
- [ ] 6.3 Update `docs/ARCHITECTURE.md` (additive gateway/registry sections; app-level
      registry deviation and trust boundary noted) and `README.md` (observable-sessions
      feature; explicit non-goals: unlaunched sessions are not observed, no
      attach/execute actions).
