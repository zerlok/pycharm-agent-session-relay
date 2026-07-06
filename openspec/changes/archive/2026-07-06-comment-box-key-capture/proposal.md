## Why

The inline comment box captures typed characters and the three shortcuts we hand-patched (Enter,
Ctrl+Enter, Esc), but every other editing keystroke — Shift+Arrow selection, Ctrl+Arrow / word
navigation, Ctrl+W, Backspace, Ctrl+Backspace, clipboard — leaks to the underlying editor and mutates
the code instead of the comment. The box is a `JBTextArea` embedded in the editor's content component,
so IntelliJ's key dispatcher resolves those shortcuts to the **host editor's** actions (the editor is
always its Swing ancestor and supplies `CommonDataKeys.EDITOR`) and fires them before Swing delivers
the key to the text area. Patching shortcuts one by one re-implements an editing keymap the platform
already owns and can never be complete across keymaps — the box must simply own the keyboard while it
is focused.

## What Changes

- Replace the box's `JBTextArea` with a **multiline `EditorTextField`** (an inner editor). Focus then
  resolves `CommonDataKeys.EDITOR` to the *inner* editor, so all editing actions — selection,
  word-nav, word-delete, backspace, clipboard, undo — apply to the box natively, with no per-shortcut
  enumeration and correct behavior across every keymap.
- The inner editor provides **newline-on-Enter** natively, so the Enter/Shift+Enter shim is removed;
  only the **Ctrl+Enter / Cmd+Enter submit** and **Esc cancel** shims are kept (registered on the
  inner editor's component so they win while it is focused).
- Guarantee **bidirectional focus switching**: clicking a code line moves editing to the host editor
  with the box still present; clicking the box returns editing to the box. The box is never disposed
  by focus loss (only by submit / cancel / range-drag).
- Preserve the existing **retained-body-across-rebuild** behavior so an in-progress body survives the
  adjustable-comment-range edge drag (the retained component becomes the `EditorTextField` / its
  document).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `review-annotation`: the "Capture a multi-line body with submit and cancel" requirement is
  strengthened — while the box is focused it SHALL own **all** editing keystrokes (not just
  Enter/Ctrl+Enter/Esc), and a new focus-switching requirement lets the user move editing between the
  box and the host editor without the box disappearing.

## Impact

- Code: `src/main/kotlin/io/github/zerlok/agentsessionrelay/ui/CommentDraft.kt` only — swap the
  component, adjust `buildPanel` / `showBox` / `registerShortcuts`, keep the retained-body plumbing.
  No domain / logic / storage / delivery changes; stays entirely in the presentation layer per
  `docs/ARCHITECTURE.md`.
- Dependencies: none new — `EditorTextField` ships with the IntelliJ Platform.
- Tests: annotation/overlay unit tests unaffected in contract; box behavior is validated manually in
  the sandbox IDE (keyboard capture is not unit-testable here).
