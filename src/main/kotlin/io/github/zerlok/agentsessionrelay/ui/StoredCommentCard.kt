package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The always-expanded, read-only inline card for a stored comment (design D2): its body plus **Edit**
 * and **Delete** affordances, rendered as a full-width block inlay under the commented range by
 * [EditorReviewOverlay]. Purely a view — both buttons route through the store/controller, never
 * mutating a surface directly.
 *
 * Two shape decisions come from comment-box-sizing:
 *
 * - **Width cap.** The card panel is wrapped by [InlineWidth.capWidth] so the visible card is no wider
 *   than the editor's right-margin column (full width when no margin is configured), matching the
 *   authoring box.
 * - **Hover toolbar.** The old permanent `SOUTH` Edit/Delete row is gone; a resting card is only as
 *   tall as its body. The two actions live in a compact toolbar revealed only while the pointer is over
 *   the card. The toolbar is a **fixed top-right overlay** laid out over the body (not a `SOUTH` row and
 *   not a reserved strut), so the card's block-inlay height is identical at rest and on hover — showing
 *   or hiding the toolbar never reflows the code below it.
 *
 * Like [CommentDraft]'s box panel, the card swallows its own mouse events so a click on its chrome
 * doesn't retarget to the editor underneath and start a text selection (design "card buttons stealing
 * focus" risk).
 */
object StoredCommentCard {

    fun build(editor: EditorEx, body: String, onEdit: () -> Unit, onDelete: () -> Unit): JComponent {
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

        // Compact hover toolbar. Kept opaque with the card's background so it reads cleanly where it
        // overlaps the body's top-right corner. Hidden at rest; toggled on pointer enter/exit below.
        // Icon-only buttons (a pencil / trash), not text buttons — GitHub/GitLab-small, with the
        // action named in a tooltip. `InplaceButton` is the platform's borderless icon button (as used
        // in tab/close affordances): it draws its own hover highlight and stays icon-sized.
        val editButton = iconButton("Edit", AllIcons.Actions.Edit, onEdit)
        val deleteButton = iconButton("Delete", AllIcons.Actions.GC, onDelete)
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = editor.colorsScheme.defaultBackground
            add(editButton)
            add(deleteButton)
            isVisible = false
        }

        // The card opens at the shared base width (~80 columns, clamped to the right-margin cap) rather
        // than shrinking to a short body's width (comment-box-sizing feedback). Only the width is pinned;
        // height is still driven **only** by the body, measured at that fixed width so a multi-line
        // comment reports the right height.
        val baseWidth = InlineWidth.baseWidthPx(editor)

        // The card: an opaque, bordered panel with the toolbar floated over the body's top-right corner
        // (see doLayout). Because the toolbar never contributes to the preferred size, revealing it on
        // hover leaves the inlay's height unchanged.
        // Content width the body is measured AND laid out at — a fixed function of baseWidth, never of
        // the card's own (possibly stretched) width. Keeping both sides on this one value is what stops
        // the layout churn: getPreferredSize's guarded setSize settles to a no-op instead of fighting a
        // doLayout that sized the body to a different width every pass (the feedback that pegged the CPU).
        val contentWidth = { insets: java.awt.Insets -> (baseWidth - insets.left - insets.right).coerceAtLeast(1) }

        val card = object : JPanel() {
            // Pin the outer width to baseWidth so the capWidth BoxLayout wrapper can't stretch the card
            // toward the (wider) right-margin cap. This both keeps the card no wider than its reading
            // column — so the top-right toolbar stays on screen — and makes the card's actual width equal
            // the width the body is measured at.
            override fun getMaximumSize(): Dimension = Dimension(baseWidth, Int.MAX_VALUE)

            override fun getPreferredSize(): Dimension {
                val insets = insets
                val cw = contentWidth(insets)
                // Wrap the body to that fixed content width before reading its height, so a multi-line
                // comment measures correctly regardless of layout timing. Guarded so the setSize is a
                // no-op once the body already has this width — the height never depends on the card's
                // actual width, so it can't drive a re-layout loop.
                if (bodyArea.width != cw) bodyArea.setSize(cw, Int.MAX_VALUE)
                return Dimension(baseWidth, bodyArea.preferredSize.height + insets.top + insets.bottom)
            }

            override fun doLayout() {
                val insets = insets
                val cw = contentWidth(insets)
                // Body is laid out at the SAME fixed content width getPreferredSize measures it at (not
                // the card's actual width), so the two never diverge and the setSize guard stays satisfied.
                bodyArea.setBounds(insets.left, insets.top, cw, height - insets.top - insets.bottom)
                // Toolbar floats in the top-right corner of the (baseWidth-wide) reading column, so its
                // Edit/Delete icons always sit inside the visible box rather than off past the margin.
                // A fixed overlay: its visibility never changes the card's height. Painted above the body
                // because it is added at a lower z-index (index 0) than the body.
                if (toolbar.isVisible) {
                    val tb = toolbar.preferredSize
                    toolbar.setBounds(insets.left + cw - tb.width, insets.top, tb.width, tb.height)
                }
            }
        }.apply {
            isOpaque = true
            background = editor.colorsScheme.defaultBackground
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 12),
            )
            cursor = Cursor.getDefaultCursor()
            // Add the toolbar first so it paints on top of the body where they overlap (lower index =
            // higher z-order in a plain container). No layout manager: the overridden doLayout above
            // positions both children explicitly.
            add(toolbar)
            add(bodyArea)
        }

        // Reveal the toolbar only while the pointer is over the card (mouse enter/exit). Enter/exit
        // also fire when the pointer crosses into a child (the body or a button), so on exit we only
        // hide when the pointer has truly left the whole card — getMousePosition(true) inspects the
        // card *and its descendants*. Attaching the same listener to the children (not just the card)
        // makes the enter fire wherever the pointer first lands. This listener set also makes the card
        // a real mouse-event target, so — together with the explicit mousePressed swallow below —
        // Swing does not retarget clicks over the card to the editor beneath it (same pattern as
        // CommentDraft).
        val hover = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setToolbarVisible(card, toolbar, true)

            override fun mouseExited(e: MouseEvent) {
                if (card.getMousePosition(true) == null) setToolbarVisible(card, toolbar, false)
            }

            // Swallow presses so a click on the card's padding/background stays on the card instead of
            // starting a text selection in the editor underneath.
            override fun mousePressed(e: MouseEvent) {}
        }
        card.addMouseListener(hover)
        bodyArea.addMouseListener(hover)
        toolbar.addMouseListener(hover)
        editButton.addMouseListener(hover)
        deleteButton.addMouseListener(hover)

        // Cap the card at the editor's right margin (comment-box-sizing), same as the authoring box.
        return InlineWidth.capWidth(card, InlineWidth.rightMarginPx(editor))
    }

    private fun iconButton(tooltip: String, icon: Icon, onClick: () -> Unit): InplaceButton =
        InplaceButton(tooltip, icon) { onClick() }

    private fun setToolbarVisible(card: JComponent, toolbar: JComponent, visible: Boolean) {
        if (toolbar.isVisible == visible) return
        toolbar.isVisible = visible
        // The card's preferred size ignores the toolbar, so this re-lays-out only the toolbar's
        // position — the inlay height stays put.
        card.revalidate()
        card.repaint()
    }
}
