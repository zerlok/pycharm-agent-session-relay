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
 * The always-expanded, read-only inline card for a stored comment, rendered as a viewport-width
 * block inlay under the commented range by [EditorReviewOverlay]. Purely a view — both buttons
 * route through the store or the controller, never mutating a surface directly.
 *
 * The card is shaped as **one message**: an author header row above the comment body, inside a 1px
 * outline that owns the whole card. That is deliberate — a future discussion thread stacks N such
 * messages inside the same frame, each keeping its own header, without re-cutting the card's
 * geometry. Nothing about the message shape is stored: the author label is a view-level constant.
 *
 * Three shape decisions are load-bearing:
 *
 * - **Elevation.** The card is filled with [RelayStyle.surface], not the editor's text background,
 *   so it reads as a control floating over code rather than as more code. That fill plus the outline
 *   are its whole visual identity: it carries **no accent edge of its own**, because the accent marks
 *   a commented range in exactly one place — the gutter bar over its lines — and two parallel blue
 *   lines a few pixels apart read as a stripey margin rather than as one object. The fill is also the
 *   authoring box's ([CommentDraft]), so a card and the box that edits it are one object in two states.
 * - **Live width.** The card measures and lays out at the width its [ReadingWidthRow] gives it — the
 *   editor's viewport width, capped at the reading measure — matching the box. The platform re-lays
 *   that row out whenever the visible area changes, so narrowing the editor re-wraps the body and
 *   keeps the header's trailing icons on screen, with no width listener of Relay's own.
 * - **Reserved header.** The Edit/Delete actions are revealed only on hover, but they live *inside*
 *   the always-present header row, whose height is a constant captured at build time. That constant
 *   is what keeps the inlay's height identical at rest and on hover, so revealing the actions never
 *   reflows the code below.
 *
 * Like [CommentDraft]'s box panel, the card swallows its own mouse events, so a click on its chrome
 * does not retarget to the editor underneath and start a text selection.
 */
object StoredCommentCard {

    // Unscaled dp gap between the header row and the body text, and between the two header icons.
    private const val HEADER_GAP_DP = 4
    private const val ICON_GAP_DP = 4

    // The header's author label. A view-level constant, NOT a domain field: `ReviewComment` has no
    // author, and modeling one is a thread concern that does not exist yet. This is the seam a future
    // thread change replaces with a real per-message author.
    private const val AUTHOR = "You"

    fun build(
        editor: EditorEx,
        body: String,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onHover: (Boolean) -> Unit,
    ): JComponent {
        // Built first: the card measures its wrapping body at the width this row will allot it, so the
        // row has to exist before the card that asks it.
        val row = ReadingWidthRow(editor)
        // The UI-surface fill shared by the card and its (opaque) header, so the header can't show as a
        // seam across the card's top — and shared with the authoring box, so Edit doesn't change the
        // object's appearance. Distinct from editor.colorsScheme.defaultBackground; that is the point.
        val cardBackground = RelayStyle.surface()

        // Read-only, soft-wrapping body text — no editor, no keystroke capture; the card is inert.
        val bodyArea = JBTextArea(body).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty()
            // Set explicitly, so the body does not inherit `TextArea.font` — which the platform's LaF
            // initialises to *Monospaced* while an EditorTextField (the box's body) carries the UI
            // font, making a comment change typeface the moment it is opened for editing.
            font = RelayStyle.bodyFont()
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
                // The icons are laid out FIRST, at their full width: they are the card's only actions,
                // while the label is a fixed constant carrying no information, so when the row is too
                // narrow for both the label is what yields. Right-to-left from the row's trailing
                // edge; bounds are set whether or not the button is visible, so a reveal is a repaint
                // rather than a layout.
                var right = width
                for (button in listOf(deleteButton, editButton)) {
                    val size = button.preferredSize
                    right -= size.width
                    // Never past the row's leading edge: an absurdly narrow card overlaps its two icons
                    // rather than pushing one out of reach, which is the whole point of the reserve.
                    button.setBounds(right.coerceAtLeast(0), (height - size.height) / 2, size.width, size.height)
                    right -= iconGap
                }
                // ...and the label takes what the icons left, truncated by its own bounds.
                val labelSize = authorLabel.preferredSize
                authorLabel.setBounds(
                    0,
                    (height - labelSize.height) / 2,
                    labelSize.width.coerceAtMost(right.coerceAtLeast(0)),
                    labelSize.height,
                )
            }
        }.apply {
            isOpaque = true
            background = cardBackground
            cursor = Cursor.getDefaultCursor()
            add(authorLabel)
            add(editButton)
            add(deleteButton)
        }

        // Content width the body is measured AND laid out at — a fixed function of the width the ROW
        // allots the card, never of the card's own (possibly stretched) width. Keeping both sides on
        // this one value is what stops layout churn: getPreferredSize's guarded setSize settles to a
        // no-op instead of fighting a doLayout that sized the body differently every pass, a feedback
        // loop that pegs the CPU. The number comes from ReadingWidthRow, which reads the row's own
        // width — imposed by the platform from the viewport — so it stays an input pushed *down*.
        // Every horizontal offset the card has rides its *border*, so it reaches both sides through
        // `insets` here and nowhere else; no second offset exists to keep in sync.
        val contentWidth = { insets: java.awt.Insets ->
            (row.contentWidthPx() - insets.left - insets.right).coerceAtLeast(1)
        }

        val card = object : JPanel() {
            override fun getPreferredSize(): Dimension {
                val insets = insets
                val cw = contentWidth(insets)
                // Wrap the body to that content width before reading its height, so a multi-line comment
                // measures correctly regardless of layout timing — and so narrowing the editor re-wraps
                // the body and grows the card taller instead of clipping the text. Guarded so the setSize
                // is a no-op once the body already has this width — the height never depends on the
                // card's actual width, so it can't drive a re-layout loop.
                if (bodyArea.width != cw) bodyArea.setSize(cw, Int.MAX_VALUE)
                // headerHeight is a build-time constant, so hover cannot move this number.
                return Dimension(
                    cw + insets.left + insets.right,
                    headerHeight + headerGap + bodyArea.preferredSize.height + insets.top + insets.bottom,
                )
            }

            override fun doLayout() {
                val insets = insets
                val cw = contentWidth(insets)
                header.setBounds(insets.left, insets.top, cw, headerHeight)
                // Body is laid out at the SAME content width getPreferredSize measures it at (not the
                // card's actual width), so the two never diverge and the setSize guard stays satisfied.
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
            // Closes the card on every edge at the theme's own frame weight, then pads the content.
            // No accent line, so the insets stay symmetric and `contentWidth` loses the same amount
            // on both sides. Whatever the border becomes, it must stay *in the border*: that is the
            // single place both getPreferredSize and doLayout read a horizontal offset from.
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
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
                // Reveal this comment's range in the editor while its card is hovered. The overlay
                // owns the single transient highlight; the card only reports enter/exit.
                onHover(true)
            }

            override fun mouseExited(e: MouseEvent) {
                // Only a *true* leave — the pointer no longer over the card or any descendant — hides
                // the actions and clears the range highlight, so crossing into a child button never
                // flickers either off.
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

        // The row the platform stretches to the viewport and this card is capped inside. Following
        // the editor needs nothing further: a split or a window resize re-lays the row out, which
        // re-measures the card at the new width and re-wraps its body.
        row.setContent(card)
        return row
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
