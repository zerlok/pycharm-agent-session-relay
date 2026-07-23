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
- [x] 1.2 Do **not** set `CommonDataKeys.EDITOR` in that snapshot: `EditorTextField` already
      implements `UiCompatibleDataProvider` and supplies its own inner editor for that key. Add a
      short "why not what" KDoc/comment on the override naming the leak it closes (the host file's
      `FileEditor` from `EditorCompositePanel`, consumed by `UndoRedoAction`) and referencing design
      D1 — do not restate the mechanism at length.
- [x] 1.3 Do **not** call `bodyField.setSupplementary(true)`. Record, in one comment line, that
      `EditorTextField.SUPPLEMENTARY_KEY` is exactly the flag that disables the platform's
      `BasicUiDataRule` re-derivation of `FILE_EDITOR` from the box's editor — which is the behaviour
      this fix depends on. (Recorded as the closing sentence of the `uiDataSnapshot` KDoc rather than a
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
      `boxPanel?.revalidate()`, `boxPanel?.repaint()`, and `inlay?.update()`.
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
- [ ] 3.4 If Open Question 1 comes back as "source file safe, but in-box undo does nothing", apply
      design D1's fallback — set `PlatformCoreDataKeys.FILE_EDITOR` explicitly from
      `TextEditorProvider.getInstance().getTextEditor(bodyField.editor)` (guarding the null editor
      before the field is shown) instead of only masking — and re-run 3.1/3.3.
