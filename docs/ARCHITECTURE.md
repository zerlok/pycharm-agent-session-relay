# Agent Session Relay — Architecture

> **Agent Session Relay** (a.k.a. **Relay**), a JetBrains/PyCharm plugin: review the agent's
> changes in your IDE and relay batched, line-anchored comments straight into its running
> session. A two-way channel: terminal = agent → you; review = you → agent — relayed into the
> specific session that made the changes.

- **Plugin ID:** `io.github.zerlok.agentsessionrelay`
- **Language:** Kotlin, IntelliJ Platform Plugin Template (Gradle)
- **License:** MIT
- **Status:** pre-implementation. This doc is the agreed design; the first
  shippable slice is captured as an OpenSpec change.

---

## 1. What Relay is (and is not)

Relay is a **line-anchored annotation layer over any file in the project**,
batched and exported to an agent CLI. That is the core. Everything else is an
entry point into, or a transport out of, that one surface.

**Relay does NOT:**
- emulate a terminal — it reuses PyCharm's (`org.jetbrains.plugins.terminal`).
- render diffs — it reuses PyCharm's diff viewer / change view.
- use git as a transport between hosts — file sync (mutagen) carries everything.
- bundle the IDE's GitHub/GitLab review-thread UI or `com.intellij.collaboration.*`
  (internal/unstable/unlicensed). It builds its own comment model on trusted
  editor APIs.

**Relay's original value:** the batched, line-anchored comment model + the
agent-readable export + delivery to a remote, idle agent — none of which the
platform provides.

---

## 2. The environment it lives in

```
   LOCAL (PyCharm + Relay)                 SANDBOX (remote host)
 ┌───────────────────────────┐          ┌──────────────────────────┐
 │ Launcher ─────────────────┼── ssh ──▶│ tmux ─▶ claude CLI        │
 │                           │          │           │ edits files   │
 │ Review surface            │          │           ▼               │
 │   gutter + tool window    │          │   (.agent/plan.md — later)│
 │   comment store           │          │                           │
 │   Exporter → REVIEW.md ───┼─ mutagen→│   REVIEW.md (synced in)   │
 │   types "read REVIEW.md"  │          │      ▲ agent reads it     │
 │   into terminal widget ───┼── keys ─▶│  claude resumes from idle │
 │                           │          │                           │
 │ project files  ◀══════════╪══════════╪══▶ project files          │
 └───────────────────────────┘  mutagen └──────────────────────────┘
              (bidirectional file sync, NO git transport)
```

- Agent runs **remotely in tmux**; the IDE runs **locally**. This is why the
  official Claude Code JetBrains plugin doesn't fit — it relies on a localhost
  lockfile/websocket the remote CLI can't reach.
- The terminal tab in PyCharm is *already inside* `ssh → tmux → claude`. Relay
  delivers by typing into that same widget — no second SSH, no tmux session
  name needed.

---

## 3. Core domain model

```
  Comment ── line-anchored annotation on ANY file
     │  { id, relativePath, startLine, endLine,
     │    anchorText, contextHash, body, status, RangeMarker }
     ▼
  ReviewBatch ── the set of pending comments to deliver together
     │
     ▼
  Exporter ── serializes a batch to an agent-readable form (per agent profile)
     │  Claude → markdown with @path#L refs
     ▼
  Delivery ── writes/sends the export to a target session
     write REVIEW.md (mutagen carries it) + type "read REVIEW.md" into terminal
     ▼
  Session ── { worktreeRoot, terminalWidget | (sshHost, tmuxSession), agentProfile }
```

Three things are pluggable / first-class (the original handoff treated them as
fixed):

| Concept       | Why pluggable                                                        |
|---------------|----------------------------------------------------------------------|
| **Exporter**  | Export format is agent-specific (Claude = markdown + `@path#L`; others differ). |
| **Session**   | Multiple agents in one repo via worktrees; delivery is per-session.  |
| **Capture mode** | How files enter the surface: any-file (base), changed-files (diff), plan file. |

---

## 4. Capture modes (entry points, not separate features)

```
  • any open file + selection      ← the general case; comment any line of anything
  • changed files (local git diff) ← reviewing the agent's edits  (MVP entry point)
  • a plan file (.agent/plan.md)   ← plan mode, written by a sandbox hook  (later)
```

All three feed the **same** comment model and tool window. They differ only in
*how content arrives*, never in how a comment is authored, listed, or exported.

---

## 5. The canonical flow (the MVP happy path)

```
 STEP                          WHAT RELAY DOES                       API / MECHANISM
 ───────────────────────────────────────────────────────────────────────────────────
 0. agent edits; mutagen       (nothing yet)
    syncs to local
 1. open Change view           refresh VFS so synced edits show;     LocalFileSystem.refresh
                               list changed files                    ChangeListManager
 2. select lines, comment      grab file + selection → popup →       keybound AnAction
                               store comment                         RangeMarker + anchor data
 3. see / edit / delete        gutter icon on commented lines;       GutterIconRenderer
    pending comments           inline edit & remove                  LineMarkerProvider
 4. open tool window,          comments grouped by file; navigate;   ToolWindowFactory
    preview, add more          live REVIEW.md preview
 5. (agent idle in tmux)       — waiting for input —
 6. press Submit               write REVIEW.md (mutagen syncs) →      Exporter + Delivery
                               type "read REVIEW.md …" into the      terminal widget keys
                               terminal widget → agent resumes
```

**Submit, resolved:** *file payload, typed-into-the-terminal-tab channel.*
Relay writes `REVIEW.md` locally, lets mutagen sync it, then types a reference
line into the PyCharm terminal widget already connected to the remote agent. The
typed line just points at the synced file; the `@path#L` refs inside resolve
natively for Claude.

---

## 6. Cross-cutting decisions

### 6.1 Diff source — reuse the local working tree
The "no git" constraint is about *transport between hosts*, not reading your own
working tree. Use `ChangeListManager` / `LineStatusTracker` (git working-tree
diff) as the primary change source; snapshot-at-launch is a fallback for
non-git projects. Comments are **not** limited to changed lines — any file, any
line. **VFS refresh** is the real subtlety: mutagen writes to disk but the IDE's
VFS may lag; provide an explicit "Refresh & review" action and rely on
frame-activation refresh.

### 6.2 Anchor drift — defense in depth, mostly free
```
  in-IDE edits        ──▶  RangeMarker tracks automatically
  out-of-IDE (agent)  ──▶  re-anchor on VFS refresh via anchorText + contextHash
  still ambiguous     ──▶  mark comment "stale", surface to human — never mis-point
```
Export is the deliverable and happens at submit time, so **loop discipline**
(annotate while the agent is idle → submit before it resumes) is the primary
defense; the content/context hash is the safety net. The data model therefore
carries `anchorText` + `contextHash` from day one, but Tier 1 needs no fuzzy
matching.

### 6.3 Threading / EDT
- SSH / external processes / snapshot hashing → **background** (never EDT).
- PSI/VFS reads → **read actions**; model mutations → **write actions** (EDT,
  `WriteCommandAction`).
- Inlay / gutter / store mutations → EDT.

### 6.4 Persistence
Comment store persists via `PersistentStateComponent`, re-anchored on load by
`relativePath` + line + content hash. (Scoped as first polish, not strictly MVP.)

---

## 7. Multi-session (worktrees) — the broader vision

```
  repo/  .git (shared)
    worktree-A/  ◀─mutagen─▶ sandbox tmux:agentA  (claude)
    worktree-B/  ◀─mutagen─▶ sandbox tmux:agentB  (codex)

  A comment lives in a file under some worktree.
  Submit batch → write REVIEW.md into THAT worktree (mutagen carries it)
              → deliver to THAT worktree's session.
```

Delivery target is inferred from which worktree a comment's file lives in; if
ambiguous or there's no agent, fall back to "pick a session" or "clipboard +
REVIEW.md". Sessions form a registry in Relay config. **Layered on the same
core — not in the first change.**

---

## 8. Scope phasing

**First OpenSpec change (MVP — the §5 happy path, single session):**
- VFS refresh + change-view entry point
- keybound "add comment" on a selection (any file; diff is the common entry)
- gutter markers; tool window with list / navigate / edit / delete / preview
- Submit: write `REVIEW.md` (Claude markdown, `@path#L`) + type "read REVIEW.md"
  into the active agent terminal widget

**Follow-on changes (each layered on the same core):**
- Launcher feature (open terminal, run a configurable per-profile launch command)
- Persistence across restarts (`PersistentStateComponent` + re-anchoring)
- Multi-session / worktree-aware delivery + session registry
- `ssh + tmux send-keys` delivery fallback (bring-your-own-terminal / headless)
- Plan capture via sandbox Claude Code hook → `.agent/plan.md`
- Pluggable Exporters for non-Claude agents

---

## 9. Open items / assumptions to verify

1. **Plan-capture hook (assumption):** capturing a Claude plan on the sandbox
   likely uses a `PreToolUse` hook matching `ExitPlanMode` reading
   `tool_input.plan`, written to a synced path. *Verify exact event/payload
   before specifying the plan-capture change.*
2. **Mutagen sync race:** sub-second gap between "REVIEW.md written" and "agent
   reads it". Note it; mitigate only if it bites (e.g. a brief wait, or having
   the typed command poll for the file).
3. **Terminal widget handle:** typing into the tab assumes Relay can target the
   right terminal widget (the one Relay launched, or the active/selected one via
   `TerminalToolWindowManager`). Decoupling from the launcher is a small design
   point for the MVP.
4. **Marketplace display name** uniqueness for "Agent Session Relay" — check before
   publishing; the plugin ID (`io.github.zerlok.agentsessionrelay`) is independent of the
   display name.

---

## 10. SDK reference (reuse vs avoid)

**Reuse (trusted/public):** `Inlay` / `EditorCustomElementRenderer`,
`LineMarkerProvider`, `GutterIconRenderer`, `RangeMarker`, `ChangeListManager` /
`LineStatusTracker`, `ToolWindowFactory`, `PersistentStateComponent`,
`com.intellij.diff.*`, `org.jetbrains.plugins.terminal` /
`TerminalToolWindowManager`.

**Avoid:** the bundled GitHub/GitLab review-thread UI and
`com.intellij.collaboration.*` (internal, unstable, not licensed for reuse).

**Study freely:** Plannotator (Apache-2.0/MIT).
