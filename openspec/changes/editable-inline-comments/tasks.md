## 1. Store: in-place body update

- [ ] 1.1 Add `updateBody(id: CommentId, body: String)` to `ReviewBatchService`, mirroring
  `updatePosition`: `storage.get(id) ?: return`, no-op if unchanged, `copy(body = ...)`,
  `storage.update`, publish `commentUpdated`.
- [ ] 1.2 Unit-test `updateBody` in `ReviewBatchServiceTest`: preserves id/subject/anchoring, replaces
  body, notifies listeners; unknown id is a no-op with no event.

## 2. Authoring box: edit mode

- [ ] 2.1 Extend `CommentDraft` to accept an optional source `ReviewComment` (id + body): seed the
  body field with its body and open over its current range.
- [ ] 2.2 Route `CommentDraft.submit()` by mode — new comment → `addComment` (unchanged); edit →
  `updateBody(id, body)` plus `updatePosition(id, subject)` when the range moved.
- [ ] 2.3 Add `CommentDraftController.openForEdit(editor, comment)` going through the same `close()`-
  first path so the single-active-box rule holds; expose the currently-editing `CommentId`.
- [ ] 2.4 Have the controller notify the overlay when the editing id changes (open/close) so cards
  reconcile.

## 3. Persistent read-only inline card

- [ ] 3.1 Add a small read-only card component (body text + Edit and Delete buttons) that swallows its
  own mouse events (same `MouseAdapter` pattern as `CommentDraft.buildPanel`) so clicks don't retarget
  to the editor.
- [ ] 3.2 In `EditorReviewOverlay`, own a per-comment map of card block inlays parallel to the marker
  map; place each as a full-width block inlay below its range via `EditorEmbeddedComponentManager`.
- [ ] 3.3 Reconcile cards by diff on `commentAdded/Updated/Removed`/`batchCleared`: add new,
  dispose+rebuild on update, dispose removed — and **skip** the comment that is currently being edited
  (read the controller's editing id).
- [ ] 3.4 Wire card **Edit** → `CommentDraftController.openForEdit`; card **Delete** →
  `ReviewBatchService.removeComment`.
- [ ] 3.5 Dispose all card inlays in `EditorReviewOverlay.dispose()` alongside the markers.

## 4. Gutter edit affordance

- [ ] 4.1 Add an **Edit comment** action to `StoredCommentGutterIconRenderer.getPopupMenuActions()`
  beside **Delete comment**, routing to `CommentDraftController.openForEdit`.

## 5. Verify

- [ ] 5.1 `EditorReviewOverlayTest`: a stored comment yields a card; `commentUpdated` re-renders it;
  delete/clear removes it; the currently-edited comment has no card.
- [ ] 5.2 `./gradlew test` green; `./gradlew buildPlugin` produces the zip.
- [ ] 5.3 Manual `runIde` QA (deferred where no display): submit → card stays; Edit from card and from
  gutter → box seeded; resize+edit → in-place update, no duplicate; cancel → original preserved;
  Delete from card → all surfaces clear.
