package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The always-expanded, read-only inline card for a stored comment (design D2), rendered as a
 * full-width block inlay under the commented range by [EditorReviewOverlay]. Purely a view — both
 * buttons route through the store/controller, never mutating a surface directly.
 *
 * The card is shaped as **one message**: an author header row above the comment body, inside a frame
 * (1px outline plus a left accent bar) that owns the whole card. That is deliberate — a future
 * discussion thread stacks N such messages inside the same frame, each keeping its own header, without
 * re-cutting the card's geometry. Nothing about the message shape is stored: the author label is a
 * view-level constant and the domain record is untouched.
 *
 * Three shape decisions are load-bearing:
 *
 * - **Elevation.** The card is filled with the platform panel background, not the editor's own text
 *   background, so it reads as a control floating over code rather than as more code. The fill, the
 *   outline and the accent bar are three separate cues, so a theme where one of them washes out still
 *   leaves the card legible.
 * - **Width cap.** The card panel is wrapped by [InlineWidth.capWidth] so the visible card is no wider
 *   than the editor's right-margin column (full width when no margin is configured), matching the
 *   authoring box.
 * - **Reserved header.** The Edit/Delete actions are still revealed only on hover, but they now live
 *   *inside* the always-present header row, whose height is a constant captured at build time. That
 *   constant — not the old floating top-right overlay — is what keeps the block inlay's height
 *   identical at rest and on hover, so revealing the actions never reflows the code below.
 *
 * Like [CommentDraft]'s box panel, the card swallows its own mouse events so a click on its chrome
 * doesn't retarget to the editor underneath and start a text selection (design "card buttons stealing
 * focus" risk).
 */
object StoredCommentCard {

    // Unscaled dp width of the card's left accent line. Matched to RangeHighlight's gutter stripe so a
    // card's edge and its range's gutter mark read as the same mark in two places.
    private const val ACCENT_WIDTH_DP = 3

    // Unscaled dp gap between the header row and the body text, and between the two header icons.
    private const val HEADER_GAP_DP = 4
    private const val ICON_GAP_DP = 4

    // The header's author label. A view-level constant, NOT a domain field: `ReviewComment` has no
    // author, and this change deliberately adds none (design "Thread state is deliberately NOT
    // modeled"). It is the seam a future thread change replaces with a real per-message author.
    private const val AUTHOR = "You"

    fun build(
        editor: EditorEx,
        body: String,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onHover: (Boolean) -> Unit,
    ): JComponent {
        // The UI-surface fill shared by the card and its (opaque) header, so the header can't show as a
        // seam across the card's top. Distinct from editor.colorsScheme.defaultBackground in effectively
        // every bundled theme — that distinctness is the point.
        val cardBackground = UIUtil.getPanelBackground()

        // Read-only, soft-wrapping body text — no editor, no keystroke capture; the card is inert.
        val bodyArea = JBTextArea(body).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            // A read-only area still shows a text caret cursor by default; use the normal arrow.
            cursor = Cursor.getDefaultCursor()
        }

        // Icon-only actions (a pencil / trash), not text buttons — GitHub/GitLab-small, with the action
        // named in a tooltip. `InplaceButton` is the platform's borderless icon button (as used in
        // tab/close affordances): it draws its own hover highlight and stays icon-sized.
        val editButton = iconButton("Edit", AllIcons.Actions.Edit, onEdit)
        val deleteButton = iconButton("Delete", AllIcons.Actions.GC, onDelete)

        // De-emphasised so the header names the message without competing with its body.
        val authorLabel = JBLabel(AUTHOR).apply {
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
            cursor = Cursor.getDefaultCursor()
        }

        // The header's height, fixed for the card's whole life and read BEFORE the buttons are hidden.
        // A constant — never a live preferredSize read of a container holding invisible children, which
        // would collapse at rest and grow on hover — is what makes the reveal free of any height change.
        val headerHeight = maxOf(
            authorLabel.preferredSize.height,
            editButton.preferredSize.height,
            deleteButton.preferredSize.height,
        )
        val headerGap = JBUI.scale(HEADER_GAP_DP)
        val iconGap = JBUI.scale(ICON_GAP_DP)

        editButton.isVisible = false
        deleteButton.isVisible = false

        // Header row: author pinned left, actions pinned right, positioned explicitly by the overridden
        // doLayout (no layout manager is consulted) so hiding a button can never re-flow the row.
        val header = object : JPanel() {
            override fun doLayout() {
                val labelSize = authorLabel.preferredSize
                authorLabel.setBounds(
                    0,
                    (height - labelSize.height) / 2,
                    labelSize.width.coerceAtMost(width),
                    labelSize.height,
                )
                // Laid out right-to-left from the row's trailing edge; bounds are set whether or not the
                // button is currently visible, so a reveal is a repaint rather than a layout.
                var right = width
                for (button in listOf(deleteButton, editButton)) {
                    val size = button.preferredSize
                    right -= size.width
                    button.setBounds(right, (height - size.height) / 2, size.width, size.height)
                    right -= iconGap
                }
            }
        }.apply {
            isOpaque = true
            background = cardBackground
            cursor = Cursor.getDefaultCursor()
            add(authorLabel)
            add(editButton)
            add(deleteButton)
        }

        // The card opens at the shared base width (~80 columns, clamped to the right-margin cap) rather
        // than shrinking to a short body's width (comment-box-sizing feedback). Only the width is pinned;
        // height is still driven by the reserved header plus the body, the latter measured at that fixed
        // width so a multi-line comment reports the right height.
        val baseWidth = InlineWidth.baseWidthPx(editor)

        // Content width the body is measured AND laid out at — a fixed function of baseWidth, never of
        // the card's own (possibly stretched) width. Keeping both sides on this one value is what stops
        // the layout churn: getPreferredSize's guarded setSize settles to a no-op instead of fighting a
        // doLayout that sized the body to a different width every pass (the feedback that pegged the CPU).
        // The accent bar rides the card's *border*, so its width reaches both sides through `insets` here
        // and nowhere else — no second horizontal offset exists to keep in sync.
        val contentWidth = { insets: java.awt.Insets -> (baseWidth - insets.left - insets.right).coerceAtLeast(1) }

        val card = object : JPanel() {
            // Pin the outer width to baseWidth so the capWidth BoxLayout wrapper can't stretch the card
            // toward the (wider) right-margin cap. This both keeps the card no wider than its reading
            // column — so the header's trailing icons stay on screen — and makes the card's actual width
            // equal the width the body is measured at.
            override fun getMaximumSize(): Dimension = Dimension(baseWidth, Int.MAX_VALUE)

            override fun getPreferredSize(): Dimension {
                val insets = insets
                val cw = contentWidth(insets)
                // Wrap the body to that fixed content width before reading its height, so a multi-line
                // comment measures correctly regardless of layout timing. Guarded so the setSize is a
                // no-op once the body already has this width — the height never depends on the card's
                // actual width, so it can't drive a re-layout loop.
                if (bodyArea.width != cw) bodyArea.setSize(cw, Int.MAX_VALUE)
                // headerHeight is a build-time constant, so hover cannot move this number.
                return Dimension(
                    baseWidth,
                    headerHeight + headerGap + bodyArea.preferredSize.height + insets.top + insets.bottom,
                )
            }

            override fun doLayout() {
                val insets = insets
                val cw = contentWidth(insets)
                header.setBounds(insets.left, insets.top, cw, headerHeight)
                // Body is laid out at the SAME fixed content width getPreferredSize measures it at (not
                // the card's actual width), so the two never diverge and the setSize guard stays satisfied.
                bodyArea.setBounds(
                    insets.left,
                    insets.top + headerHeight + headerGap,
                    cw,
                    height - insets.top - insets.bottom - headerHeight - headerGap,
                )
            }
        }.apply {
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                // Closes the card's right/bottom/top edges at the theme's own frame weight...
                JBUI.Borders.customLine(JBColor.border(), 1),
                // ...while the leading edge carries the accent bar — the message idiom, and the same
                // color as this comment's resting gutter bar. Carried by the border on purpose: it lands
                // in `insets`, which is the single place both getPreferredSize and doLayout read it.
                JBUI.Borders.customLine(RangeHighlight.STORED_COMMENT_ACCENT, 0, ACCENT_WIDTH_DP, 0, 0),
                JBUI.Borders.empty(8, 12),
            )
            cursor = Cursor.getDefaultCursor()
            // No layout manager: the overridden doLayout above positions both children explicitly.
            add(header)
            add(bodyArea)
        }

        // Reveal the actions only while the pointer is over the card (mouse enter/exit). Enter/exit
        // also fire when the pointer crosses into a child (the body, the header or a button), so on exit
        // we only hide when the pointer has truly left the whole card — getMousePosition(true) inspects
        // the card *and its descendants*. Attaching the same listener to the children (not just the card)
        // makes the enter fire wherever the pointer first lands. This listener set also makes the card
        // a real mouse-event target, so — together with the explicit mousePressed swallow below —
        // Swing does not retarget clicks over the card to the editor beneath it (same pattern as
        // CommentDraft).
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                setActionsVisible(card, editButton, deleteButton, true)
                // Reveal this comment's range in the editor while its card is hovered (design D4). The
                // overlay owns the single transient highlight; the card only reports enter/exit.
                onHover(true)
            }

            override fun mouseExited(e: MouseEvent) {
                // Same proven exit test as before: only a *true* leave (pointer no longer over the card
                // or any descendant) hides the actions and clears the range highlight, so crossing into
                // a child button never flickers either off.
                if (card.getMousePosition(true) == null) {
                    setActionsVisible(card, editButton, deleteButton, false)
                    onHover(false)
                }
            }

            // Swallow presses so a click on the card's padding/header/background stays on the card
            // instead of starting a text selection in the editor underneath.
            override fun mousePressed(e: MouseEvent) {}
        }
        card.addMouseListener(hover)
        bodyArea.addMouseListener(hover)
        header.addMouseListener(hover)
        authorLabel.addMouseListener(hover)
        editButton.addMouseListener(hover)
        deleteButton.addMouseListener(hover)

        // Cap the card at the editor's right margin (comment-box-sizing), same as the authoring box.
        return InlineWidth.capWidth(card, InlineWidth.rightMarginPx(editor))
    }

    private fun iconButton(tooltip: String, icon: Icon, onClick: () -> Unit): InplaceButton =
        InplaceButton(tooltip, icon) { onClick() }

    private fun setActionsVisible(
        card: JComponent,
        editButton: JComponent,
        deleteButton: JComponent,
        visible: Boolean,
    ) {
        if (editButton.isVisible == visible) return
        editButton.isVisible = visible
        deleteButton.isVisible = visible
        // The header row's height is a constant of the build and its children are positioned whether or
        // not they are visible, so this re-lays-out nothing that can move — the inlay height stays put.
        card.revalidate()
        card.repaint()
    }
}
