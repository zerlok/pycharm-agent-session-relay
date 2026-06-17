# Agent Session Relay — Architecture

> **Agent Session Relay** (a.k.a. **Relay**), a JetBrains/PyCharm plugin: review the agent's
> changes in your IDE and relay batched, line-anchored comments straight into its running
> session. A two-way channel: terminal = agent → you; review = you → agent — relayed into the
> specific session that made the changes.

- **Plugin ID:** `io.github.zerlok.agentsessionrelay`
- **Language:** Kotlin, IntelliJ Platform Plugin Template (Gradle)
- **License:** MIT
- **Status:** pre-implementation. This doc is the agreed **technical** design — structure,
  APIs, and cross-cutting mechanics. **Product requirements** (capture modes, the user flow,
  scope phasing, per-capability behavior) live in the OpenSpec change under
  `openspec/changes/`; this doc points to them rather than restating them.

---

## 1. What Relay is (and is not)

Relay is a **line-anchored annotation layer over any file in the project**,
batched and exported to an agent CLI. That is the core. Everything else is an
entry point into, or a transport out of, that one surface.

**Relay does NOT:**
- emulate a terminal — it reuses PyCharm's (`org.jetbrains.plugins.terminal`).
- render diffs — it reuses PyCharm's diff viewer / change view.
- own file sync between hosts — it reads and writes the **local** filesystem only. A
  remote session is responsible for syncing those files with the local host (e.g. via
  mutagen). Relay never uses git as a cross-host transport.
- bundle the IDE's GitHub/GitLab review-thread UI or `com.intellij.collaboration.*`
  (internal/unstable/unlicensed). It builds its own comment model on trusted
  editor APIs.

**Relay's original value:** the batched, line-anchored comment model + the
agent-readable export + delivery to an idle agent — none of which the platform
provides.

---

## 2. The environment it lives in

**Local-only** is the simplest environment: agent and IDE on the same host, no file sync
required. The **remote** picture below is the most complex case — shown here as an example.
Everything Relay does collapses to a subset of it when the agent is local.

```
   LOCAL (PyCharm + Relay)                 SANDBOX (remote host)
 ┌───────────────────────────┐          ┌───────────────────────────┐
 │ Launcher ─────────────────┼── ssh ──▶│ tmux ─▶ claude CLI        │
 │                           │          │           │ edits files   │
 │ Review surface            │          │           ▼               │
 │   gutter + tool window    │          │   (.agent/plan.md — later)│
 │   comment store           │          │                           │
 │   Exporter → REVIEW.md ───┼─ FS sync→│   REVIEW.md (synced in)   │
 │   types "read REVIEW.md"  │          │      ▲ agent reads it     │
 │   into terminal widget ───┼── keys ─▶│  claude resumes from idle │
 │                           │          │                           │
 │ project files  ◀══════════╪══════════╪══▶ project files          │
 └───────────────────────────┘  FS sync └───────────────────────────┘
                      (bidirectional file sync)
```

In this scheme:

- Agent runs **remotely** (here, in tmux); the IDE runs **locally**. This is why the
  official Claude Code JetBrains plugin doesn't fit — it relies on a localhost
  lockfile/websocket the remote CLI can't reach.
- The terminal tab in PyCharm is *already inside* `claude` (possibly behind an ssh session
  and wrappers such as tmux). When Relay delivers by typing, it types into that same
  widget — no second remote connection needed. (Typed delivery is a follow-on; see the
  OpenSpec change for current MVP scope.)

---

## 3. Core domain model

```
  Comment ── a body linked to a Subject (what the comment is about)
     │  { id, subject, anchorText?, contextHash?, body, status, RangeMarker? }
     │
     │  Subject ─ one of:
     │    Line(path, n) · LineRange(path, start, end) · File(path)
     │    · Files([path…]) · Project   (the whole review / batch)
     ▼
  ReviewBatch ── the set of pending comments to deliver together
     │
     ▼
  Exporter ── serializes a batch to an agent-readable form (per agent profile)
     │  Claude → text (markdown) with @path#L refs
     ▼
  Delivery ── writes/sends the export to a target session
     write REVIEW.md to the local FS (the remote session syncs it in)
     ▼
  Session ── { root, terminalWidget | (sshHost, tmuxSession), agentProfile }
```

A **Comment** is a body plus a **Subject** — the thing it is about. The model deliberately
keeps the Subject open, so a comment is not limited to a single line range:

- **line / line range** — one or more lines on a file (the common case)
- **whole file** — a path with no line range
- **multiple files** — one comment spanning several files
- **project / batch** — detached from any file (general feedback on the whole review)

Line/range/file subjects carry `anchorText` + `contextHash` for re-anchoring; the
multi-file and project subjects need no line anchor, so those fields are optional. *Which
subjects the MVP actually lets you author is a product decision in the `review-annotation`
capability — the model here just doesn't foreclose any of them.*

Three things are pluggable / first-class (the original handoff treated them as
fixed):

| Concept       | Why pluggable                                                        |
|---------------|----------------------------------------------------------------------|
| **Exporter**  | Export format is agent-specific (Claude = markdown + `@path#L`; others differ). |
| **Session**   | Multiple agents in one repo via worktrees; delivery is per-session.  |
| **Capture mode** | How files enter the surface: any-file (base), changed-files (diff), plan file. |

---

## 4. Capture modes & user flow — see the OpenSpec change

This split is a project rule (also stated in the README): **product requirements** — what
the system is *for the user* (capture modes, the canonical review → submit flow, per-capability
behavior) — live in the OpenSpec change under `openspec/changes/` (`review-annotation`,
`review-delivery`). **This doc** describes what the system is in *technical* terms — the
high-level solution and why. It points at the specs rather than restating them.

The one architectural note: **capture mode is a pluggable seam for *how content enters the
surface*** (any open file, the changed-files diff, a plan file). Only the base mode —
comment on any open file — is in the MVP; the **diff and plan-file entry points are
postponed** to a follow-on. The seam exists so they slot in later without touching the
comment model: modes differ solely in how content arrives, never in how a comment is
authored, listed, exported, or delivered.

---

## 5. Cross-cutting decisions

### 5.1 Diff source — reuse the local working tree
The "no git" constraint is about *transport between hosts*, not reading your own
working tree. Use `ChangeListManager` / `LineStatusTracker` (git working-tree
diff) as the primary change source; snapshot-at-launch is a fallback for
non-git projects. Comments are **not** limited to changed lines — any file, any
line. **VFS refresh** is the real subtlety: file sync writes to disk but the IDE's
VFS may lag; provide an explicit "Refresh & review" action and rely on
frame-activation refresh.

### 5.2 Anchor drift — defense in depth, mostly free
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

### 5.3 Threading / EDT
- SSH / external processes / snapshot hashing → **background** (never EDT).
- PSI/VFS reads → **read actions**; model mutations → **write actions** (EDT,
  `WriteCommandAction`).
- Inlay / gutter / store mutations → EDT.

### 5.4 Persistence
Comment store persists via `PersistentStateComponent`, re-anchored on load by
`relativePath` + line + content hash. (Scoped as first polish, not strictly MVP.)

---

## 6. Multi-session (worktrees) — post-MVP

Worktree / multi-session support is **optional and postponed**. The MVP targets a **single
project root** and does not infer or manage worktrees; the design must simply not preclude
adding this later. The shape it will take:

```
  repo/  .git (shared)
    worktree-A/  ◀─FS sync─▶ sandbox tmux:agentA  (claude)
    worktree-B/  ◀─FS sync─▶ sandbox tmux:agentB  (codex)

  A comment lives in a file under some worktree.
  Submit batch → write REVIEW.md into THAT worktree (the session syncs it in)
              → deliver to THAT worktree's session.
```

Delivery target would be inferred from which worktree a comment's file lives in; if
ambiguous or there's no agent, fall back to "pick a session" or "clipboard +
REVIEW.md". Sessions would form a registry in Relay config. Layered on the same
core — **not in the MVP**.

---

## 7. Open items / assumptions to verify

1. **Build tool:** scaffold from the IntelliJ Platform Plugin Template (**Gradle**) for the
   MVP — it bakes in the verifier, `runIde`, and Marketplace publishing. A Maven build was
   floated; revisit only if a concrete need appears (the template is Gradle-only, so Maven
   means dropping the template).
2. **Plan-capture hook (assumption):** capturing a Claude plan on the sandbox
   likely uses a `PreToolUse` hook matching `ExitPlanMode` reading
   `tool_input.plan`, written to a synced path. *Verify exact event/payload
   before specifying the plan-capture change.*
3. **File-sync race:** sub-second gap between "REVIEW.md written" and "agent
   reads it". Note it; mitigate only if it bites (e.g. a brief wait, or having
   the typed command poll for the file). Relevant once typed delivery lands.
4. **Terminal widget handle:** typing into the tab assumes Relay can target the
   right terminal widget (the one Relay launched, or the active/selected one via
   `TerminalToolWindowManager`). Decoupling from the launcher is a small design
   point for the typed-delivery follow-on.
5. **Marketplace display name** uniqueness for "Agent Session Relay" — check before
   publishing; the plugin ID (`io.github.zerlok.agentsessionrelay`) is independent of the
   display name.

---

## 8. SDK reference (reuse vs avoid)

**Reuse (trusted/public):** `Inlay` / `EditorCustomElementRenderer`,
`LineMarkerProvider`, `GutterIconRenderer`, `RangeMarker`, `ChangeListManager` /
`LineStatusTracker`, `ToolWindowFactory`, `PersistentStateComponent`,
`com.intellij.diff.*`, `org.jetbrains.plugins.terminal` /
`TerminalToolWindowManager`.

**Avoid:** the bundled GitHub/GitLab review-thread UI and
`com.intellij.collaboration.*` (internal, unstable, not licensed for reuse).

**Study freely:** Plannotator (Apache-2.0/MIT).
