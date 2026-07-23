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

Six further facts, established by disassembling the same jar during the post-review remediation
round. They invalidate parts of the list above, so they are recorded here rather than silently
editing it — the corrections they force are D1-R, D2-R and the Risks section.

- **A `setNull` mask defeats the very rule it was meant to invite.** `PreCachedDataContext$MySink.set`
  — the sink the *rules* write through — first walks `cachedDataForRules` (the already-cached
  per-component snapshots) and **returns without storing anything if any layer holds a non-null entry
  for that key**. `EXPLICIT_NULL` is such an entry. `BasicUiDataRule` does see `FILE_EDITOR` as null
  (`PreCachedDataContext$1.get` maps `EXPLICIT_NULL` → `null` and stops the walk there), so it tries
  the re-derivation — and its `sink.set(FILE_EDITOR, …)` is then dropped on the floor. So masking
  `FILE_EDITOR` does not merely *risk* leaving the key null; it **guarantees** it. This is decidable
  statically; it is not a running-IDE question.
- **Component snapshots are consulted nearest-first, as assumed.** The constructor builds the
  component list with `FList.createFromReversed(generate(component, ::getParent))` and
  `cacheComponentsData` prepends each layer, so the head of the list is the focus owner and
  `EXPLICIT_NULL` on the box panel really does shadow `EditorCompositePanel`. The masking half of D1
  works; only the re-derivation half does not.
- **The Swing-`UndoManager` escape hatch never fires for an IntelliJ editor.**
  `EditorComponentImpl` *does* extend `javax.swing.text.JTextComponent`, but its constructor puts
  `UndoRedoAction.IGNORE_SWING_UNDO_MANAGER = true` on itself, which is exactly the condition
  `getUndoManager` checks before taking that branch. The box's inner editor is the focus owner, so a
  null `FILE_EDITOR` always falls through to the project `UndoManager`.
- **`UndoManager.getInstance(project).undo(null)` is not a no-op — it is a global undo.**
  `UndoManagerImpl.getDocRefs(null)` returns `Collections.emptyList()`, and with empty refs
  `UndoRedoStacksHolder.canBeUndoneOrRedone` reads `myGlobalStack` and undoes its last valid group.
  Global groups are cross-file commands (refactorings, file create/delete/move, multi-file reformat).
  So the null-`FILE_EDITOR` path can and does modify source files.
- **`Inlay.update()` cannot grow the box; `revalidate()` is what does.**
  `MyRenderer.calcHeightInPixels` (and `FullEditorWidthRenderer`'s override, which just delegates)
  returns `max(getHeight(), 0)` — the renderer's *current* Swing height, not its preferred height. The
  height only changes inside `synchronizeBoundsWithInlay`, which reads `getPreferredHeight()`, calls
  `setBounds(…)` and then calls `Inlay.update()` **itself**. That method is reached from
  `MyRenderer.doLayout()`/`validate()`, i.e. from a layout pass — which `revalidate()` schedules.
  A caller-side `Inlay.update()` runs strictly *before* that pass, so it can only ever re-report the
  stale height.
- `PreCachedDataContext` is built on the EDT from whatever component
  `DataManager.getDataContext(component)` is handed, upward. For the action-system path that is the
  focus owner, so the box panel is normally snapshotted only while focus is inside the box and its
  inner editor exists. That is *not* a proof of unreachability — any caller may pass the panel
  directly — which is why the editor-less branch is kept as a tripwire rather than deleted.
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

**D1-R (supersedes D1) — Set `FILE_EDITOR` on the box's content panel to the box's *own* `TextEditor`;
mask only when there is no inner editor to name.** The box content panel built in `buildPanel` (the
anonymous `JPanel` at line 491) implements `com.intellij.openapi.actionSystem.UiDataProvider`, and its
`uiDataSnapshot(sink)` does:

```
val inner = bodyField.editor
if (inner != null) sink.set(PlatformCoreDataKeys.FILE_EDITOR, TextEditorProvider.getInstance().getTextEditor(inner))
else sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)
```

Because component snapshots are consulted nearest-first and the panel is a descendant of
`EditorCompositePanel`, this value shadows the host file's `FileEditor`, and `UndoRedoAction` — which
reads exactly that key — undoes the *comment body's* document. This is the same `TextEditor` wrapper
`BasicUiDataRule` produces for an `EditorTextField` in a dialog, where undo already works today; the
only reason the platform cannot produce it here is that the box has a `FILE_EDITOR`-providing Swing
ancestor and a mask cannot be lifted (Context: `MySink.set` drops a rule's write when any cached
layer already holds an entry). The `else` branch is unreachable in practice — `PreCachedDataContext`
snapshots from the focus owner upward (Context), so the box panel is normally reached only while
focus is inside it, which implies the inner editor exists. It is `setNull` rather
than a fall-through because handing back the *host file's* editor is the worse of the two bad
outcomes; but be clear that it is not a safe branch either — a null `FILE_EDITOR` routes undo to the
project's global stack (see Risks). It is a tripwire on an invariant, not a fallback, and if it ever
starts firing the answer is to fix the invariant. Two corollaries carry over from D1: do **not** also set
`CommonDataKeys.EDITOR` (`EditorTextField` already supplies it — Single Source of Truth), and do
**not** call `bodyField.setSupplementary(true)` — though note the *reason* has changed: under D1-R the
flag is no longer load-bearing (we no longer depend on `BasicUiDataRule` firing), it is simply a claim
that the body is not a real editing surface, which is false here. Scoping still needs no focus
listener.

- _Superseded — D1 as originally decided and shipped: `sink.setNull(FILE_EDITOR)` alone, relying on
  `BasicUiDataRule` to re-derive the box's `TextEditor`._ Disproved against 2024.2.5 bytecode:
  `PreCachedDataContext$MySink.set` refuses to store a key that any already-cached component layer
  holds an entry for, and `EXPLICIT_NULL` is such an entry — so the mask that invites the rule is
  precisely what blocks it. `FILE_EDITOR` therefore stays null, `EditorComponentImpl`'s
  `IGNORE_SWING_UNDO_MANAGER` rules out the Swing-undo branch, and `UndoRedoAction` reaches
  `UndoManager.getInstance(project).undo(null)` = **undo the last global command**, which edits source
  files. The rejected-on-Single-Source-of-Truth reasoning below was the error: `BasicUiDataRule` is not
  a source we can defer to here, because it is structurally unable to run. Rejected.
- _Alternative — implement the deprecated `DataProvider` on the panel and return `null` for
  `FILE_EDITOR`:_ `DataProvider` is still present on 242, but a classic provider returning `null`
  reads as "not provided" and the search continues to the ancestors — only `DataSink.setNull`'s
  `EXPLICIT_NULL` masks. This is the same trap `comment-box-key-capture`'s design already rejected
  for `CommonDataKeys.EDITOR`. Rejected; unreliable by construction.
- _Alternative — mark the body editor supplementary (`EditorTextField.setSupplementary(true)`):_ it
  stops the platform deriving any `FileEditor` from the box's editor, so `FILE_EDITOR` stays null and
  `UndoRedoAction` falls through to `UndoManager.getInstance(project)` with a null `FileEditor` — the
  project-global undo stack. Rejected; it trades a scoped-to-the-wrong-file undo for an unscoped one.
  (Note this is the *same* end state the superseded mask-only D1 actually produced.)
- _Alternative — register component-scoped Ctrl+Z / Ctrl+Shift+Z actions on `bodyField`, the way
  `registerShortcuts` handles Ctrl+Enter and Esc:_ that enumerates bindings the platform already owns
  (Cmd+Z, alternate keymaps, the Edit menu, the editor context menu) and can never be complete — the
  exact mistake `comment-box-key-capture` was created to stop making. Rejected.
- _Alternative — mask more keys (`VIRTUAL_FILE`, `PROJECT`, …) so the box is fully opaque:_ the box
  legitimately needs a project context (it resolves `ReviewBatchService` from `editor.project`), and
  a broader mask breaks unrelated actions for no safety gain. Rejected; override exactly
  `FILE_EDITOR`.

**D2-R (revises D2) — Re-measure the inlay from a document listener on the retained body document,
deferred to the EDT queue.** Register, once per draft, a `DocumentListener` on `bodyField.document` with the draft as
parent disposable (`bodyField.document.addDocumentListener(listener, this)`). On `documentChanged`,
schedule the re-measure through `ApplicationManager.getApplication().invokeLater { … }`, guard on the
inlay still being valid (exactly as `showBox`'s deferred focus request does at lines 382–386), then
`revalidate()` + `repaint()` the current box panel. **`revalidate()` is the whole mechanism**: it
schedules the layout pass that reaches `MyRenderer.doLayout()` → `synchronizeBoundsWithInlay()`,
which reads the panel's new preferred height, `setBounds`es the renderer and calls `Inlay.update()`
itself. The deferral is what lets the inner editor finish recomputing soft wraps first, which is what
makes the soft-wrap case (a long typed line, no newline) work. Because `showBox` builds a new panel on
every rebuild, the draft keeps a `private var boxPanel: JComponent?`, assigned in `showBox` and
cleared in `hideBox` next to `inlay = null`, so the listener always revalidates the live panel.

Parenting the listener to the draft (`addDocumentListener(listener, this)`) makes the draft a
**Disposer parent**: `DocumentImpl` registers a `DocumentListenerDisposable` child under it. Every
teardown path must therefore go through `Disposer.dispose(draft)` — calling `draft.dispose()`
directly runs the draft's own cleanup but leaves the child, and the draft's own node, in the Disposer
tree forever. `CommentDraftController.close()` is already correct; the open-failure path in
`CommentDraft.create()` is not (tasks 4.3).

- _Superseded — D2 as originally decided and shipped: `revalidate()` + `repaint()` + an explicit
  `Inlay.update()`, on the reasoning that "both calls are needed"._ Disproved against 2024.2.5
  bytecode: `MyRenderer.calcHeightInPixels` returns the renderer's *current* Swing height, so
  `Inlay.update()` re-reads whatever `synchronizeBoundsWithInlay` last set. Called from our deferred
  runnable it runs strictly before the layout pass that would change that height, so it can only
  re-report the stale value — it is inert with respect to growth, while still costing an inlay reflow
  on every keystroke. Dropped.
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
- _Alternative — `Inlay.updateSize()` instead of `Inlay.update()`:_ moot under D2-R — the draft calls
  neither; `synchronizeBoundsWithInlay()` calls `update()` on our behalf.

**D3 — Keep both fixes inside `CommentDraft.kt`; add no shared helper.** Each fix is a handful of
lines hanging off state the draft already retains (`bodyField`, `inlay`), and neither has a second
consumer: `StoredCommentCard` has no editable body, so it has no undo scope to correct and no
document to listen to. A new helper file would only widen the merge surface against the sibling
changes landing concurrently in `StoredCommentCard` / `EditorReviewOverlay` for no reuse.

## Risks / Trade-offs

- **[Overriding `FILE_EDITOR` hides the host file editor from *every* file-editor-scoped action while
  the box is focused]** (VCS "Annotate", "Close tab", editor-tab actions…) → accepted and largely
  intended: while the user is typing a comment those actions should not silently act on the host
  file. Under D1-R they now see the box's own `TextEditor` rather than nothing, which is the more
  honest answer for an action that asks "which editor is the user in?". `PROJECT` and `VIRTUAL_FILE`
  are left alone, so project-scoped actions still work. Confirm no everyday action regresses (Open
  Question 4).
- **[A null `FILE_EDITOR` is NOT a safe state — the earlier claim that "the source file is protected
  either way" was wrong]** → recorded here because it was this change's stated safety premise and it
  did not hold. With `FILE_EDITOR` null, `EditorComponentImpl` carries `IGNORE_SWING_UNDO_MANAGER`,
  so `UndoRedoAction` reaches `UndoManager.getInstance(project)` and calls `undo(null)`, which
  `UndoManagerImpl` resolves to an empty document-reference set and therefore to the project's
  **global** undo stack — undoing the last cross-file command (a refactoring, a file create/delete/
  move, a multi-file reformat). That is a larger edit to the user's code than the leak this change set
  out to fix, not a protection. D1-R removes the exposure by never leaving the key null while the box
  is focused; the residual `setNull` branch is unreachable (Context, last bullet) and deliberately
  fails loudly rather than falling back to the host file.
- **[Re-measuring on every keystroke]** → under D2-R the draft only calls `revalidate()`/`repaint()`;
  the reflow (`setBounds` + `Inlay.update()`) is performed by `synchronizeBoundsWithInlay` and **only
  when the height actually changed**, so the platform already coalesces the common case. Mitigation
  kept in reserve if a validate pass per keystroke still costs too much: skip the revalidate when the
  panel's preferred height is unchanged since the last measure (tasks.md 2.4).
- **[The deferred measure can run after submit/cancel disposed the draft]** → guard on
  `inlay?.isValid`, the same guard `showBox`'s deferred focus request uses.
- **[Both fixes are attached to per-rebuild state]** → the provider rides on the panel (rebuilt with
  it, so always current) and the listener rides on the retained document (registered once, parented to
  the draft). Verify explicitly after an edge-drag (Open Question 5).
- **[Parenting the document listener to the draft turns the draft into a Disposer parent]** → any
  `draft.dispose()` that is not routed through `Disposer.dispose(draft)` leaks the listener child and
  the draft node. See D2-R; fixed on the open-failure path by tasks 4.3.

## Open Questions

Nothing below can be settled here — this machine has no display and `runIde` is impossible, so the
only local evidence is `compileKotlin`, the unit suite, and reading the platform's own bytecode. Each
must be answered in a running IDE and the answer recorded back into this section.

> **Answered statically (remediation round).** The original Open Question 1 — "does `setNull` let
> `BasicUiDataRule` re-derive `FILE_EDITOR`?" — is decidable from `PreCachedDataContext$MySink.set`
> and the answer is **no**, always. It is not an open question and was never a running-IDE question;
> see D1-R and the Context facts. The same round settled that a caller-side `Inlay.update()` cannot
> grow the box (D2-R). Neither is listed below.

1. With D1-R in place, does undo inside the box act on the **comment body** — i.e. does the platform
   record undoable actions for an `EditorFactory`-created document that has no `VirtualFile`, exactly
   as it does for an `EditorTextField` in a dialog? Test: type in the box, Ctrl+Z then Ctrl+Shift+Z,
   and check both (a) the source file is untouched — the release blocker — and (b) the box's own text
   undoes and redoes.
2. Is one deferred `invokeLater` measure enough for the soft-wrap case, or does the wrapped height
   settle a frame later (needing a second pass, or the rejected `SoftWrapChangeListener`)? Test: type
   one long unbroken line into the box and watch whether the box grows on the wrapping character.
3. Does the box also *shrink* promptly when body lines are deleted (down to the `BODY_ROWS` floor),
   or does only growth propagate?
4. Does pointing `FILE_EDITOR` at the box's own editor regress any action the user reasonably invokes
   with the box focused (Save All, Find in Files, the tool window's own actions)?
5. Does the undo scoping and the live resize both still hold after an edge-drag rebuild
   (`hideBox`/`showBox`), where the panel — and with it the data provider — is a new instance?
6. Does a validate pass per keystroke introduce perceptible typing latency in a large file, i.e. is
   the coalescing in tasks.md 2.4 needed?
7. Is the deferred `revalidate()` still load-bearing once the inner editor exists? Observed while
   writing the regression tests: with the body's inner editor built, a document change already
   invalidates the box panel *synchronously* (the inner editor's own components revalidate and
   `invalidate()` propagates up through the panel). Whether that reaches a validate root at or above
   `MyRenderer` — i.e. whether the platform would have re-measured the box on its own, and the
   observed "resizes after I stop typing" has another cause — cannot be decided headlessly, because
   `RepaintManager` never validates an undisplayable hierarchy. Test: revert only the document
   listener in a running IDE and watch whether the box still grows on the keystroke. If it does, this
   change's defect-B half is unnecessary and should be dropped rather than kept "just in case".
