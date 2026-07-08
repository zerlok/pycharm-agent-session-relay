## Context

The pending review batch is held by `InMemoryReviewBatchStorage`, a `LinkedHashMap` in JVM heap, reached only through the `ReviewBatchStorage` interface (ARCHITECTURE §3.1). `ReviewBatchService` (project `@Service`) is the sole logic-layer API over it and the one place the concrete backing is named (`ReviewBatchService.kt:26`). All surfaces — gutter markers, inline cards, tool window — are retained-mode and reconcile from live store state (`comments()`), not from replayed events (§3.3). Persistence was deferred at MVP with a prescribed shape (§5.4): a `PersistentStateComponent` behind the same interface, in `workspace.xml`, serializing a flat DTO rather than the domain types.

The domain is intentionally inert: `ReviewComment` is an immutable `data class`, `Subject` a `sealed interface`, `CommentId` a `@JvmInline value class`. None is serializable by `xmlb`, and per the layer rules they must not become so.

## Goals / Non-Goals

**Goals:**
- A pending batch spanning multiple files survives IDE close and OS reboot, and reappears on the next open of the same project.
- On file open after restart, comments render at their recorded line ranges via the existing surfaces, with no change visible to the reviewer.
- The change stays behind the `ReviewBatchStorage` interface: storage layer plus one wiring line, no domain/UI/export/delivery edits.

**Non-Goals:**
- Re-anchoring across out-of-IDE edits (§5.2). Comments restore at stored line numbers; drift handling is a separate change.
- Cross-project, cross-machine, or committed-to-VCS sharing of batches. These are private drafts.
- Any change to `ReviewBatchStorage`, `ReviewBatchService`, or `ReviewBatchListener` contracts, or to how surfaces render.

## Decisions

### D1: A new `PersistentReviewBatchStorage` implements the existing interface — the swap point is `ReviewBatchService.kt:26`
The interface was built for exactly this swap. `PersistentReviewBatchStorage : ReviewBatchStorage, PersistentStateComponent<State>`, annotated `@Service(Service.Level.PROJECT)` and `@State(name = "RelayReviewBatch", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])`. `ReviewBatchService` obtains it via `project.service<PersistentReviewBatchStorage>()` instead of `InMemoryReviewBatchStorage()`.

- **Registration is via `@State`, not a bare `@Storage`.** The platform's `ComponentStore` reads the storage config only from the `@State` annotation (`getStateSpecOrError`); a class-level `@Storage` alone is inert (its `@Target` is empty) and state would silently never persist. `@State.name` is the `<component>` tag in `workspace.xml` and must stay stable. A reflective test guards its presence, since a missing `@State` is invisible to a unit test that calls `getState`/`loadState` directly.

- **Why a service, not a `new`:** `PersistentStateComponent`'s `loadState`/`getState` are only driven by the platform when it owns the instance's lifecycle. It cannot be instantiated directly like the in-memory store.
- **Alternative rejected — make `ReviewBatchService` itself the `PersistentStateComponent`:** fewer files, but it drags `xmlb` serialization and DTO mapping into the logic layer, violating §3.1 (logic has no persistence detail). The one-line wiring cost is the correct price for keeping the concern in storage.
- `InMemoryReviewBatchStorage` remains as the fast, dependency-free backing for unit tests.

### D2: Serialize a flat `PersistedComment` DTO, mapped to/from the domain at the storage boundary
`xmlb` requires a no-arg constructor and mutable `var` bean properties and has no story for a Kotlin sealed hierarchy. So the persisted form is a flat class living in the storage layer:

```
class PersistedComment {              // no-arg ctor, all var, @Tag/@Attribute as needed
    var id: String = ""
    var subjectKind: String = ""      // LINE | LINE_RANGE | FILE | FILES | PROJECT
    var fileUrl: String? = null
    var startLine: Int = -1           // 0-based domain convention preserved on disk
    var endLine: Int = -1
    var fileUrls: MutableList<String> = mutableListOf()   // only for FILES
    var body: String = ""
    var status: String = "ACTIVE"
    var anchorText: String? = null
    var contextHash: String? = null
}
```

State holder: `class State { @XCollection var comments: MutableList<PersistedComment> = mutableListOf() }`, preserving insertion order (mirrors the `LinkedHashMap` contract of `all()`).

- **Why flatten `Subject` to `subjectKind` + fields** rather than nest: `xmlb` can't dispatch on a sealed type, so the mapper switches on `subjectKind` to rebuild the exact `Subject` variant. The MVP only authors `Line`/`LineRange`, but all five variants are mapped so persistence is never the thing that blocks a later subject type.
- **Why keep the domain untouched:** adding `var`/annotations to `ReviewComment` would break "domain stays inert" (§3.1). The DTO absorbs all serialization concessions; the mapper (`ReviewComment ↔ PersistedComment`) is private to the storage layer.
- **Line numbers stay 0-based on disk**, matching the domain; the 1-based conversion remains a user-facing/export concern only.

### D3: `loadState` inflates raw records only — no re-anchoring, no platform lookups on the load path
`loadState` runs early in project open (pre-index). It only rebuilds the in-memory `LinkedHashMap<CommentId, ReviewComment>` from the DTOs. No `VirtualFile` resolution, no document access, no re-anchoring. Rendering happens later and for free: when an editor opens, `EditorReviewOverlay.init → reconcile()` reads `comments()` and draws markers/cards at the stored range; the tool window reads `comments()` when it builds. Because every surface is reconcile-from-current-state, a store that is already full when the UI wakes needs no event replay.

### D4: `getState` is the current in-memory snapshot; the platform decides when to write
`getState()` maps `all()` to DTOs on demand. Persisting on the platform's own save cadence (project close, autosave) is sufficient for the "survives close/reboot" requirement — no explicit flush-to-disk call is added. The existing submit-time position flush (§3.2) already updates the store before the platform next serializes it.

## Risks / Trade-offs

- **`getState` runs on the platform's background save thread** → it iterates the un-synchronized `LinkedHashMap` while CRUD mutates it on the EDT, risking `ConcurrentModificationException` / a torn snapshot on save-during-edit. Mitigated with `@State(getStateRequiresEdt = true)`, which pins `getState` to the EDT — consistent with the EDT-only mutation invariant (§5.3), so no lock is needed.
- **Tool window shows empty after restart despite a restored store** → the one integration point to verify: the overlay path is confirmed to reconcile on `init`, but the tool window must build from `comments()` when its factory runs on project open (not only in response to events). Confirmed already satisfied — `ReviewBatchPanel.init` calls `rebuild()` unconditionally.
- **Runtime external reload of `workspace.xml`** (VCS branch switch, external edit) re-runs `loadState` with no listener event, so already-open views refresh only on the next store change. Accepted: the target is restore-on-restart, not live external sync; storage stays event-free by design (§3.1).
- **A file was edited outside the IDE while the project was closed** → the restored marker may sit a few lines off. Accepted and documented: correctness of position across out-of-IDE edits is the deferred re-anchoring change (§5.2); the comment itself never lost.
- **`workspace.xml` schema drift for old persisted state** → `PersistedComment` fields are additive with defaults, so a missing field deserializes to its default; an unknown `subjectKind` maps to a safe fallback (e.g. skip or treat as `Project`) rather than throwing on load.
- **`getState`/`loadState` copy cost** → batches are small (tens of comments); a full map-to-DTO on save is negligible and keeps the DTO boundary clean.

## Migration Plan

No data migration: there is no prior on-disk format. First run after the change starts with an empty persisted batch; existing in-heap batches from a running session are unaffected. Rollback is reverting the wiring line to `InMemoryReviewBatchStorage()` — any `workspace.xml` state block is simply ignored.

## Open Questions

- None blocking. The tool-window initial-build check (see Risks) is a verification task, not an unresolved design decision.
