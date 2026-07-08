## Why

A pending review batch lives only in JVM heap today (`InMemoryReviewBatchStorage`), so closing the IDE or rebooting the OS silently discards every unsent comment. A reviewer who annotates several files across a session and then restarts loses all that work. Persistence was deferred at MVP (ARCHITECTURE §5.4); the loss is now hitting real use.

## What Changes

- The pending batch is written to disk per-user and restored when the project reopens, so a batch of comments across multiple files survives IDE close and OS reboot.
- On restart, opening a document shows its comments at their recorded line ranges — the same surfaces (gutter markers, inline cards, tool window) render from the restored store with no behavior change to the reviewer.
- Storage layer gains a `PersistentReviewBatchStorage` implementation of the existing `ReviewBatchStorage` interface, backed by `PersistentStateComponent` and stored in `workspace.xml` (private, uncommitted drafts).
- A flat `PersistedComment` DTO plus a domain↔DTO mapper are added in the storage layer, because the pure domain types (`sealed Subject`, immutable `ReviewComment`, `value class CommentId`) are intentionally not serializable and must stay inert.
- `ReviewBatchService` is rewired to obtain the storage backing as a service (one line) instead of instantiating the in-memory store directly.
- **Out of scope:** re-anchoring across out-of-IDE edits (ARCHITECTURE §5.2). Comments restore at their recorded line numbers; drift detection remains a separate deferred change.

## Capabilities

### New Capabilities
<!-- none — this strengthens an existing capability -->

### Modified Capabilities
- `review-batch`: the project-scoped store requirement is strengthened from in-memory-only to durable — the batch SHALL survive IDE restart, restored per-user from disk and re-rendered on file open, without re-anchoring.

## Impact

- **Code:** `storage/` gains `PersistentReviewBatchStorage.kt` and `PersistedComment.kt` (+ mapper); `logic/ReviewBatchService.kt` changes one wiring line. `InMemoryReviewBatchStorage` stays for tests. Domain, UI, export, and delivery layers unchanged.
- **Persisted data:** a new state block in `workspace.xml` (`StoragePathMacros.WORKSPACE_FILE`) — per-user, not committed to VCS.
- **APIs:** no change to `ReviewBatchStorage`, `ReviewBatchService`, or `ReviewBatchListener` contracts; the swap is behind the existing interface.
- **Dependencies:** none new — `PersistentStateComponent`/`xmlb` are platform-provided.
