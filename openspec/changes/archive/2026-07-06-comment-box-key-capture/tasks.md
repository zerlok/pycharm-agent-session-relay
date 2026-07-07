## 1. Swap the body component to a multiline EditorTextField

- [x] 1.1 Replace the retained `JBTextArea` field in `CommentDraft` with a retained multiline
      `EditorTextField` (plain-text file type, project-bound), constructed with `oneLineMode = false`.
- [x] 1.2 Add an `addSettingsProvider { … }` that enables soft wraps and sets a preferred visible row
      count / minimum height so the box keeps its current ~4-row footprint.
- [x] 1.3 Update `buildPanel` to embed the `EditorTextField` (replacing the `JBScrollPane(textArea)`)
      and repoint the panel's `mousePressed` handler to focus the `EditorTextField` on box clicks (D3).
      _Follow-up (sandbox test): a bare multiline `EditorTextField` draws no border, so it blended into
      the panel. Restored the framed "white input inside the gray box" look with a 1px `JBColor.border()`
      line + inner padding on the field._
- [x] 1.4 Update `submit` / `doSubmit` to read the body from the field's document text instead of
      `textArea.text`.

## 2. Rework keyboard handling

- [x] 2.1 Remove the Enter / Shift+Enter newline shim (the inner editor inserts newlines natively).
- [x] 2.2 Register the Ctrl+Enter / Cmd+Enter submit and Esc cancel actions component-scoped so they
      win while the box is focused; parent them to the draft and register once, surviving rebuilds
      (D2). Choose wrapper vs inner-editor `contentComponent` per sandbox behavior.
      _Resolved: registered on the retained `EditorTextField` wrapper (ancestor of the focused inner
      editor, survives the edge-drag rebuild as a single registration); revisit if 4.3 shows Esc lost._

## 3. Preserve focus and body across rebuild

- [x] 3.1 Keep the deferred `IdeFocusManager.requestFocus(...)` path in `showBox`, targeting the
      `EditorTextField`; guard on inlay validity as today.
- [x] 3.2 Confirm the retained field/document carries the typed body across `hideBox()`/`showBox()`
      during an edge drag, and focus returns to the box on release (D4).
      _By inspection: `bodyField` is a draft-level field re-wrapped into a fresh panel each `showBox`,
      so its document text carries over; focus is re-requested via the deferred path. Runtime confirm
      is part of 4.5._

## 4. Validate in the sandbox IDE

- [ ] 4.1 Build the plugin offline (per build-test-env) and launch `runIde`.
- [ ] 4.2 Verify each editing shortcut acts on the box, not the code: character entry, Enter newline,
      Shift+Arrow selection, Ctrl+Arrow word-nav, Ctrl+W, Backspace, Ctrl+Backspace, Delete, and
      cut/copy/paste — underlying editor text/selection unchanged in every case.
      _Note: `runIde` starts a fresh IDE with the DEFAULT keymap — a personal keymap profile is not
      present, so test with default-keymap shortcuts (or import your keymap into the sandbox). The box
      honors whatever keymap is active, same as any editor; there is no per-field keymap._
- [ ] 4.3 Verify Ctrl+Enter submits, Cmd+Enter submits (if on macOS keymap), and Esc cancels, each
      acting on the box.
- [ ] 4.4 Verify focus switching: click a code line → editing acts on code and the box stays open with
      its body intact; click back in the box → editing resumes in the body.
- [ ] 4.5 Verify the range edge-drag still hides/rebuilds the box with the typed body preserved and
      focus returned, and that Ctrl+Z in the box does not undo code edits.
