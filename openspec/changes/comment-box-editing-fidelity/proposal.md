## Why

The inline comment box is a block inlay living *inside* the host editor's content component
(`CommentDraft.showBox`, `EditorEmbeddedComponentManager.addComponent`), and two consequences of that
hosting are still unfixed:

1. **Undo/redo escapes the box and edits the user's source file.** The action system resolves
   `PlatformCoreDataKeys.FILE_EDITOR` by walking the Swing hierarchy *up* from the focus owner; the
   nearest provider of that key is the host file's `EditorCompositePanel`
   (`FILE_EDITOR = composite.selectedEditor`). `UndoRedoAction` reads exactly that key and undoes
   against it. So Ctrl+Z / Ctrl+Shift+Z while typing a comment undoes changes to the **host source
   file**. `2026-07-06-comment-box-key-capture` fixed the per-keystroke leak by moving the body to an
   `EditorTextField` — which supplies `CommonDataKeys.EDITOR` from its own inner editor — but that
   change never touched `FILE_EDITOR`, so this action-scoped leak is live in the shipped MVP. It is
   silent data loss in the user's code.

2. **The box resizes late.** `CommentDraft.bodyField` overrides `getPreferredSize` to grow past the
   `BODY_ROWS` floor as the body grows, but nothing in `CommentDraft` reacts to the body's document
   changing. The platform *does* re-measure an embedded component (`MyRenderer.validate()` →
   `synchronizeBoundsWithInlay()` → `Inlay.update()`), but only when a Swing validate pass reaches the
   renderer — and `EditorTextField.documentChanged` does not revalidate. The box therefore only grows
   once some unrelated revalidation happens, which reads as "the text area resizes after I stop
   typing".

## What Changes

- Scope **undo/redo** (and every other file-editor-scoped action) to the box while its body is
  focused: the box's content panel becomes a `UiDataProvider` that masks
  `PlatformCoreDataKeys.FILE_EDITOR`, so the platform's own `BasicUiDataRule` re-derives a
  `TextEditor` from the box's inner editor instead of handing back the host file's editor. Ctrl+Z in
  the box undoes the comment body and never the code.
- Make the box **re-measure immediately** when its body changes: a once-per-draft `DocumentListener`
  on the retained body document revalidates the live box panel and calls `Inlay.update()`, so the box
  grows/shrinks on the same edit — including growth caused by soft-wrapping a long line, where no
  newline is typed.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `review-annotation`: the "Capture a multi-line body with submit and cancel" requirement is
  strengthened — undo/redo joins the set of keystrokes the focused box owns, and is explicitly scoped
  to the comment body so it can never modify the underlying source file. The "Author a line-anchored
  comment in an inline box" requirement is strengthened — the box's grow-with-the-body height must be
  applied on the edit that changes the body, not on a later unrelated layout pass.

## Impact

- Code: `src/main/kotlin/io/github/zerlok/agentsessionrelay/ui/CommentDraft.kt` only — a data-context
  override on the box content panel built by `buildPanel`, plus a document listener and a retained
  handle on the current box panel used by `showBox`/`hideBox`. Presentation layer only, per
  `docs/ARCHITECTURE.md`; no domain, storage, logic, export, or delivery change.
- Dependencies: none new. `UiDataProvider` / `DataSink` and `Inlay.update()` all ship with the
  2024.2.5 platform already on the classpath.
- Explicitly **out of scope** (sibling changes land these in parallel): `CommentDraftController`, the
  wash / edge-drag geometry, `StoredCommentCard`, `EditorReviewOverlay`.
- Tests: no unit test asserts the box's undo scope or measure timing — neither is reachable from the
  headless harness. The compile gate plus a running-IDE pass are the verification.
