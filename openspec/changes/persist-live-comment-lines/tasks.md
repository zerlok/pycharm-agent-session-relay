## 1. Document-scoped position flush

- [x] 1.1 Add `syncPositions(document: Document)` to `EditorReviewOverlayService`: for each overlay
      whose `editor.document === document`, push its `currentPositions()` entries through
      `ReviewBatchService.updatePosition` (guarded by `!project.isDisposed`), reusing the exact flush
      the `release()` path already performs.
- [x] 1.2 Refactor `release()` to call the shared flush (or a private helper it shares with
      `syncPositions`) so the editor-close and save paths cannot drift apart.

## 2. Save hook

- [x] 2.1 In `EditorReviewOverlayService.init`, subscribe a `FileDocumentManagerListener` on the
      application message bus, connection parented to `this` service, overriding
      `beforeDocumentSaving(document)` to call `syncPositions(document)`.
- [x] 2.2 Update the service KDoc to note document save as a sync point alongside editor close, and
      remove the now-inaccurate "save not wired" implication from `release()`'s comment.

## 3. Verify

- [x] 3.1 Add a test asserting that saving a document flushes its comments' shifted ranges into the
      store (via `updatePosition`) and that comments in an unsaved sibling document are untouched.
- [x] 3.2 Build offline and run the test suite per the project build/test env.
