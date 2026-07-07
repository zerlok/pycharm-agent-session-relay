## Context

The inline comment box (`ui/CommentDraft.kt`) hosts its body in a `JBTextArea` added to the editor
via `EditorEmbeddedComponentManager` — i.e. the text area is a Swing descendant of the editor's
`EditorComponentImpl`. IntelliJ's `IdeKeyEventDispatcher` runs before Swing delivers a key: it looks
the keystroke up in the active keymap, builds a `DataContext` from the focus owner, and fires the
first enabled matching action. Because the focus owner's ancestor chain includes `EditorComponentImpl`
(which supplies `CommonDataKeys.EDITOR`), editor actions — `EditorBackSpace`, `EditorSelectWord`
(Ctrl+W), `EditorLeftWithSelection` (Shift+Left), `EditorDeleteToWordStart` (Ctrl+Backspace), etc. —
resolve to the **host editor**, fire there, and consume the key before the text area ever sees it.

We already patched Enter / Ctrl+Enter / Esc by registering component-scoped actions on the text area
(`registerCustomShortcutSet(set, textArea, parent)`), which out-rank global keymap actions while that
component is focused. Extending that to every editing shortcut (Option A, explored) re-implements the
platform's editing keymap and is never complete across keymaps. Explore concluded on **Option C**:
give the box a real inner editor so the platform owns editing natively.

Constraints: change stays in the presentation layer (`ui/`) per `docs/ARCHITECTURE.md`; the retained
body must survive the adjustable-comment-range edge drag (D1 of that change disposes and rebuilds the
inlay); target platform is PyCharm CE 2024.2.5 (`sinceBuild 242`).

## Goals / Non-Goals

**Goals:**
- While the box is focused, every text-editing keystroke acts on the comment body, never the code,
  with no per-shortcut enumeration and correct behavior across keymaps.
- Ctrl+Enter/Cmd+Enter submits and Esc cancels while the box is focused.
- The user can move focus to the editor (box stays) and back to the box, editing the right target
  each time.
- The in-progress body survives the range edge-drag rebuild, as today.

**Non-Goals:**
- No change to how comments are stored, exported, delivered, or reconciled (domain/logic/storage
  untouched).
- No change to the add-comment hover affordance, gutter markers, or edge-drag resize gesture.
- Not adding syntax highlighting / language features to the comment body — it is plain text.

## Decisions

### D1: Host the body in a multiline `EditorTextField` instead of `JBTextArea`

An `EditorTextField` wraps a real IntelliJ editor. When it is focused, `CommonDataKeys.EDITOR`
resolves to *its own* inner editor, so `IdeKeyEventDispatcher` fires editing actions against the inner
editor — every selection/nav/delete/clipboard action works natively and correctly for any keymap. This
is the platform-blessed component for "an editable text field living inside an editor," and it aligns
with the Single-Source-of-Truth principle: we reuse the platform's editing keymap rather than
re-declaring it.

Construct it multiline and word-wrapping, e.g. `EditorTextField(document, project, FileTypes.PLAIN_TEXT)`
with `setOneLineMode(false)`, and in an `addSettingsProvider { editor -> … }` enable soft wraps and a
sensible visible row count / minimum height to match today's ~4-row box. The field exposes a
`Document` (and a `getEditor()` once shown) for focus and text access.

- Alternatives: (A) enumerate every colliding shortcut on the `JBTextArea` — rejected in explore
  (incomplete, keymap-fragile, fights SSOT). (B) install a data provider that blanks
  `CommonDataKeys.EDITOR` while focused — rejected: classic `DataProvider` returning null reads as
  "not provided" and falls through to `EditorComponentImpl`, so suppression is unreliable on 242.

### D2: Keep only the Ctrl+Enter (submit) and Esc (cancel) shims; drop the Enter/Shift+Enter shim

The inner editor inserts a newline on Enter by itself, so the Enter/Shift+Enter shim is removed.
Ctrl+Enter/Cmd+Enter and Esc are not plain-text editor edits, so they are still registered as
component-scoped actions — on the `EditorTextField` (or its inner editor's `contentComponent`) — so
they win while the box is focused. Esc has no default editor edit action to fight, and Ctrl+Enter
out-ranks any global binding via component scope, exactly as the current Enter fix does.

### D3: Focus switching falls out of focus scope; no explicit dismiss-on-blur

Because capture is scoped to the inner editor being focused, clicking a code line naturally moves the
`EDITOR` context to the host editor and editing acts on the code — with the box still present (nothing
disposes it on blur; disposal stays tied to submit/cancel/another-box, as today). The box panel's
`mousePressed` handler already routes clicks on the box back to the field; it is repointed from
`textArea.requestFocusInWindow()` to focusing the `EditorTextField`. No focus listener, no global hook.

### D4: Preserve the body across the edge-drag rebuild via a retained document

Today the `JBTextArea` instance is retained across `hideBox()`/`showBox()` so its text carries over.
With `EditorTextField`, retain the field (and its `Document`) as the draft-level field and re-wrap it
in a fresh panel/inlay on rebuild — same pattern. Re-focus after rebuild continues to go through the
deferred `IdeFocusManager.requestFocus(...)` path, targeting the field's component. `registerShortcuts`
stays a once-per-draft registration parented to the draft so it survives rebuilds.

## Risks / Trade-offs

- **Esc is swallowed by the inner editor / not delivered to our shim** → register the cancel action on
  the inner editor's `contentComponent` (available after the field is shown) rather than the wrapper,
  and verify in the sandbox; fall back to an editor `EditorActionHandler`/`AnAction` scoped to that
  component if the wrapper-level registration loses the race.
- **Deferred focus timing** — the inner editor's `getEditor()` is null until the field is added and
  laid out → keep the existing `invokeLater` + `IdeFocusManager` deferral and request focus on the
  field (which forwards to the inner editor once created); guard on inlay validity as today.
- **Row sizing / appearance drift** from `JBTextArea` → set soft-wrap + preferred visible rows and a
  minimum height in the settings provider so the box keeps its current footprint.
- **Undo scope** — the inner editor owns its own undo stack, isolated from the host document; this is
  desirable (Ctrl+Z in the box must not undo code edits) and needs a quick sandbox confirmation.
- **Clipboard/caret interaction with the wash + edge grips** — the edge-drag gesture is driven by the
  editor mouse channel and only triggers on the host editor's editing area, so it is unaffected by the
  inner editor; confirm a drag started outside the box still hides/rebuilds correctly.

## Migration Plan

Single self-contained edit to `ui/CommentDraft.kt`; no data or API migration. Rollback is reverting
the file. Validation is manual in the sandbox IDE (`runIde`) exercising the scenarios in the delta
spec — keyboard capture and focus switching are not unit-testable in this harness.

## Open Questions

- Register the Ctrl+Enter/Esc shims on the `EditorTextField` wrapper or on the inner editor's
  `contentComponent`? Decide during implementation from sandbox behavior (D2/first risk).
