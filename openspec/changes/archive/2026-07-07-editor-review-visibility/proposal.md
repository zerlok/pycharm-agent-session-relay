## Why

The in-editor review surface is noisier than it needs to be and under-communicates where a
comment actually lives. The add-comment "+" follows the pointer everywhere — including over code
you are reading — so it competes for attention while you work. Meanwhile a comment's *line range*
is only weakly signalled: the draft wash tints the code text but not the line-number gutter, and a
stored comment shows a permanent gutter icon that is now redundant (every stored comment already
renders an always-visible inline card) while giving no clear read of which lines it covers. This
change makes the "+" appear only where you'd reach for it, makes the commented range obvious in the
gutter, and drops the redundant icon.

## What Changes

- Confine the hover **"+"** add-comment affordance to the **left gutter** (the line-number strip):
  it appears only while the pointer is over a gutter area and is suppressed while the pointer is
  over code content. The trigger spans the gutter's sub-areas so the icon stays clickable.
- Add a **right-click "Add review comment"** action to the editor context menu — comments the
  current selection's line range, or the caret line when there is no selection.
- Extend the **draft's range highlight** to cover the **line-number gutter** (a colored gutter bar
  beside the numbers), so the commented lines read as highlighted in the gutter as well as the code.
- Show the **same range + gutter highlight on a stored comment when its inline card is hovered**, so
  hovering a card reveals exactly which lines it covers (there is no permanent stored wash at rest).
- **Remove the always-on stored-comment gutter icon.** Editing a stored comment becomes card-only
  (the card's Edit action). **BREAKING** (behavioral): the stored-comment gutter marker no longer
  appears, and the gutter marker is no longer an edit entry point.
- **Non-goal / deferred:** a "hide/collapse comments in editor view" toggle. That later change is
  what will bring the gutter icon back — as the collapsed-comment indicator and its edit entry point.
  The icon renderer class is kept in the tree (unwired) so that change is a re-wire.

## Capabilities

### New Capabilities
<!-- none: all behavior lives under the existing review-annotation capability -->

### Modified Capabilities
- `review-annotation`: the hover affordance is confined to the gutter; a right-click add-comment
  entry point is added; the draft range highlight extends into the line-number gutter; a stored
  comment highlights its range on card hover; the stored-comment gutter icon is removed and editing
  becomes card-only.

## Impact

- **Presentation layer only** (`ui` package); no domain/logic/storage changes.
  - `RelayHoverListener` — gate the "+" on `EditorMouseEventArea` (gutter, not editing area).
  - `plugin.xml` + a new `AnAction` — register the editor-popup "Add review comment" action.
  - `CommentDraft` — add a gutter `LineMarkerRenderer` to the range highlighter.
  - `EditorReviewOverlay` / `StoredCommentCard` — transient range+gutter highlight on card hover;
    stop attaching `StoredCommentGutterIconRenderer` (class retained, unused).
- Reuses the existing threading/layer rules (see `docs/ARCHITECTURE.md`); the stored marker remains
  the live position source for the hover highlight and for submit-time position sync.
