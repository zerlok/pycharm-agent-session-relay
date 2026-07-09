# agent-event-gateway — tasks

## 1. Domain & registry core

- [ ] 1.1 Add `session` domain records (pure Kotlin, no platform imports, by-layer
      package placement per design D7): `AgentSession` (session token as identity, agent,
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
      session token) and `SessionRegistryService` (`@Service(APP)`) applying
      registrations and events on the EDT and publishing on a `SessionRegistryListener`
      MessageBus topic; dismissal API (drops entry + persistence, no side effects);
      tests mirroring the review-batch layering.
- [ ] 1.4 Persist registrations (not state, not events) via `PersistentStateComponent`
      (flat DTO bean, `@State` annotation — see ARCHITECTURE.md §5.4 and the project's
      persistence gotchas), restoring sessions in the unknown state on startup and
      dropping ended sessions; tests for the DTO mapping and restore path.

## 2. Gateway endpoint

- [ ] 2.1 Implement the transport-agnostic `EventGateway` seam: launcher-token check for
      registration, session-token resolution for events, parse → normalize → registry;
      per-agent normalizer registry with the Claude Code normalizer (SessionStart /
      UserPromptSubmit / Stop / Notification-by-matcher (`permission_prompt→permission`,
      `idle_prompt→idle`, `agent_needs_input→question`) / SessionEnd), capability
      defaulting (built-in adapter set when omitted; `turn_completed`-only for unknown
      agents); unit tests per mapping, unmappable-payload drop, and token scoping.
- [ ] 2.2 Implement the registration route `POST /relay/v1/sessions`: launcher-token
      auth, `local_path`-under-open-project validation (`409` otherwise), per-session
      token minting; tests for mint, 409, and idempotent behavior on re-registration.
- [ ] 2.3 Implement the `HttpRequestHandler` extension for the ingest and events routes:
      override `isSupported` to accept POST, bearer auth before body parsing (401 on
      missing/unknown token), 4xx on malformed input, never throws into the built-in
      server, hops to EDT for registry mutation.
- [ ] 2.4 Descriptor lifecycle: generate the launcher token; write
      `~/.relay/gateway/<port>.json` (0600 on POSIX, `{port, launcher_token, pid, ide}`)
      on startup, delete on shutdown, and sweep sibling descriptors with dead pids on
      startup; tests for the pure parts (content, sweep decision).

## 3. Claude Code adapter

- [ ] 3.1 Author the injectable Claude settings JSON resource: hooks for SessionStart,
      UserPromptSubmit, Stop, Notification (permission_prompt / idle_prompt /
      agent_needs_input matchers), SessionEnd — each a one-line `curl -m 5` to
      `"$RELAY_URL/relay/v1/ingest/claude/<event>"` with `$RELAY_TOKEN`, exit-zero on
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
      spawned process incl. tmux `-e`); trust model (per-session token scope, loopback
      exposure on shared hosts, no-local-data rule).

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
      read descriptor → register (expect token; expect 409 for a foreign path) → POST the
      Claude lifecycle sequence — and verify registry state transitions, tool window
      rendering, notifications, 401 and malformed-payload paths, and restart restore
      (unknown state, old token still valid).
- [ ] 6.3 Update `docs/ARCHITECTURE.md` (additive gateway/registry sections; app-level
      registry deviation and trust boundary noted) and `README.md` (observable-sessions
      feature; explicit non-goals: unlaunched sessions are not observed, no
      attach/execute actions).
