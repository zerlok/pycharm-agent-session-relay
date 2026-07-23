## 0. Orientation

- [x] 0.1 Work only in the worktree at `/home/claude_zerlok/dev/relay-wt-box` on branch
      `feat/comment-box-editing-fidelity`. Do not touch the other `relay-wt-*` worktrees.
- [x] 0.2 Read `src/main/kotlin/io/github/zerlok/agentsessionrelay/ui/CommentDraft.kt` end to end,
      plus `openspec/changes/archive/2026-07-06-comment-box-key-capture/design.md` (D1/D2/D4) — this
      change must compose with that one, not undo it.
- [x] 0.3 Scope discipline: `CommentDraft.kt` is the only file to modify. Do **not** touch
      `CommentDraftController`, the wash / edge-drag geometry, `StoredCommentCard`,
      `EditorReviewOverlay`, `InlineWidth`, or `BODY_ROWS` — sibling changes own those and are landing
      in parallel.

## 1. Scope undo/redo to the comment box (defect A, safety-critical)

- [x] 1.1 In `CommentDraft.buildPanel`, make the box content panel — the anonymous
      `object : JPanel(BorderLayout(0, JBUI.scale(6)))` that already overrides `getPreferredSize`
      (around line 491) — additionally implement
      `com.intellij.openapi.actionSystem.UiDataProvider`, overriding
      `uiDataSnapshot(sink: DataSink)` to call `sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)`.
      (**The `setNull`-only body is superseded by 4.1** — the panel must *supply* the box's own
      `TextEditor`, not mask. The `UiDataProvider` on that panel is still right.)
- [x] 1.2 Do **not** set `CommonDataKeys.EDITOR` in that snapshot: `EditorTextField` already
      implements `UiCompatibleDataProvider` and supplies its own inner editor for that key. Add a
      short "why not what" KDoc/comment on the override naming the leak it closes (the host file's
      `FileEditor` from `EditorCompositePanel`, consumed by `UndoRedoAction`) and referencing design
      D1 — do not restate the mechanism at length. (The "don't set `EDITOR`" rule survives into D1-R;
      the KDoc's re-derivation story does not — see 4.2.)
- [x] 1.3 Do **not** call `bodyField.setSupplementary(true)`. Record, in one comment line, that
      `EditorTextField.SUPPLEMENTARY_KEY` is exactly the flag that disables the platform's
      `BasicUiDataRule` re-derivation of `FILE_EDITOR` from the box's editor — which is the behaviour
      this fix depends on. (**The "depends on" clause is superseded by 4.1/D1-R**: the fix supplies
      `FILE_EDITOR` itself and no longer relies on that rule; the "don't mark it supplementary"
      instruction still stands, for the reason in D1-R.) (Recorded as the closing sentence of the `uiDataSnapshot` KDoc rather than a
      standalone comment line — it is the same fact as 1.2's "don't set `EDITOR`", so keeping both in
      one place avoids a second, drifting copy.)
- [x] 1.4 Compile gate: `./gradlew compileKotlin --offline` from the worktree root. This is the only
      local evidence that `UiDataProvider`, `DataSink.setNull` and `PlatformCoreDataKeys.FILE_EDITOR`
      resolve on the 2024.2.5 classpath. If `UiDataProvider` does not resolve, **stop and report** —
      do not silently substitute the deprecated `DataProvider` returning `null`, which does not mask
      (design D1, first rejected alternative).

## 2. Re-measure the box as the body changes (defect B)

- [x] 2.1 Add `private var boxPanel: JComponent? = null` to `CommentDraft`. Assign it in `showBox()`
      to the panel handed to `EditorEmbeddedComponentManager.addComponent` (only after `addComponent`
      returns non-null, alongside `inlay = newInlay`), and clear it in `hideBox()` next to
      `inlay = null`. (The panel handed to `addComponent` is the `InlineWidth.capWidth` *wrapper*, not
      the inner `content` panel that carries the `UiDataProvider`; revalidating the wrapper is what the
      inlay renderer measures, so that is the handle retained.)
- [x] 2.2 Register a **once-per-draft** `DocumentListener` on the retained `bodyField.document`,
      parented to the draft: `bodyField.document.addDocumentListener(listener, this)`. Register it
      where it runs exactly once (e.g. the `init` block, or guarded by the same style of flag as
      `shortcutsRegistered`), never inside the per-rebuild body of `showBox` without a guard — the
      reasoning is the same one `registerShortcuts`' KDoc already documents (the panel and the inner
      editor are torn down and rebuilt on every edge-drag; the field and its document are not).
- [x] 2.3 In `documentChanged`, schedule the re-measure with
      `ApplicationManager.getApplication().invokeLater { … }` so the inner editor has finished
      recomputing soft wraps; inside, bail out unless the inlay is still valid (mirror the
      `if (!newInlay.isValid) return@invokeLater` guard in `showBox`), then
      `boxPanel?.revalidate()`, `boxPanel?.repaint()`, and `inlay?.update()`. (**The `inlay?.update()`
      is superseded by 4.4** — it cannot grow the box; `revalidate()` is the mechanism.)
- [ ] 2.4 (Only if 3.3 reports typing latency) Coalesce: remember the last measured preferred height
      of `boxPanel` and skip the revalidate/update when it is unchanged.
- [x] 2.5 Keep the whole path on the EDT and use no `WriteCommandAction` — Relay's own state is never
      wrapped in one (`docs/ARCHITECTURE.md`, threading rules).

## 3. Verify

- [x] 3.1 `./gradlew compileKotlin --offline` — clean.
- [x] 3.2 `./gradlew test` (needs network for junit4 + opentest4j) — the suite must stay green. No
      existing test asserts the box's undo scope or measure timing, so none should need editing; if
      one does, say why in the commit rather than loosening the assertion. (Ran here — network was
      available; `BUILD SUCCESSFUL`, no test needed editing.)
- [ ] 3.3 Running-IDE checklist — **cannot be run on this machine** (no display, `runIde` impossible).
      Hand it to the user and write the outcomes back into `design.md` "## Open Questions" rather than
      claiming any of it as verified:
      - type in the box, press Ctrl+Z / Ctrl+Shift+Z → the source file is unchanged (this is the
        release blocker) and the box's own text undoes/redoes (Open Question 1);
      - click into the host editor, press Ctrl+Z → normal host-file undo is back;
      - press Enter in the box → the box grows on that keystroke and the code below reflows
        immediately;
      - type one long unbroken line → the box grows on the character that wraps (Open Question 2);
      - delete body lines → the box shrinks back toward the `BODY_ROWS` floor (Open Question 3);
      - edge-drag the range, release, then repeat the undo and growth checks → both still hold on the
        rebuilt box (Open Question 5);
      - type quickly in a large file → no perceptible latency (Open Question 6); if there is, enable
        task 2.4.
- [x] 3.4 ~~If Open Question 1 comes back as "source file safe, but in-box undo does nothing", apply
      design D1's fallback…~~ **Superseded by 4.1.** The premise was that the mask *might* not let
      `BasicUiDataRule` fire. It provably never can (design D1-R), so the fallback is no longer a
      contingency to run in a living IDE — it is the fix, unconditionally.

## 4. Remediation (post-review round; supersedes parts of 1 and 2)

Four MAJOR review findings were confirmed against the 2024.2.5 bytecode on the Gradle classpath. The
design record is already corrected (`design.md` D1-R, D2-R, Risks, Open Questions; `proposal.md`
Impact). The delta spec in `specs/review-annotation/spec.md` is **unchanged and stays unchanged** —
its scenarios state the required *behaviour*, which was right all along; only the mechanism chosen to
deliver it was wrong. These tasks are the code side.

- [x] 4.1 **(release blocker — the change does not currently meet its own safety scenario.)** In the
      box panel's `uiDataSnapshot`, replace the bare `sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)`
      with: read `bodyField.editor`; when it is non-null,
      `sink.set(PlatformCoreDataKeys.FILE_EDITOR, TextEditorProvider.getInstance().getTextEditor(it))`;
      only when it is null, `sink.setNull(...)`. Do not add a focus check — the snapshot is only taken
      while focus is inside the box. Reason, in full, is design D1-R: `PreCachedDataContext$MySink.set`
      refuses to store a key that any already-cached component layer holds an entry for, and
      `EXPLICIT_NULL` *is* such an entry, so the mask that was supposed to invite `BasicUiDataRule`'s
      re-derivation is exactly what blocks it — leaving `FILE_EDITOR` null, which routes
      `UndoRedoAction` to `UndoManager.getInstance(project).undo(null)`, i.e. the project's **global**
      undo stack. Ctrl+Z in the box can currently revert a refactoring.
      (Re-verified independently before applying: `PreCachedDataContext$MySink.set` bytecode 163–226
      iterates `cachedDataForRules` and `return`s on the first layer holding an entry for the key;
      `runSnapshotRules`' lambda sets that field around every `UiDataRule`; `PreCachedDataContext$1.get`
      maps `EXPLICIT_NULL` → null. `UndoRedoAction.getUndoManager` takes the Swing branch only when
      `IGNORE_SWING_UNDO_MANAGER` is absent, and `EditorComponentImpl` sets it. Finding confirmed.)
- [x] 4.2 Rewrite that override's KDoc to match. It must no longer say the mask lets `BasicUiDataRule`
      re-derive the key — that claim is false on this platform. Say instead, densely: the box is a
      block inlay inside the host editor's content component, so the action system would otherwise
      resolve `FILE_EDITOR` to `EditorCompositePanel`'s host-file editor and `UndoRedoAction` would
      undo the user's code; the panel supplies the box's own `TextEditor` instead, which is the same
      wrapper the platform derives for an `EditorTextField` in a dialog. Keep the two existing "why
      not" notes (don't set `EDITOR`; never mark the body supplementary) and add one line for why the
      null branch is `setNull` rather than a silent fall-through — reference D1-R, do not restate it.
- [x] 4.3 Fix the Disposer child leak on the open-failure path: `CommentDraft.create()` calls
      `draft.dispose()` directly when `showBox()` fails. Since 2.2 added
      `bodyField.document.addDocumentListener(listener, this)`, `DocumentImpl` registers a
      `DocumentListenerDisposable` as a Disposer **child** of the draft, so a direct `dispose()` leaves
      both the child and the draft's own node in the Disposer tree forever. Use
      `Disposer.dispose(draft)`. Then audit the file for any other direct `.dispose()` on a
      `Disposable`: at the time of writing `hideBox()` and `CommentDraftController.close()` already use
      `Disposer.dispose`, and `create()` is the only offender — confirm that still holds.
      (Confirmed: `DocumentImpl.addDocumentListener(l, parent)` really does
      `Disposer.register(parent, DocumentListenerDisposable)`. Audit of `src/main` leaves one other
      direct `.dispose()` — `EditorReviewOverlay`'s `hoverHighlight?.dispose()` — which is *not* a
      `Disposable`: `RangeHighlight` is a plain class with its own `dispose()` and no Disposer node.
      `create()` was the only offender.)
- [x] 4.4 Drop the `current.update()` call from `scheduleRemeasure` and rewrite its KDoc. Per design
      D2-R: `MyRenderer.calcHeightInPixels` returns the renderer's *current* Swing height, and the only
      thing that changes that height is `synchronizeBoundsWithInlay`, which reads
      `getPreferredHeight()`, calls `setBounds(…)` and then calls `Inlay.update()` **itself** — reached
      from `MyRenderer.doLayout()`/`validate()`, i.e. from the layout pass `revalidate()` schedules.
      Our call runs strictly before that pass, so it can only re-report the stale height while costing
      an extra inlay repaint per keystroke. The KDoc must name `revalidate()` → the renderer's layout →
      `synchronizeBoundsWithInlay` as the mechanism, and must not claim "both calls are needed".
      (Re-verified: `MyRenderer.calcHeightInPixels` is `max(getHeight(), 0)`, and
      `synchronizeBoundsWithInlay` — reached from `validate()`/`doLayout()` — is the only caller of
      `setBounds` and it calls `Inlay.update()` itself. Finding confirmed.)
- [x] 4.5 Re-point `src/test/kotlin/io/github/zerlok/agentsessionrelay/ui/CommentDraftTest.kt`. Keep
      the file — it is in scope — but stop it asserting things the harness cannot show:
      - the two `uiDataSnapshot` tests: update them for 4.1. Note that headlessly an `EditorTextField`
        is never shown, so `bodyField.editor` is null and the tests exercise the **unreachable-in-
        practice `setNull` branch**; say so in the KDoc rather than letting the unchanged assertions
        read as proof of the shipped behaviour. If the inner editor can be forced into existence in
        this fixture (try `bodyField.addNotify()`), prefer asserting the primary branch: the value is a
        `TextEditor` whose `getEditor()` is the body field's own editor, and nothing else is set. If it
        cannot, keep the mask-branch assertion and record the gap in the KDoc.
      - `test a body edit re-measures the box inlay` and `…the rebuilt box after an edge drag`: these
        assert that `Inlay.update()` propagates a renderer size the test set by hand (`growRenderer`) —
        i.e. they pin the call 4.4 deletes, and the size change they observe is the test's own, not the
        body's. Remove them, or rewrite them to assert only wiring the harness really reaches. Do not
        keep `growRenderer`-style scaffolding to preserve a green assertion.
      - `test closing the box before the deferred measure is harmless` stays as is: the deferred
        runnable dropping cleanly after a close is real, ours, and observable.
      - the class KDoc: it currently frames the growth test as testing "the re-measure a body edit
        triggers on the live inlay". After 4.4 nothing about the box's growth is observable without a
        display — the `revalidate()` → renderer path cannot run headlessly. State that plainly and
        point at design.md "## Open Questions".
      (Done as follows. The inner editor *can* be forced in this fixture — `bodyField.addNotify()`
      alone does not (the `editor.text.field.init.on.shown` registry flag is on), but the public
      `getEditor(true)` then does — so the two snapshot tests assert the **primary** branch: the value
      is a `TextEditor` whose `getEditor()` is the body's own editor, and it is the only entry. The
      `setNull` branch keeps a third, explicitly-labelled test so its *intent* is pinned. Forced
      editors are released in `tearDown` (`removeNotify` + pump), or the fixture's leak check trips.
      The two growth tests were rewritten rather than removed: `growRenderer` is gone, and they now
      assert the mechanism 4.4 leaves in place — a body edit revalidates the **current** box panel.
      They are non-vacuous: with `documentChanged` stubbed to `Unit` both fail, which is why the
      helper drains the queue before measuring and the panel is re-validated between the edit and the
      pump (a document change also invalidates the box synchronously via the inner editor — see
      design.md Open Question 7).)
- [x] 4.6 Re-run the gates: `./gradlew compileKotlin --offline`, then `./gradlew test`. If 4.1 does not
      compile because `TextEditorProvider` is not resolvable from `ui/`, **stop and report** — do not
      fall back to the mask, which is the defect being fixed. (`TextEditorProvider` resolves from
      `ui/`; both gates green — `compileKotlin --offline` clean, 85 tests pass.)
- [ ] 4.7 The running-IDE checklist in 3.3 still stands, with the first item now the acceptance gate
      for 4.1: type in the box, Ctrl+Z / Ctrl+Shift+Z, and confirm (a) the source file is untouched and
      (b) the box's own text undoes and redoes (design.md Open Question 1). Record the outcome in
      "## Open Questions", not here.
