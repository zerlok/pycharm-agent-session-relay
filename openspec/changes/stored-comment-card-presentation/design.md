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
- ~~Touching `CommentDraft.kt` / `CommentDraftController.kt` — sibling changes own those files. The
  authoring box keeps its current look; card and box diverging visually is accepted for this change.~~
  **Retracted by the review revision (R2 below):** the accepted divergence is exactly what the review
  reported. `CommentDraft.kt` is now in scope **for its presentation only** — fill, frame, field border,
  button styling and the primary action's label. Its range/anchor/submit/focus/key logic stays untouched,
  and `CommentDraftController.kt` remains out of scope.
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

**The frame is a left accent bar carried by the card's `border`, not by a child component.**
> **Superseded by R1** (review revision, below): the accent bar itself is dropped. The decision is kept
> because its *mechanism* still holds — whatever the card's border is, it is carried by `border` so it
> lands in `insets`, and no second horizontal offset is ever introduced.

Compose
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

**The accent color lives in `RangeHighlight` and is shared by the card bar and the gutter bar.**
> **Superseded by R4** (review revision, below): the color moves out of `RangeHighlight` into a
> `RelayStyle` object, and the follow-up this decision defers — unifying it with `CommentDraft`'s private
> `EDGE_ACTIVE` — is done here, because `CommentDraft.kt` is now in scope.

Add one
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

## Decisions — review revision (PR #9, 2026-08-01)

The three review notes are answered by R1–R5. R1 retracts a decision above; R2–R3 extend the change into
the authoring box; R4–R5 are what keeps that extension from duplicating constants.

**R1 — The card drops its left accent bar; the gutter bar is the range's only accent mark.**
*(Supersedes "The frame is a left accent bar carried by the card's `border`".)* The card's border becomes
the 1px `customLine(JBColor.border(), 1)` outline plus the `empty(8, 12)` padding, i.e. the accent line is
removed from the compound and `ACCENT_WIDTH_DP` disappears. The review is decisive on the visual — two
blue lines within a few pixels of each other (the gutter bar at the far left of the line-number strip,
the card's bar at the left of the inlay) do not read as "one object in two places", they read as a
stripey margin. The gutter bar is the one that carries information — it says *which* lines and *how many*
— so it is the one that survives; the card is already separated from the code by its fill and its
outline, which is what the original defect report actually asked for.

- Consequence for the invariant the superseded decision protected: `contentWidth(insets) = baseWidth -
  insets.left - insets.right` is unchanged, and the insets simply become symmetric again. Nothing about
  the header, the measured body width or the guarded `setSize` moves. The card's test for the accent run
  inside the leading inset inverts into a test that the leading and trailing insets are **equal** and that
  no accent pixel is painted anywhere in the border — a real assertion, not a deletion.
- _Alternative — keep the card bar and drop the gutter bar:_ rejected. The gutter bar is the only
  at-rest signal on the *code* side; dropping it would undo the change's second goal and leave a
  commented range unmarked until its card is hovered.
- _Alternative — keep both but recolor the card's bar (e.g. a neutral gray):_ rejected as a
  half-measure; the report is that the second line distracts, not that it is the wrong color.

**R2 — The authoring box wears the card's presentation, because it is the card's editing state.**
*(Extends the change into `CommentDraft.kt`; retracts that file's Non-Goal.)* `buildPanel`'s content panel
moves from `editor.colorsScheme.defaultBackground` to the shared UI-surface fill, keeping its existing
1px outline and `empty(8, 12)` padding — the same three values the card uses, from the same source. This
is not decoration: the card and the box occupy the *same* screen position for the *same* comment (the
card is suppressed while its box is open), so any difference between them reads as the object changing
identity when the user clicks Edit.

- The box deliberately does **not** gain the card's author header row. The header names a stored message;
  a box is composition, and reserving a row for "You" above a field the user is already typing into buys
  nothing. Card and box match on surface, frame and padding — the cues the review named — and differ on
  the controls each needs. Revisit only if a thread UI makes the box a message-in-progress.
- The box's *width* and *height* behavior is untouched: `getPreferredSize` still pins the width to
  `baseWidth` and lets the height follow the body, and `InlineWidth.capWidth` still applies. Only colors,
  the field's border and the buttons change. This is what keeps R2 compatible with the sibling change.

**R3 — The primary action is "Comment" in both modes, filled with the plugin accent; neither button
paints a patch.** Three separate fixes to the same row:

- *Label.* `JButton(if (editing != null) "Save" else "Comment")` becomes an unconditional `"Comment"`.
  The verb names what the button produces (a review comment), which is true whether the comment is new or
  revised; "Save" named the storage operation instead, and the two labels made the same control look like
  two different controls. Esc/Ctrl+Enter and the submit path are unchanged — this is a label, not a
  behavior.
- *Fill.* The button is styled through the two client properties `DarculaButtonUI` itself reads:
  `"JButton.backgroundColor"` (checked first in `getBackground`, ahead of the default-button gradient and
  the plain-button colors) and `"JButton.textColor"` (checked first in `getButtonTextColor`). Verified
  against the disassembled 2024.2.5 `DarculaButtonUI`. This paints the plugin's accent rather than the
  theme's default blue, which is the "our plugin's style" the review asked for, and it needs no root pane
  — `isDefaultButton()` is false for a button inside an inlay, so the platform's usual default-button
  route is unavailable here anyway.
- *Patch.* The gray rectangle around each button is Swing's opaque fill: a `JButton` is opaque by default,
  so `DarculaButtonUI.update` → `BasicButtonUI.update` fills the whole rect with `UIManager`'s flat
  `Button.background` before the *rounded* shape is drawn over it — which is why the flat color survives
  at the four corners of both buttons, including Cancel, which has no accent of its own. Setting
  `isOpaque = false` on both drops that fill and lets the box's own surface show through.
- The two fixes compose, and that is checked rather than assumed: `paint` → `paintDecorations` (where the
  `getBackground` result is filled into the rounded shape) has **no** opacity test anywhere in it, so
  turning the button non-opaque removes the square patch without removing the accent fill.
- _Alternative — `DarculaButtonUI.DEFAULT_STYLE_KEY`:_ gives a filled button in the *theme's* blue with
  correct text contrast for free, and adapts to any theme. Rejected as the primary mechanism because the
  review asked for the plugin's color specifically; the client-property route reaches the same painter
  with our value. If a future theme ships a `ButtonUI` that ignores both properties, the button degrades
  to a normal button — legible, just not accented.

**R4 — One `RelayStyle` object owns every Relay color; nothing else declares one.** *(Supersedes "The
accent color lives in `RangeHighlight`".)* `RelayStyle.kt` holds the accent, the accent's filled-surface
variant and its label color, the range wash, and the draft's idle/active edge colors, plus the shared
`surface()` fill. `RangeHighlight.STORED_COMMENT_ACCENT` and `CommentDraft`'s private `EDGE_ACTIVE` were
*the same two RGB pairs declared in two files* — a duplication the original design could only note as a
follow-up because `CommentDraft.kt` was off-limits. With R2 opening that file, the follow-up is done here
rather than left as a known divergence. `CommentDraft.RANGE_BACKGROUND` also stops being reached into
from `EditorReviewOverlay` (the overlay was importing a *draft* constant for a *stored-comment* hover).

- `RelayStyle` is colors and the surface fill only — no components, no borders, no layout. A style object
  that starts producing components becomes a second place where geometry lives, and geometry here is
  load-bearing (the content-width invariant). Both surfaces keep composing their own borders.

**R5 — The accent's filled variant is a separate value from the accent line color.** The accent
(`0x3B74E8` light / `0x6E9BF0` dark) is tuned to be *visible as a 3px line*, which in dark themes means
light — and white text on that light blue is unreadable. The fill (`0x3B74E8` light / `0x3574F0` dark) is
the same hue at a luminance that carries a white label in both themes. Two values, one hue, both in
`RelayStyle` with the reason recorded next to them.

- _Alternative — one color for both:_ rejected; it makes either the gutter bar invisible on dark
  backgrounds or the button label unreadable. There is no single luminance that serves a hairline on a
  dark editor and a filled surface under white text.

## Decisions — visual round 2 (PR #9 screenshot, 2026-08-01)

The first revision's colors were accepted ("new colors for button and editor — i see it"); a screenshot
marked three spots that still read as wrong. R6-R7 answer the marked spots, R8 is a fourth defect found
while measuring the screenshot and is fixed in the same pass. All three were measured off the review
screenshot in pixels and traced to a mechanism in the disassembled 2024.2.5 platform, not guessed from
the rendering.

**R6 — The body field paints its own background, so its accent frame hugs the input.** Measured on the
screenshot: the accent line sits at x=30 and x=643, the white input runs 36..637, and the 5px between
them on each side (3px at top and bottom) is the *box's* surface color showing through. The cause is that
[`EditorTextField`] extends `NonOpaquePanel` — it is non-opaque by construction, so the `empty(3, 5)`
padding *inside* the accent line is simply never painted, and the frame reads as a stray rectangle
floating around the input rather than as the input's own frame. Fix: `isOpaque = true` plus
`background = editor.colorsScheme.defaultBackground`. `EditorTextField.setBackground` stores the color as
its enforced background *and* pushes it into the inner editor when that is created, so the padding ring
and the text area are the same color by construction rather than by two settings agreeing.

- _Alternative — swap the border composition to `compound(empty(3, 5), customLine(ACCENT, 1))`:_ the line
  would then be painted around the inner editor instead of at the component's edge, which also removes
  the gap. Rejected: the frame would shrink 5px inside the box's content width, so it would no longer
  line up with anything else in the box, and the body text would sit hard against the line.
- _Alternative — drop the `empty(3, 5)` padding:_ same misalignment plus text touching the frame.
- Do **not** rely on the field's default background: `EditorTextField.getBackground` falls back to
  `UIUtil.getTextFieldBackground()`, which is *not* the editor background in dark themes (`#45494A` vs
  `#2B2B2B` in Darcula) — that would trade a gray ring for a lighter gray ring. Enforcing the editor's
  own background is what makes the ring vanish in both themes.

**R7 — The primary action's outline is its fill, not the theme's plain-button border.** The gray ring the
screenshot marks around the blue button is `DarculaButtonPainter`: only the *fill* was overridden in R3,
so the painter still drew the plain-button border gradient around it. `getBorderPaint(Component, boolean)`
reads the client property `"JButton.borderColor"` as a `Color` and returns it directly for an enabled
button, ahead of its default/plain branches — the exact counterpart to R3's two properties. Setting it to
the same `RelayStyle.ACCENT_FILL` makes the button one solid accent shape.

- _Alternative — `DarculaButtonUI.DEFAULT_STYLE_KEY`:_ would fix the border by making the platform treat
  the button as *the* default button, but it also brings the default-button drop shadow and a different
  focus ring, i.e. it changes more than the defect. Rejected again, for the same reason as in R3: three
  narrow property overrides describe exactly what we want and nothing else.

**R8 — The actions align with the body field's trailing edge.** Not marked, found by measuring: the
field's frame ends at x=643 (the box's content width) while the button row's last component ends at 635.
`FlowLayout` reserves its `hgap` at *both* ends of the row, so `FlowLayout(RIGHT, scale(8), 0)` inset the
whole row 8px from the right. Fix: `hgap = 0` on the layout and an explicit `8dp` strut *between* the two
buttons, which keeps the gap where it was meant to be and lets the row end flush. The ~4px that remains
between the button's painted shape and the field's frame is the platform's own focus-ring inset, present
in every IDE dialog, and is deliberately not compensated for.

## Risks / Trade-offs

- **[The reserved header adds ~1 row of height to every card]** — a one-line comment is now header +
  body instead of body alone. Accepted and explicitly re-specified: it is the cost of making the card
  identifiable, and it buys back the guarantee that revealing the actions never reflows code. The
  comment-box-sizing scenario that promised "only as tall as that line plus its padding" is amended
  rather than silently broken.
- **[R2 puts this change and `comment-box-editing-fidelity` (PR #10) in the same file]** — that sibling
  change edits `CommentDraft.kt` too: it adds a `UiDataProvider` to the same `content` panel declaration
  R2 recolors, adds a document listener in `init`, and changes `create`'s failure path. The two do not
  overlap semantically (presentation vs. undo scoping/resize), but they overlap *textually* on the
  `val content = object : JPanel(...)` line and inside `showBox`'s button construction. Accepted with
  eyes open: whichever merges second resolves a small conflict. Neither branch is rebased onto the other
  here, because that would make PR #9 depend on an unmerged PR #10.
- **[The primary button's fill depends on a Darcula-family `ButtonUI`]** — `JButton.backgroundColor` /
  `JButton.textColor` are read by `DarculaButtonUI`; a third-party LaF with its own `ButtonUI` would
  ignore them. → Degrades to a normal, legible button; nothing about submit breaks.
- **["Comment" in edit mode reads as "add another"]** — a user who opened an existing comment might read
  the button as creating a second one. Judged the lesser evil against one control wearing two names, and
  the surrounding context (the box is seeded with the existing body, over the existing range, with the
  card suppressed) says "editing" clearly. Listed as a running-IDE observation, not a settled fact.
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
  them, the fallback is a tinted editor background per the rejected alternative. **Partially answered by
  the review**: "the new look of the left comment card is better" — it reads as elevated in the reviewer's
  theme. The other themes are still unchecked, and the same question now applies to the box (R2).
- When a card is hovered, does the accent gutter bar stay visible, or does the hover highlight's pale
  bar paint over it (the two-bar overlap above)? If it is swallowed, apply the `RangeHighlight.create`
  gutter opt-out fallback.
- ~~Final accent color values and the accent bar width — 3px or 4px for a stronger left edge.~~
  **Answered by R1**: there is no card bar. The gutter bar stays at `BAR_WIDTH_DP = 3`; the review saw it
  and reported it as doing its job ("highlights how many lines are commented").
- Is the resting card too tall now that the header row is always reserved? If so, the levers are the
  header gap and shrinking the author label's font, not removing the row.
- Does `InplaceButton`'s self-drawn hover highlight look correct against the new card background (it
  paints relative to its parent's background)?
- Does the resting accent bar read as noise in a file with many comments, or does it read as the
  intended index of commented ranges?
- **(R3)** Does the accent fill (`RelayStyle.ACCENT_FILL`) carry the white label legibly in light,
  Darcula and High Contrast? High Contrast in particular substitutes its own palette, and our value
  ignores it. If it fails there, the fallback is `DEFAULT_STYLE_KEY` (theme blue) per R3's alternative.
- **(R3)** With `isOpaque = false` on both buttons, does Cancel still read as a button at all against the
  box's surface fill, or does it lose its outline? `DarculaButtonPainter` draws the border independently
  of the opaque fill, so it should — worth one look.
- **(R2)** Do the card and the box actually look like one object across the Edit transition — same fill,
  same frame, same left edge, no visible jump in width or padding when the card is swapped for the box?
