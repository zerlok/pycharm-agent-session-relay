## Why

This change records the **shipped baseline** of Agent Session Relay's annotation surface — the
first stage of the relay pipeline in [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) §3
(`Comment → ReviewBatch → Exporter → Delivery`). It exists so `openspec/specs/` reflects the code
as it actually runs today, rather than the earlier plan (a keybound action + popup) that
implementation deliberately replaced with a hover-gutter + inline inlay box.

The later stages — batching, export, and delivery — are tracked as separate in-progress changes
(`comment-batch`, `review-export`, `review-delivery`), each layered on this baseline.

## What Changes

- **Bootstrap the plugin** from the IntelliJ Platform Plugin Template (Kotlin, Gradle,
  `io.github.zerlok.agentsessionrelay`) so there is a buildable, `runIde`-able plugin.
- **Author a line-anchored comment inline**, GitHub/GitLab style:
  - Hovering an editor line reveals a single **+** gutter affordance on that line.
  - Clicking it opens a full-width **inline comment box** (a block inlay) below the target lines,
    with the target range highlighted; the range is the current selection, or the clicked line
    when there is no selection.
  - The box takes a multi-line body and supports submit (Ctrl/Cmd+Enter or button), cancel
    (Esc or button), and newline entry (Enter / Shift+Enter), with focus routed into the box.
  - At most one box is open at a time.
- **Surface the captured comment on submit.** The baseline reports the captured file + line range
  + body via a confirmation notification and log entry. Persisting it into a batch and exporting
  it are the next stages (out of scope here — see the `comment-batch` and `review-delivery`
  changes).

## Capabilities

### New Capabilities
- `review-annotation`: Reveal an add-comment affordance on hover and author a line-anchored comment
  in an inline box over any open file. (Displaying, listing, and deleting *stored* comments belong
  to the `comment-batch` stage, not this baseline.)

## Impact

- **New project scaffolding**: Gradle build, `src/main/kotlin`, `src/main/resources/META-INF/plugin.xml`,
  plugin ID `io.github.zerlok.agentsessionrelay`, `notificationGroup`.
- **IntelliJ Platform APIs**: editor-factory mouse listeners, `RangeHighlighter`,
  `GutterIconRenderer`, `EditorEmbeddedComponentManager` (block inlay), `Inlay`,
  project-scoped `Service`, `Notification`.
- **No runtime services or external deps** beyond the IntelliJ Platform.
