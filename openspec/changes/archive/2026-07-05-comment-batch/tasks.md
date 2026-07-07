## 1. Comment model

- [x] 1.1 Define `ReviewComment` (id, subject, `anchorText?`, `contextHash?`, body, status) with an optional live `RangeMarker`
- [x] 1.2 Define the open `Subject` type — `Line` / `LineRange` / `File` / `Files` / `Project`; author only `Line`/`LineRange`, stub the rest
- [x] 1.3 Compute `contextHash` over the surrounding lines at author time for line-anchored comments (no fuzzy re-anchoring yet)

## 2. Batch store

- [x] 2.1 Implement a `Project`-scoped store service holding the pending batch with add / delete / clear
- [x] 2.2 Add a change-listener mechanism; mutations and callbacks run on the EDT
- [x] 2.3 Make the store the single source the gutter and tool window render from (no private copies)

## 3. Persist on submit (modifies review-annotation)

- [x] 3.1 Reroute `CommentDraft.submit` to add a `ReviewComment` to the store (with `RangeMarker`, `anchorText`, `contextHash`) instead of only notifying
- [x] 3.2 Create the `RangeMarker` from the target range at submit time

## 4. Gutter markers for stored comments

- [x] 4.1 Render a persistent marker on each stored comment's line range, distinct from the hover "+"
- [x] 4.2 Refresh markers on store change; remove a marker when its comment is deleted

## 5. Tool window

- [x] 5.1 Implement a `ToolWindowFactory` listing pending comments grouped by file (line range + body snippet)
- [x] 5.2 Wire navigate-to-line (open/focus file, caret to the comment's start line) on double-click
- [x] 5.3 Keep the list in sync with the store via the change listener

## 6. Delete

- [x] 6.1 Add a delete action in the gutter and in the tool window
- [x] 6.2 On delete, remove from the store; gutter and tool window update via the listener

## 7. Refresh & review (modifies review-annotation)

- [x] 7.1 Implement a "Refresh & review" action that triggers an async VFS refresh so on-disk edits (local or synced) become visible

## 8. Verification

- [x] 8.1 Unit-test the store (add/delete/clear + listener fires) and `contextHash` determinism
- [ ] 8.2 Manually verify in `runIde`: author several comments → gutter markers + tool window list → navigate → delete keeps all surfaces in sync → Refresh surfaces on-disk edits
