# Manual QA — agent-event-gateway (task 6.2)

Task 6.2 is an interactive `runIde` end-to-end check. It **cannot run in the headless CI /
agent environment** (no display; `runIde` is unavailable), so it is marked **N/A** in
`tasks.md` and captured here as a checklist to run by hand in a `runIde` sandbox before
release. Everything it would assert is already covered at the unit level (201 tests, see the
per-layer specs); this is the last integration confidence pass.

Setup: `./gradlew runIde`, open a project, then **Settings → Tools → Agent Session Relay**.

## 1. Launch a local session
- [ ] Add a `local` start-script config (e.g. `command = bash`), Apply.
- [ ] Launch it. A terminal opens in the **Agent Sessions** tool window (distinct from
      *Relay Review*), and the session appears there tagged to this project.
- [ ] In that terminal, confirm the env contract is exported:
      `env | grep AGENT_SESSION_RELAY_` shows `URL`, `ID`, `PORT`, `PROJECT_DIR`.

## 2. Drive the normalized webhook with curl
Using the injected `$AGENT_SESSION_RELAY_URL` / `$AGENT_SESSION_RELAY_ID`, POST the lifecycle
sequence to `.../relay/v1/sessions/$AGENT_SESSION_RELAY_ID/events/<type>` and verify each
registry transition renders live in the tool window:
- [ ] `session.started` → **idle**.
- [ ] `turn.started` → **working**.
- [ ] `turn.completed` → **idle**; an IDE notification "turn completed" fires (+ beep if the
      toggle is on).
- [ ] `needs.input?kind=permission` → **needs-input(permission)**; higher-urgency notification
      states the kind (+ beep if that toggle is on).
- [ ] `turn.started` again clears needs-input → **working**.
- [ ] Clicking a notification's **Open Agent Sessions** action reveals that session's terminal.
- [ ] `session.ended` → **ended**.

## 3. Sound-toggle gating
- [ ] Turn off each per-event sound toggle in Settings; re-drive `turn.completed` /
      `needs.input` and confirm the notification still shows but no beep plays. Re-enable → beep
      returns.

## 4. Unknown / malformed paths
- [ ] POST to an **unknown session id** → acknowledged (`2xx`) no-op, no tool-window change, no
      error surfaced.
- [ ] POST an **unknown `<type>`** (e.g. `.../events/bogus.event`) → acknowledged (`2xx`) no-op.
- [ ] POST a **malformed route** (e.g. missing `<type>`) → `4xx`; the IDE built-in server keeps
      serving (no stack trace thrown into it).
- [ ] A `GET` to the events path is not handled (POST-only `isSupported`).

## 5. Restart restore
- [ ] With a live (non-ended) session, restart the IDE (`runIde` again).
- [ ] The session is restored in the **unknown** state, showing its last-event time.
- [ ] POST an event to the **same id** → it still routes and the state corrects itself.
- [ ] An **ended** session from before the restart is **not** restored.
- [ ] **Dismiss** any session → it leaves the tool window and persistence; nothing is sent back
      to the agent.

## 6. Capability honesty
- [ ] A config that does **not** declare `turnStarted` never shows a **working** dot even if a
      `turn.started` arrives; a config that does not declare `needsInput` shows no needs-input
      indicator. Last-event time is always shown.

## 7. Never-touch-user-settings
- [ ] Confirm no agent settings file (`~/.claude/settings.json`, `.claude/settings.json`, …) is
      created or modified by launching a session — all wiring stays in the start-script command
      (design D12).
