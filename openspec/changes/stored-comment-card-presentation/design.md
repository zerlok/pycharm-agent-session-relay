## Context

`StoredCommentCard.build` (`StoredCommentCard.kt:41-175`) returns an
`EditorEmbeddedComponentManager` block-inlay panel with exactly two children today: a read-only
`JBTextArea` body and a `FlowLayout` toolbar of two `InplaceButton`s that is hidden at rest
(`isVisible = false`, `StoredCommentCard.kt:71`) and floated over the body's top-right corner by the
card's own `doLayout` (`StoredCommentCard.kt:117-120`).

Its visual identity is three lines:

```kotlin
isOpaque = true
background = editor.colorsScheme.defaultBackground              // StoredCommentCard.kt:124
border = JBUI.Borders.compound(
    JBUI.Borders.customLine(JBColor.border(), 1),               // StoredCommentCard.kt:126
    JBUI.Borders.empty(8, 12),                                  // StoredCommentCard.kt:127
)
```

The card therefore has the same fill as the code it floats over, framed by the theme's faintest line.
The toolbar repeats the same background (`StoredCommentCard.kt:68`).

Three sizing facts from comment-box-sizing are load-bearing and constrain every option below:

- The card's outer width is **pinned** to `InlineWidth.baseWidthPx(editor)` by an overridden
  `getMaximumSize` (`StoredCommentCard.kt:94`) and the whole panel is then wrapped by
  `InlineWidth.capWidth(card, InlineWidth.rightMarginPx(editor))` (`StoredCommentCard.kt:174`).
- `getPreferredSize` (`StoredCommentCard.kt:96-105`) and `doLayout` (`StoredCommentCard.kt:107-121`)
  both measure/lay out the body at **one** value, `contentWidth(insets) = baseWidth - insets.left -
  insets.right` (`StoredCommentCard.kt:87`), and the `setSize` at `StoredCommentCard.kt:103` is guarded
  by `if (bodyArea.width != cw)`. The in-code comment (`StoredCommentCard.kt:84-86`) records that this
  single shared width is what stopped a layout feedback loop that pegged the CPU.
- The toolbar is excluded from `getPreferredSize` on purpose, so the inlay's height is identical at
  rest and on hover (`StoredCommentCard.kt:80-82`).

On the editor side, `EditorReviewOverlay.addMarker` (`EditorReviewOverlay.kt:176-189`) creates the
stored comment's position marker on the *document* markup with `null` attributes and no
`gutterIconRenderer` (editor-review-visibility D5, comment at `EditorReviewOverlay.kt:184-186`); the
only visible range signal is the transient `RangeHighlight.create(...)` built on card hover with
`CommentDraft.RANGE_BACKGROUND` (`EditorReviewOverlay.kt:262`). `RangeHighlight.gutterBar(color)`
(`RangeHighlight.kt:42-45`) is already a standalone, shared `LineMarkerRenderer` factory — a
`BAR_WIDTH_DP = 3` stripe (`RangeHighlight.kt:34`) — attachable to any `RangeHighlighter`.

## Goals / Non-Goals

**Goals:**

- A resting stored-comment card that is unmistakably a UI object over code: distinct fill, an accent
  frame, and a header naming who wrote it.
- A resting tie between a card and its lines, visible in the gutter without hovering.
- Preserve every comment-box-sizing invariant: pinned base width, single content width, guarded
  `setSize`, identical rest/hover height, mouse-event swallowing.
- Shape the card as *one message* so a future thread stacks N messages in one frame.

**Non-Goals:**

- Any domain/storage/logic change. No `author` field, no messages list, no thread-state enum, no
  `ReviewComment` change. The header's author label is a hardcoded view-level string.
- Threading/replies themselves, and any open/closed (resolve) affordance.
- Touching `CommentDraft.kt` / `CommentDraftController.kt` — sibling changes own those files. The
  authoring box keeps its current look; card and box diverging visually is accepted for this change.
- A resting **wash** over the commented code. Only the gutter bar becomes resting; the wash stays
  hover-only (it would tint arbitrarily large regions of a file at rest).

## Decisions

**The card gets an elevated UI-surface background, not the editor's.** Replace
`editor.colorsScheme.defaultBackground` (`StoredCommentCard.kt:124`, and the toolbar's copy at
`StoredCommentCard.kt:68`) with the platform's panel/UI surface color (`UIUtil.getPanelBackground()`),
which is a different value from the editor background in effectively every bundled theme, light and
dark. This is what makes the card read as a control floating over code rather than as more code. The
toolbar must move to the same color in the same edit — it is an opaque patch drawn inside the card, and
leaving it on the editor color would make the header's right end a visible seam.

- _Alternative — tint the editor background by a few percent of the accent:_ derives from the editor's
  own scheme so it can never clash, but the delta is theme-dependent (invisible in some high-contrast
  schemes, muddy in others) and needs a hand-tuned alpha per mode. Rejected; the platform panel color is
  already the IDE's answer to "a UI surface", and themes maintain its contrast against the editor.
- _Alternative — keep the editor background and rely only on a heavier border:_ rejected; the defect
  report is specifically that a thin frame over identical fill does not separate. A frame alone leaves
  the card's interior reading as code.

**The frame is a left accent bar carried by the card's `border`, not by a child component.** Compose
the border as `customLine(JBColor.border(), 1)` (kept — the outline still closes the card's right and
bottom edges) **plus** `customLine(ACCENT, 0, ACCENT_WIDTH, 0, 0)` **plus** the existing
`empty(8, 12)` padding. Carrying the accent in the border is what preserves the CPU-loop invariant for
free: `contentWidth` (`StoredCommentCard.kt:87`) is defined as `baseWidth - insets.left - insets.right`,
and a border contributes to `insets`, so the measuring side (`getPreferredSize`) and the layout side
(`doLayout`) both lose exactly the accent's width in the same expression, in the same pass. No second
width constant is introduced, and the "both sides must move together" requirement is satisfied by
construction.

- _Alternative — a `JPanel` accent strip added as a third child, positioned in `doLayout`:_ requires a
  second horizontal offset that `getPreferredSize` must also subtract, i.e. exactly the two-places-one-
  number situation that caused the layout feedback loop. Rejected.
- _Alternative — a heavy full box (2-3px outline all round in the accent color):_ visually loud, and it
  competes with the header and the gutter bar rather than composing with them. Rejected; the left bar is
  the GitHub/Slack/IDE-notification idiom for "message" and reads at a glance in a wall of code.

**The header row is always present and reserves the icons' height; only the icons toggle.** Add a
header child laid out by the card's `doLayout` across the full `contentWidth` at the top of the content
box, with the author label (`JBLabel("You")`, de-emphasised via `UIUtil.getContextHelpForeground()` or
a small-font `JBFont`) pinned left and the existing `editButton`/`deleteButton` pinned right. The header
occupies a **fixed** height computed once at build time as
`max(authorLabel.preferredSize.height, editButton.preferredSize.height, deleteButton.preferredSize.height)`,
captured in a local `val` before the buttons are hidden, and `getPreferredSize` returns
`headerHeight + HEADER_GAP + bodyArea.preferredSize.height + insets.top + insets.bottom`. Because that
height is a constant of the build, not a function of what is currently visible, toggling the two buttons
on hover cannot change the inlay height — the same guarantee the floating overlay gave, now with a
visible resting row instead of empty space. `doLayout` places the header at
`(insets.left, insets.top, cw, headerHeight)` and the body at
`(insets.left, insets.top + headerHeight + HEADER_GAP, cw, height - insets.top - insets.bottom -
headerHeight - HEADER_GAP)`; the body is still sized to `cw`, so the guarded `setSize` at
`StoredCommentCard.kt:103` still settles to a no-op.

- _Alternative — keep the floating top-right toolbar overlay and add only a left-hand author label:_
  the label and the icons would then live in two different mechanisms at the same y, and the overlay
  would paint on top of the label whenever the label is long. Rejected; one reserved row is simpler and
  is the shape a thread needs.
- _Alternative — measure the header via a layout manager and read `header.preferredSize` each pass:_
  an invisible child collapses a `FlowLayout`'s preferred height, so the header's height would differ
  between rest and hover — the exact reflow this change must not reintroduce. Rejected; the height is a
  build-time constant and the header's children are positioned explicitly.
- The hover `MouseAdapter` (`StoredCommentCard.kt:145-166`) is currently attached to the card, body,
  toolbar and both buttons (`StoredCommentCard.kt:167-171`). It must also be attached to the header
  panel and the author label: it is both the reveal trigger and the `mousePressed` swallow
  (`StoredCommentCard.kt:165`), so any child that can receive a press without it would let the click
  retarget to the editor and start a text selection. The `getMousePosition(true)` exit test
  (`StoredCommentCard.kt:157`) inspects descendants and needs no change.

**The resting tie is `RangeHighlight.gutterBar(ACCENT)` on the existing position marker, not the
unwired icon renderer.** In `addMarker` (`EditorReviewOverlay.kt:176-189`) set
`highlighter.lineMarkerRenderer = RangeHighlight.gutterBar(ACCENT)` on the marker the overlay already
creates, and amend the D5 comment (`EditorReviewOverlay.kt:184-186`) to record that the marker is now
"live position source **and** resting gutter signal", still with no `gutterIconRenderer` and still with
`null` text attributes (no wash). This costs no new object, needs no reconcile path, and drifts with
in-IDE edits for free because it rides the marker the overlay already keeps live. It also lands on the
*document* markup, so the bar shows in every split of the file, matching `RangeHighlight`'s stated role
(`RangeHighlight.kt:12-20`).

- _Alternative — wire `StoredCommentGutterIconRenderer`:_ its own KDoc and the D5 comment
  (`EditorReviewOverlay.kt:186`) both reserve it for the deferred hide-comments change, where it is the
  *collapsed* indicator and an edit entry point. Re-wiring it now would put a balloon icon on every
  commented line permanently — the icon noise D5 deliberately removed — and would break the existing
  headless regression test at `EditorReviewOverlayTest.kt:147`. Rejected; the class stays unwired.
- _Alternative — a resting wash over the code as well:_ rejected, see Non-Goals; a permanently tinted
  region per comment is exactly the "hides the code" complaint in a different form.

**The accent color lives in `RangeHighlight` and is shared by the card bar and the gutter bar.** Add one
`JBColor` (light/dark pair) beside `gutterBar` in `RangeHighlight.kt` — the file that already owns the
shared range visuals — and use it for both the card's left border line and the marker's gutter bar, so
"this card" and "these lines" are literally the same color. `CommentDraft.RANGE_BACKGROUND`
(`CommentDraft.kt:423`) is the pale *wash* color and is far too faint for a 3px stripe or a card edge;
the saturated blue that would fit, `CommentDraft.EDGE_ACTIVE` (`CommentDraft.kt:429`), is `private` and
`CommentDraft.kt` is off-limits to this change. The new constant is therefore the single source for the
stored-comment accent, and unifying it with the draft's edge blue is left to a later change that is
allowed to edit `CommentDraft.kt`.

- _Alternative — reuse `CommentDraft.RANGE_BACKGROUND` for the accent:_ one color for everything, but a
  pale wash color used as a line is invisible — it is chosen to sit *behind text*. Rejected.
- _Alternative — widen the scope to promote `EDGE_ACTIVE` out of `CommentDraft`:_ the correct end state,
  but `CommentDraft.kt` has concurrent sibling changes on other branches and touching it here would
  conflict. Rejected for this change; recorded as the follow-up.

**Thread state is deliberately NOT modeled, and when it arrives it is orthogonal to `CommentStatus`.**
This change shapes the card as one message — author header plus body — precisely so a future discussion
thread stacks N of these inside one frame, with the frame (accent bar + outline) owning the thread and
each stacked message owning its own header. That future adds an OPEN/RESOLVED thread state. That state
is **not** the existing domain enum `CommentStatus` (`domain/ReviewComment.kt:13-22`), whose three
values `ACTIVE` / `STALE` / `ORPHANED` are documented there as being about **anchor drift** — whether
the comment still points where it was authored — and are reserved for a future re-anchoring change.
Conversation lifecycle and anchor validity are independent axes (a RESOLVED thread can be ORPHANED, an
ACTIVE anchor can carry an OPEN thread); merging them would make "resolved" unrepresentable for a
drifted comment and would corrupt the re-anchoring semantics. A future change adds a separate field.
Nothing in *this* change may add a domain field, a messages list, a state enum, or an author field.

## Risks / Trade-offs

- **[The reserved header adds ~1 row of height to every card]** — a one-line comment is now header +
  body instead of body alone. Accepted and explicitly re-specified: it is the cost of making the card
  identifiable, and it buys back the guarantee that revealing the actions never reflows code. The
  comment-box-sizing scenario that promised "only as tall as that line plus its padding" is amended
  rather than silently broken.
- **[Two gutter bars over the same lines while a card is hovered]** — the resting accent bar (document
  markup, `HighlighterLayer.LAST`) and the hover highlight's own bar (editor markup,
  `HighlighterLayer.SELECTION - 1`, `RangeHighlight.kt:60`) paint the same rectangle in the same gutter
  column. Same geometry, different colors; which wins is a paint-order question that cannot be resolved
  headlessly (see Open Questions). Fallback if the pale hover bar swallows the accent on hover: give
  `RangeHighlight.create` an opt-out for its gutter bar and let the resting accent bar stand alone while
  the wash marks the hover.
- **[Panel background vs. a custom editor color scheme]** — a user whose editor background is already
  the panel color (some minimal themes) would see no elevation. → The 1px outline plus the accent bar
  still separate the card; the background is one of three cues, not the only one.
- **[Accent bar competing with VCS change bars in the same gutter column]** — both are
  `LineMarkerRenderer` stripes. Accepted; the bar is 3px (`RangeHighlight.kt:34`) and the pairing is the
  same one the draft wash already ships with.
- **[Author label is a hardcoded "You"]** — it is a lie the moment a second participant exists. Accepted
  for this change and called out in the spec text as view-level; it is the seam the thread change
  replaces with a real per-message author.

## Open Questions

No display is available in this environment (project build/test env: compile + unit tests only), so
every item below must be settled by looking at a running IDE and recorded in `tasks.md` when resolved:

- Does `UIUtil.getPanelBackground()` actually read as *elevated* against the editor background in the
  default light and dark themes (and Darcula / High Contrast)? If it is indistinguishable in any of
  them, the fallback is a tinted editor background per the rejected alternative.
- When a card is hovered, does the accent gutter bar stay visible, or does the hover highlight's pale
  bar paint over it (the two-bar overlap above)? If it is swallowed, apply the `RangeHighlight.create`
  gutter opt-out fallback.
- Final accent color values (the light/dark `JBColor` pair) and the accent bar width — 3px to match
  `BAR_WIDTH_DP` (`RangeHighlight.kt:34`) or 4px for a stronger left edge. Taste call.
- Is the resting card too tall now that the header row is always reserved? If so, the levers are the
  header gap and shrinking the author label's font, not removing the row.
- Does `InplaceButton`'s self-drawn hover highlight look correct against the new card background (it
  paints relative to its parent's background)?
- Does the resting accent bar read as noise in a file with many comments, or does it read as the
  intended index of commented ranges?
