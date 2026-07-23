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
  focused: the box's content panel becomes a `UiDataProvider` that supplies
  `PlatformCoreDataKeys.FILE_EDITOR` as the box's *own* `TextEditor`, shadowing the host file's editor
  for anything below the panel. Ctrl+Z in the box undoes the comment body and never the code.
- Make the box **re-measure immediately** when its body changes: a once-per-draft `DocumentListener`
  on the retained body document revalidates the live box panel, so the platform's embedded-component
  renderer re-measures and the box grows/shrinks on the same edit — including growth caused by
  soft-wrapping a long line, where no newline is typed.

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
- Dependencies: none new. `UiDataProvider` / `DataSink` and `TextEditorProvider.getTextEditor` all
  ship with the 2024.2.5 platform already on the classpath.
- Explicitly **out of scope** (sibling changes land these in parallel): `CommentDraftController`, the
  wash / edge-drag geometry, `StoredCommentCard`, `EditorReviewOverlay`.
- Tests: `src/test/kotlin/io/github/zerlok/agentsessionrelay/ui/CommentDraftTest.kt` (new) opens a
  real draft over a `BasePlatformTestCase` editor fixture and asserts the parts of both fixes that a
  headless harness can reach: **what the box panel contributes to the action system** (`FILE_EDITOR` =
  the wrapper around the *body's* editor and nothing else, on the first box and on the panel rebuilt
  by an edge-drag, plus the editor-less branch's mask), and **what a body edit does to the box** (it
  revalidates the *current* box panel — the panel is re-validated between the edit and the pump, so
  only the draft's own deferred call can invalidate it — and that deferred call drops harmlessly when
  the box was closed in between). What it does **not** prove, and must not claim to: end-to-end
  `DataContext` resolution — that the panel's `FILE_EDITOR` really wins the walk, and that undo then
  reaches the body's document — and that the box actually grows, since `revalidate()` only *schedules*
  the renderer layout pass and nothing lays components out without a display. Those stay running-IDE
  checks in tasks 3.3/4.7 and design.md "## Open Questions".
