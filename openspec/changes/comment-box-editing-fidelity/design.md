## Context

Everything here happens inside `ui/CommentDraft.kt`. The relevant shape of that file today:

- The body is an `EditorTextField` (line 104) constructed over an `EditorFactory` document, with
  `isUseSoftWraps = true` (line 120) and a `getPreferredSize` override (lines 111–116) that floors the
  height at `lineHeight * BODY_ROWS` (`BODY_ROWS = 2`, line 413) and otherwise grows with content.
- `buildPanel` (lines 475–524) wraps that field in an anonymous `object : JPanel(BorderLayout(...))`
  whose `getPreferredSize` pins the *width* to `InlineWidth.baseWidthPx(editor)` and leaves the height
  content-driven, then returns `InlineWidth.capWidth(content, cap)`.
- `showBox` (lines 345–388) hands that panel to
  `EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, properties)` with
  `fullWidth = true` and stores the resulting `Inlay` in `inlay` (line 366). It is called once on open
  and again on every edge-drag release; `hideBox` (lines 391–394) disposes the inlay.
- `registerShortcuts` (lines 526–561) is deliberately a **once-per-draft** registration on the
  *retained* `bodyField`, parented to the draft, precisely because `showBox`/`hideBox` tear the panel
  and the inner editor down and rebuild them (the KDoc at lines 532–540 spells this out). The
  `shortcutsRegistered` flag (lines 371–374) enforces it.

Facts established against the PyCharm CE 2024.2.5 SDK actually on the classpath (read out of
`lib/app-client.jar` of the `pycharm-community-2024.2.5` distribution in the Gradle cache):

- `com.intellij.openapi.actionSystem.UiDataProvider` **exists** on 242, with
  `uiDataSnapshot(DataSink)`. `DataSink` offers `set`, `setNull`, `lazy`, `lazyNull`. The deprecated
  `com.intellij.openapi.actionSystem.DataProvider` also still exists, and
  `UiCompatibleDataProvider` bridges the two.
- `DataSink.setNull(key)` stores `CustomizedDataContext.EXPLICIT_NULL` in the per-component snapshot
  (`PreCachedDataContext$MySink.setNull`), which `PreCachedDataContext.getDataInner` recognises as a
  real masking null. A plain `DataProvider.getData(...) == null` does not: it reads as "not provided"
  and the walk continues to the ancestors.
- `EditorTextField` **already implements `UiCompatibleDataProvider`**, and its `uiDataSnapshot` sets
  `CommonDataKeys.EDITOR` to its own inner editor (and `COPY_PROVIDER` in renderer mode). Nothing
  else. That is the mechanism `2026-07-06-comment-box-key-capture` bought; it does not touch
  `FILE_EDITOR`.
- `com.intellij.openapi.fileEditor.impl.EditorCompositePanel` is a `UiDataProvider` and sets
  `PlatformCoreDataKeys.FILE_EDITOR = composite.selectedEditor` (plus `PROJECT`, `VIRTUAL_FILE`,
  `VIRTUAL_FILE_ARRAY`). It is a Swing ancestor of the host editor's content component, hence of the
  block inlay, hence of the body field. `TextEditorComponent` (nearer) supplies `EDITOR`, `CARET`,
  `VIRTUAL_FILE` — not `FILE_EDITOR`.
- `com.intellij.ide.actions.UndoRedoAction` resolves `PlatformCoreDataKeys.FILE_EDITOR` from the
  action's `DataContext` and performs undo/redo against it. When that key is null it falls back to a
  Swing `UndoManager` for a `JTextComponent` context, else to `UndoManager.getInstance(project)` with
  a null `FileEditor` — i.e. the *project-global* undo stack.
- `com.intellij.ide.impl.dataRules.BasicUiDataRule` is a platform `UiDataRule` that, given
  `EDITOR != null` and `editor.getUserData(EditorTextField.SUPPLEMENTARY_KEY) != true`, fills
  `FILE_EDITOR` **when the snapshot's value is null** with
  `TextEditorProvider.getInstance().getTextEditor(editor)`.
- `EditorEmbeddedComponentManager$MyRenderer.validate()` calls `synchronizeBoundsWithInlay()`, which
  recomputes the renderer's bounds from the embedded component's preferred size and, when they
  changed, does `setBounds(...)` followed by `Inlay.update()`. `Inlay.update()` and
  `Inlay.updateSize()` both exist on 242.
- `EditorTextField.documentChanged` only forwards the event to listeners registered via
  `addDocumentListener`; it does **not** call `revalidate()`.
- `Document.addDocumentListener(DocumentListener, Disposable)` exists, so a listener can be parented
  to the draft.

Constraint: this change stays in `ui/` (`docs/ARCHITECTURE.md`), on the EDT, and must compose with
`2026-07-06-comment-box-key-capture` (D1/D4) and `2026-07-05-adjustable-comment-range` (D1) rather
than undo either. Sibling changes are landing concurrently in `CommentDraftController`, the wash/edge
geometry, `StoredCommentCard` and `EditorReviewOverlay`; this change must not touch them.

## Goals / Non-Goals

**Goals:**

- Ctrl+Z / Ctrl+Shift+Z (and every other binding of undo/redo, on every keymap) while the comment body
  is focused must never modify the underlying source file.
- Undo/redo while the body is focused should act on the comment body's own edit history.
- Focus back in the host editor must restore normal host-file undo, with no explicit focus wiring.
- The box must grow (and shrink) on the same edit that changes the body's line count — including
  soft-wrap-induced growth, where no newline is typed.
- Both fixes must survive the edge-drag `hideBox`/`showBox` rebuild.

**Non-Goals:**

- Re-implementing undo. The platform owns undo; this change only corrects which editor it is scoped to.
- Any change to the wash, the edge-drag gesture, range geometry, the stored-comment card, or the
  overlay.
- Any change to `BODY_ROWS`, the width cap, or `InlineWidth` — the *sizing policy* from
  `2026-07-06-comment-box-sizing` is correct; only its *propagation timing* is at fault.
- Domain / storage / logic / export / delivery: untouched.

## Decisions

**D1 — Mask `FILE_EDITOR` on the box's content panel with a `UiDataProvider`, and let the platform
re-derive it from the box's own editor.** The box content panel built in `buildPanel` (the anonymous
`JPanel` at line 491) additionally implements `com.intellij.openapi.actionSystem.UiDataProvider`, and
its `uiDataSnapshot(sink)` calls `sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)`. Because the panel
is a Swing ancestor of the focused inner editor but a descendant of `EditorCompositePanel`, the
nearer explicit null masks the host file's `FileEditor`. `BasicUiDataRule` then sees a null
`FILE_EDITOR` alongside `EDITOR` = the box's inner editor (supplied by `EditorTextField` itself) and
fills in `TextEditorProvider.getInstance().getTextEditor(innerEditor)`, so undo/redo scopes to the
box's own document. Two corollaries: (a) do **not** also set `CommonDataKeys.EDITOR` — `EditorTextField`
already does, and restating it violates Single Source of Truth; (b) do **not** call
`bodyField.setSupplementary(true)` — `SUPPLEMENTARY_KEY` is exactly the flag that switches
`BasicUiDataRule`'s re-derivation off. Scoping needs no focus listener: the panel is only visited
when the focus owner is inside the box, so focus in the host editor never sees the mask.

- _Alternative — implement the deprecated `DataProvider` on the panel and return `null` for
  `FILE_EDITOR`:_ `DataProvider` is still present on 242, but a classic provider returning `null`
  reads as "not provided" and the search continues to the ancestors — only `DataSink.setNull`'s
  `EXPLICIT_NULL` masks. This is the same trap `comment-box-key-capture`'s design already rejected
  for `CommonDataKeys.EDITOR`. Rejected; unreliable by construction.
- _Alternative — mark the body editor supplementary (`EditorTextField.setSupplementary(true)`):_ it
  stops the platform deriving any `FileEditor` from the box's editor, so `FILE_EDITOR` stays null and
  `UndoRedoAction` falls through to `UndoManager.getInstance(project)` with a null `FileEditor` — the
  project-global undo stack. Rejected; it trades a scoped-to-the-wrong-file undo for an unscoped one.
- _Alternative — register component-scoped Ctrl+Z / Ctrl+Shift+Z actions on `bodyField`, the way
  `registerShortcuts` handles Ctrl+Enter and Esc:_ that enumerates bindings the platform already owns
  (Cmd+Z, alternate keymaps, the Edit menu, the editor context menu) and can never be complete — the
  exact mistake `comment-box-key-capture` was created to stop making. Rejected.
- _Alternative — set `FILE_EDITOR` ourselves to `TextEditorProvider.getInstance().getTextEditor(bodyField.editor)`
  instead of masking:_ duplicates what `BasicUiDataRule` already does, and has to cope with
  `bodyField.editor` being null before the field is shown. Rejected on Single Source of Truth — but
  kept as the documented fallback if the running IDE shows the rule does not fire (Open Question 1).
- _Alternative — mask more keys (`VIRTUAL_FILE`, `PROJECT`, …) so the box is fully opaque:_ the box
  legitimately needs a project context (it resolves `ReviewBatchService` from `editor.project`), and
  a broader mask breaks unrelated actions for no safety gain. Rejected; mask exactly `FILE_EDITOR`.

**D2 — Re-measure the inlay from a document listener on the retained body document, deferred to the
EDT queue.** Register, once per draft, a `DocumentListener` on `bodyField.document` with the draft as
parent disposable (`bodyField.document.addDocumentListener(listener, this)`). On `documentChanged`,
schedule the re-measure through `ApplicationManager.getApplication().invokeLater { … }`, guard on the
inlay still being valid (exactly as `showBox`'s deferred focus request does at lines 382–386), then
`revalidate()` + `repaint()` the current box panel and call `Inlay.update()`. `revalidate` alone is
not enough to guarantee promptness and `Inlay.update()` alone does not re-run the panel's layout, so
both are needed; the deferral is what lets the inner editor finish recomputing soft wraps first, which
is what makes the soft-wrap case (a long typed line, no newline) work. Because `showBox` builds a new
panel on every rebuild, the draft keeps a `private var boxPanel: JComponent?`, assigned in `showBox`
and cleared in `hideBox` next to `inlay = null`, so the listener always revalidates the live panel.

- _Alternative — register the listener on the inner editor (`bodyField.editor`) or on its
  `contentComponent`:_ `EditorTextField` creates the inner editor in `addNotify` and releases it in
  `removeNotify` (`releaseEditorLater`), so it is destroyed and recreated on every `hideBox`/`showBox`.
  The registration would have to be redone per rebuild — the precise trap `registerShortcuts`'
  KDoc documents and resolves by registering on the retained wrapper. Rejected.
- _Alternative — add a `SoftWrapChangeListener` on the inner editor's `SoftWrapModelEx` alongside the
  document listener:_ same per-rebuild lifetime problem, and a deferred measure after the document
  change already observes the post-wrap preferred size. Rejected for now; revisit only if Open
  Question 3 shows a wrap-only change slipping through.
- _Alternative — call `revalidate()` from inside the `getPreferredSize` override:_ mutating layout
  state from a measurement call is re-entrant and Swing does not tolerate it. Rejected.
- _Alternative — a `ComponentListener` on the body field reacting to `componentResized`:_ that fires
  *after* a layout pass already used the stale size — it is the symptom, not the trigger. Rejected.
- _Alternative — `Inlay.updateSize()` instead of `Inlay.update()`:_ `updateSize()` is the narrower
  default method; `update()` is what the platform's own `synchronizeBoundsWithInlay()` calls after a
  bounds change, so it is the behaviour already proven in this code path. Rejected in favour of
  matching the platform.

**D3 — Keep both fixes inside `CommentDraft.kt`; add no shared helper.** Each fix is a handful of
lines hanging off state the draft already retains (`bodyField`, `inlay`), and neither has a second
consumer: `StoredCommentCard` has no editable body, so it has no undo scope to correct and no
document to listen to. A new helper file would only widen the merge surface against the sibling
changes landing concurrently in `StoredCommentCard` / `EditorReviewOverlay` for no reuse.

## Risks / Trade-offs

- **[Masking `FILE_EDITOR` hides the host file editor from *every* file-editor-scoped action while the
  box is focused]** (VCS "Annotate", "Close tab", editor-tab actions…) → accepted and largely
  intended: while the user is typing a comment those actions should not silently act on the host
  file. `PROJECT` and `VIRTUAL_FILE` are left alone, so project-scoped actions still work. Confirm no
  everyday action regresses (Open Question 4).
- **[If `BasicUiDataRule` does not re-derive the box's `FileEditor`, undo inside the box becomes a
  no-op or a project-global undo]** → the source file is protected either way, so the safety-critical
  half of this change holds; the documented fallback in D1 restores in-box undo. Tracked as Open
  Question 1 and as an explicit tasks.md branch.
- **[Re-measuring on every keystroke]** → `Inlay.update()` is a bounded reflow that the platform
  already performs on each box rebuild, but on a large file it is not free. Mitigation kept in
  reserve: skip the update when the panel's preferred height is unchanged since the last measure
  (tasks.md 2.4).
- **[The deferred measure can run after submit/cancel disposed the draft]** → guard on
  `inlay?.isValid`, the same guard `showBox`'s deferred focus request uses.
- **[Both fixes are attached to per-rebuild state]** → the provider rides on the panel (rebuilt with
  it, so always current) and the listener rides on the retained document (registered once, parented to
  the draft). Verify explicitly after an edge-drag (Open Question 5).

## Open Questions

Nothing below can be settled here — this machine has no display and `runIde` is impossible, so the
only local evidence is `compileKotlin` plus the unit suite. Each must be answered in a running IDE and
the answer recorded back into this section.

1. Does `sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)` on the box panel actually let
   `BasicUiDataRule` fill `FILE_EDITOR` with `TextEditorProvider.getTextEditor(innerEditor)`, or does
   the `EXPLICIT_NULL` survive the rule and leave the key null? Test: type in the box, press Ctrl+Z
   and Ctrl+Shift+Z, and check both (a) the source file is untouched — the safety requirement — and
   (b) the box's own text undoes/redoes. If only (a) holds, apply D1's documented fallback and set
   `FILE_EDITOR` explicitly from `bodyField.editor`.
2. Is one deferred `invokeLater` measure enough for the soft-wrap case, or does the wrapped height
   settle a frame later (needing a second pass, or the rejected `SoftWrapChangeListener`)? Test: type
   one long unbroken line into the box and watch whether the box grows on the wrapping character.
3. Does the box also *shrink* promptly when body lines are deleted (down to the `BODY_ROWS` floor),
   or does only growth propagate?
4. Does masking `FILE_EDITOR` regress any action the user reasonably invokes with the box focused
   (Save All, Find in Files, the tool window's own actions)?
5. Does the undo scoping and the live resize both still hold after an edge-drag rebuild
   (`hideBox`/`showBox`), where the panel — and with it the data provider — is a new instance?
6. Does the per-keystroke `Inlay.update()` introduce perceptible typing latency in a large file, i.e.
   is the coalescing in tasks.md 2.4 needed?
