# Agent Session Relay — Architecture

> **Agent Session Relay** (a.k.a. **Relay**), a JetBrains/PyCharm plugin: review the agent's
> changes in your IDE and relay batched, line-anchored comments straight into its running
> session. A two-way channel: terminal = agent → you; review = you → agent — relayed into the
> specific session that made the changes.

- **Plugin ID:** `io.github.zerlok.agentsessionrelay`
- **Language:** Kotlin, IntelliJ Platform Plugin Template (Gradle)
- **License:** MIT
- **Status:** MVP implemented and published, plus batch persistence. This doc is the agreed
  **technical** design — structure, APIs, and cross-cutting mechanics. **Product requirements**
  (capture modes, the user flow, scope phasing, per-capability behavior) live in the OpenSpec
  capability specs under `openspec/specs/` (in-flight proposals under `openspec/changes/`);
  this doc points to them rather than restating them.
- **Reading rule — design intent vs. shipped code.** Anything this doc describes that is *not*
  built is tagged **[NOT IMPLEMENTED]**. Untagged prose describes code that exists today.
  Preserve that invariant when editing: an untagged claim here is read as a guarantee, and a
  stale one silently becomes a false guarantee.

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
- bundle the IDE's GitHub/GitLab review-thread UI or depend on `com.intellij.collaboration.*`
  (Apache-2.0, but `@ApiStatus.Experimental`). It builds its own comment model on stable
  editor APIs — see §8 for what that does and does not rule out.

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

## 3. Core domain model & layered design

```
  Comment ── a body linked to a Subject (what the comment is about)
     │  { id, subject, anchorText?, contextHash?, body, status }
     │  (no live RangeMarker — position lives in the view; see §3.2)
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

| Concept          | Why pluggable                                                                   |
|------------------|---------------------------------------------------------------------------------|
| **Exporter**     | Export format is agent-specific (Claude = markdown + `@path#L`; others differ). |
| **Session**      | Multiple agents in one repo via worktrees; delivery is per-session.             |
| **Capture mode** | How files enter the surface: any-file (base), changed-files (diff), plan file.  |

### 3.1 Layers — storage · logic · presentation

Relay is built as strict, one-directional layers. **The view depends only on a logic API
and an event topic; it never holds a storage handle.** Logic mediates every read and
write; storage sits behind it and is swappable without the view or logic knowing — a claim
since validated in practice: the in-memory Map was replaced by a `PersistentStateComponent`
in one wiring line, touching neither layer above it.

```
  depends inward ──▶                             seam = MessageBus Topic
  PRESENTATION
    Controller  AnAction (add / delete / refresh) ─┐ commands
    View        EditorReviewOverlay (per editor)   │ + queries         ▲ events
                ToolWindow · Inlay · gutter        ▼                   │
  LOGIC (application)
    ReviewBatchService   @Service(PROJECT)
      the ONLY API the view sees: commands + queries; owns & dispatches events;
      no Swing, no editor imports
  STORAGE
    ReviewBatchStorage   — dumb CRUD over records
      PersistentReviewBatchStorage (workspace.xml); in-memory impl kept for tests
  DOMAIN  (pure Kotlin, serializable, no platform imports)
    ReviewComment { id, body, subject, status, anchorText?, contextHash? }
    Subject = Line | LineRange | File | Files | Project
```

| Layer      | Home                          | Platform primitive                                                                                 |
|------------|-------------------------------|----------------------------------------------------------------------------------------------------|
| Domain     | pure Kotlin records           | —                                                                                                  |
| Storage    | `ReviewBatchStorage`          | `PersistentStateComponent`                                                                         |
| Logic      | `ReviewBatchService`          | `@Service(PROJECT)`                                                                                |
| Seam       | `ReviewBatchListener`         | `MessageBus` `Topic`                                                                               |
| View       | overlay / tool window / inlay | `EditorFactoryListener`, `DocumentMarkupModel`, `Inlay`, `GutterIconRenderer`, `ToolWindowFactory` |
| Controller | actions                       | `AnAction`                                                                                         |

**Storage is a separate layer from logic, not a private field of it.** The abstraction it
hides — *how* records are held — must not leak up into the logic that mediates them, which is
why the persistence swap could touch storage alone.

### 3.2 Inert data vs. live objects

The store holds **only inert, serializable data**: a file **url** (not a `VirtualFile`),
**line numbers** (not a `RangeMarker`), the text, and anchoring seeds (`anchorText`,
`contextHash`). Every live platform object — `Document`, `RangeHighlighter`, `Inlay`,
`VirtualFile` — is a **per-editor projection the view derives from that data and frees when
the editor closes.**

While a file is open, its `RangeHighlighter` (which *is* a `RangeMarker`) is the **live
source of truth for position**; the stored line numbers are the **last-known anchor**,
refreshed from the live marker only at sync points (export, save, editor close). Storage
stays pure while in-IDE edits still track — with no storage write per keystroke.

### 3.3 Rendering & editor lifecycle (retained-mode, per-editor)

Editor rendering is **retained-mode**: register a markup/inlay object once and the platform
repaints it. The view maintains live objects, not frames, and reconciles them **by diff** on store events (add new,
dispose removed, leave the rest). Key rules (per-decision detail in [
`comment-batch/design.md`](../openspec/changes/archive/2026-07-05-comment-batch/design.md)):

- **Ownership follows scope.** An `EditorReviewOverlay` per editor holds what belongs to one
  editor (the inline cards, the card-hover highlight); a `DocumentReviewMarkers` per *document*
  holds the position markers. Both are created on `EditorFactoryListener.editorCreated` and
  freed on `editorReleased` — the markers ref-counted against the live overlays on their
  document, so they are built by the first editor to show the file and disposed after the last
  one's close flush.
- **Filter and seed.** `EditorFactory` is application-wide: handle only editors whose
  `project` matches, whose `editorKind == MAIN_EDITOR`, and whose document has a file. On
  startup, seed from `EditorFactory.getAllEditors()` — `editorCreated` fires only for
  editors opened afterward.
- **Disposer to the service, not the editor alone.** Parent per-editor disposables to the
  project `@Service` so they release on dynamic plugin unload too; dispose them in
  `editorReleased`. Never parent to `Project` / `Application` directly.
- **Inline surfaces are component inlays.** Both the card and the authoring box go through
  `Editor.addComponentInlay(offset, InlayProperties(), component, ComponentInlayAlignment
  .FIT_VIEWPORT_WIDTH)`, not `EditorEmbeddedComponentManager`. The alignment is what makes a
  surface span the editor's viewport and re-lay itself out on every visible-area change; the
  plugin's only remaining width decision is the reading-measure cap applied inside that row by
  `ReadingWidthRow`. Deriving the width from the editor by hand is what this replaced, twice.
- **Highlight & gutter on the document markup** (`DocumentMarkupModel.forDocument`, shared
  across splits); **inlays are per-editor** (`InlayModel` lives on the editor). These two
  scopes differ, so a comment's markers are owned **once per document** even though cards
  are owned per editor — otherwise N splits of one file would each add their own highlighter
  for the same comment.
- **Out of range → orphaned.** A comment whose recorded range does not exist in the current
  document gets no marker and no card, and is marked `ORPHANED`; it keeps its recorded range
  and stays in the tool window. Because it then has no live position at all, no sync point can
  write a display-time substitute over what the user recorded. It returns to `ACTIVE` at the
  next reconcile once the range fits again. An invalid marker is still skipped when live
  positions are read — it reports no position rather than a wrong one. The same verdict is also
  reached at export from the file's content (§5.2), so it does not wait for the file to be
  opened; both paths apply the one rule (`Subjects.fitsIn`) through the same idempotent status
  command, so they cannot disagree or cascade.

---

## 4. Capture modes & user flow — see the OpenSpec specs

This split is a project rule (also stated in the README): **product requirements** — what
the system is *for the user* (capture modes, the canonical review → submit flow, per-capability
behavior) — live in the OpenSpec capability specs under `openspec/specs/` (`review-annotation`,
`review-batch`, `review-export`, `review-delivery`), with in-flight work under
`openspec/changes/`. **This doc** describes what the system is in *technical* terms — the
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
  in-IDE edits        ──▶  RangeMarker tracks automatically                        [shipped]
  out-of-IDE (agent)  ──▶  detect at export: compare anchorText against the        [shipped]
                      │    file's CONTENT — open or closed. Mismatch ⇒ STALE;
                      │    recorded range gone ⇒ ORPHANED
                      └─▶  re-anchor by searching anchorText + contextHash          [NOT IMPLEMENTED]
  still ambiguous     ──▶  export the comment flagged — never mis-point silently    [shipped]
```

Export is the deliverable and happens at submit time, so **loop discipline**
(annotate while the agent is idle → submit before it resumes) is the primary
defense; the content/context hash is the safety net. The data model therefore
carries `anchorText` + `contextHash` from day one, but Tier 1 needs no fuzzy
matching.

Tier 1 needs no help from Relay even when the agent rewrites an **open** file: the platform's
reload-from-disk is **diff-based**, so `RangeMarker`s remap onto the new text and a comment moves
to the right lines by itself (confirmed in maintainer QA, 2026-08-06). This is why adopting
`DocumentTracker` line mapping is a low priority rather than a queued fix — the reasoning that
once motivated it, that reloads reapply offsets naively, is simply wrong.

Tier 2 is **detection, not relocation**: at the export sync point (and only there — a save is not
a claim about anything) each line-anchored comment's recorded `anchorText` is compared against the
text at its recorded range in the file's **current content**, obtained through
`FileDocumentManager` — the in-memory document when the file is open, loaded from disk when it is
not. A mismatch marks the comment `STALE` and a recorded range that no longer exists marks it
`ORPHANED`; the exporter renders each as a short, distinct flag beside an otherwise unchanged
`@path#L` reference. Reading content is I/O, so this runs off the EDT in the delivery layer, and
it is what makes the check cover the case Relay's premise makes common: the agent edits files the
user is *not* looking at. A comment that genuinely cannot be checked — no recorded anchor text, or
a file that cannot be resolved or read — keeps its status: "we could not check" is not reported as
"we checked and it moved". A closed file is not one of those cases. `contextHash` stays
captured-but-unread; it is the input to the deferred tier.

**Fuzzy re-anchoring — searching for `anchorText` elsewhere and *moving* the comment — is
[NOT IMPLEMENTED]** and deliberately so: it can relocate a comment to a wrong-but-plausible
match, which is the failure mode this tier exists to eliminate, and it wants the `DocumentTracker`
line mapping tracked separately.

### 5.3 Threading / EDT

- SSH / external processes / snapshot hashing / export process → **background** (never
  EDT); hand background code an **immutable snapshot** of the batch taken on the EDT. The submit
  pipeline hops exactly once for this reason: EDT position flush → background anchor verification,
  planning and VFS lookup → EDT `REVIEW.md` write, status writes and the clear-or-preserve decision.
- **The `REVIEW.md` write is on the EDT, deliberately.** It goes through the artifact's `Document`
  inside a `WriteCommandAction`, because clearing the batch — the user's only copy, no undo — may
  only be authorized by an export the user can *see*, and a filesystem write behind the Document
  layer leaves an open `REVIEW.md` showing the previous export. Document mutations are
  EDT-plus-write-action by platform contract. What "write off the EDT" protected — a submit that
  does not freeze the IDE — is carried by the background stage, which holds the file reads.
- **`WriteCommandAction` is only for Document/PSI/VFS edits.** Adding, deleting, or
  re-anchoring a comment mutates Relay's *own* state, not the document — do it on the EDT
  without a write command.
- PSI/VFS reads → **read actions**; navigation touching PSI/indexes → guard with
  `DumbService.runWhenSmart`.
- Inlay / gutter / markup / store mutations and listener callbacks → EDT.

### 5.4 Persistence

Shipped: `PersistentReviewBatchStorage` implements `ReviewBatchStorage` behind the same logic
API, stored per-user in `workspace.xml` (`StoragePathMacros.WORKSPACE_FILE`) — these are
private, uncommitted drafts. Two constraints it honors:

- **Serialize a flat DTO, not the domain type.** `xmlb` needs a no-arg constructor and
  mutable (`var`) bean properties and does not serialize a Kotlin sealed hierarchy — so
  persist a flat `PersistedComment` (`subjectKind` + url + start/end + body + anchor data)
  with `@XCollection`, mapped to/from the sealed `Subject` at the boundary.
- **Two platform rules the unit tests cannot reach**, so they are guarded reflectively:
  storage config is read only from `@State` (a class-level `@Storage` alone is inert), and
  `getState()` runs off-EDT unless `getStateRequiresEdt = true` — required here because
  mutations are EDT-only and unsynchronized.

**Resolve off the load path.** `loadState` runs early (pre-index) and loads raw records only; it
resolves no url and decides nothing. A restored comment is judged when its file's editor opens or
at the next submit, whichever comes first: one whose recorded range does not exist is marked
`ORPHANED` and keeps the range it was recorded at — never clamped into view and never written
back over (§3.3).
**Re-anchoring it by searching for `anchorText` + `contextHash` at that moment is
[NOT IMPLEMENTED]**; until it lands an orphaned comment waits for its file to come back rather
than being moved. See §5.2.

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

1. ~~**Build tool.**~~ **Resolved:** Gradle, via the IntelliJ Platform Plugin Template
   (IntelliJ Platform Gradle Plugin 2.x). Maven was floated and dropped — the template is
   Gradle-only. Revisit only if a concrete need appears.
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
5. ~~**Marketplace display name** uniqueness.~~ **Resolved:** published as "Agent Session
   Relay" (Marketplace plugin 32797). The plugin ID (`io.github.zerlok.agentsessionrelay`) is
   independent of the display name.

---

## 8. SDK reference (reuse vs avoid)

**Reuse (trusted/public):** `@Service` (project-level), `MessageBus` / `Topic`,
`EditorFactory` / `EditorFactoryListener`, `DocumentMarkupModel`, `Disposer`, `Inlay` /
`EditorCustomElementRenderer`, `GutterIconRenderer`, `RangeMarker` / `RangeHighlighter`,
`ChangeListManager` / `LineStatusTracker`, `ToolWindowFactory`, `PersistentStateComponent`,
`com.intellij.diff.*`, `org.jetbrains.plugins.terminal` / `TerminalToolWindowManager`.
(`LineMarkerProvider` is pull/PSI-driven — right for static code markers, *not* for the
user-authored, mutable comment markers, which ride a `GutterIconRenderer` on the highlighter.)

**Don't depend on:** the bundled GitHub/GitLab review-thread UI and
`com.intellij.collaboration.*`. The reason is **API stability, not licensing** — the module is
Apache-2.0 like the rest of intellij-community, and its code-review editor package is
`@ApiStatus.Experimental` (not `@Internal`). Relay owns its comment model rather than binding
it to an experimental API it does not control. (`intellij.platform.collaborationTools` **is**
bundled in PyCharm Community 2024.2.5, as `lib/modules/` — the rule stands on stability, not
on absence.)

This is a rule about **taking a dependency**, not about reading the code. The GitHub and
GitLab plugins are the reference implementations of an in-editor review surface, and several
things Relay needs sit in the platform rather than in that module — notably
`Editor.addComponentInlay` / `ComponentInlayRenderer` / `ComponentInlayAlignment`,
`EditorScrollingPositionKeeper`, `ActiveGutterRenderer` +
`reserveLeftFreePaintersAreaWidth`, and `DocumentTracker` / `LineStatusTrackerBase`
(platform VCS) for mapping a line across an out-of-IDE rewrite. Using those is in-charter.

**Correction, and the ground the inlay dependency actually stands on.** This section used to
call the `ComponentInlay*` API *stable* and contrast it with collaboration-tools on that basis.
It is not: all three of those classes are `@ApiStatus.Experimental` in the 2024.2.5 target — the
same status as the package the rule above rejects. The distinction that does hold is a different
one: they live in the public `com.intellij.openapi.editor` package, whereas the alternative Relay
used before them, `EditorEmbeddedComponentManager`, lives in `openapi.editor.**impl**` and carries
no stability contract at all. Depending on an experimental public API is a step *toward* stability
from there, and it is the API the platform's own review-comment inlays are built on, so it will not
be withdrawn without a replacement. Both surfaces reach it through one construction site each, so a
revert is local.

**Study freely:** the GitHub/GitLab plugins (Apache-2.0), Plannotator (Apache-2.0/MIT).
