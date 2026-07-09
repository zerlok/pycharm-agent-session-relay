## 1. Persisted DTO and mapping

- [x] 1.1 Add `PersistedComment` in the storage layer: a flat class with a no-arg constructor and mutable `var` fields (`id`, `subjectKind`, `fileUrl`, `startLine`, `endLine`, `fileUrls`, `body`, `status`, `anchorText`, `contextHash`), keeping domain 0-based line numbers.
- [x] 1.2 Add a private domain↔DTO mapper: `ReviewComment → PersistedComment` and back, switching on `subjectKind` to rebuild each `Subject` variant (`Line`, `LineRange`, `File`, `Files`, `Project`); map `CommentId`, `CommentStatus`, and anchoring fields losslessly.
- [x] 1.3 Handle degenerate persisted input on load: missing fields fall back to defaults, and an unrecognized `subjectKind` maps to a safe fallback rather than throwing.

## 2. Persistent storage backing

- [x] 2.1 Add `PersistentReviewBatchStorage` implementing `ReviewBatchStorage` and `PersistentStateComponent<State>`, annotated `@Service(Service.Level.PROJECT)` and `@Storage(StoragePathMacros.WORKSPACE_FILE)`; back it with an insertion-ordered `LinkedHashMap<CommentId, ReviewComment>` to match `all()` ordering.
- [x] 2.2 Implement the `State` holder with an `@XCollection`/ordered `MutableList<PersistedComment>`; implement `getState()` (map current comments to DTOs) and `loadState()` (rebuild the map from DTOs, no file resolution or re-anchoring).
- [x] 2.3 Implement the `ReviewBatchStorage` CRUD (`all`, `get`, `add`, `update`, `remove`, `clear`) over the in-memory map, mirroring `InMemoryReviewBatchStorage` semantics.

## 3. Wiring

- [x] 3.1 Change `ReviewBatchService.kt:26` to obtain the backing via `project.service<PersistentReviewBatchStorage>()` instead of `InMemoryReviewBatchStorage()`; leave `InMemoryReviewBatchStorage` in place for tests.
- [x] 3.2 Confirm `ReviewBatchToolWindowFactory` builds from `comments()` on project open (initial `rebuild()`), so a restored batch renders in the tool window without waiting for a store event; add the initial build if missing.

## 4. Tests

- [x] 4.1 Unit-test the domain↔DTO mapper round-trip for every `Subject` variant and for populated/empty anchoring fields.
- [x] 4.2 Unit-test `PersistentReviewBatchStorage` CRUD parity with the in-memory store, and a `getState`→`loadState` round-trip preserving comments, order, and identity.
- [x] 4.3 Unit-test the degenerate-input path: unknown `subjectKind` and missing fields load without throwing.

## 5. Verification

- [x] 5.1 Run `./gradlew test` (all existing + new tests green) and `./gradlew buildPlugin`.
- [ ] 5.2 Manual `runIde` QA: create comments across two files, close and reopen the IDE, confirm the tool window lists them and each file's markers/cards render at the recorded ranges; confirm a cleared batch stays empty after restart. (Deferred where no display is available.)
