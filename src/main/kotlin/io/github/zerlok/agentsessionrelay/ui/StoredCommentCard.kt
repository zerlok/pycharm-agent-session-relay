package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The always-expanded, read-only inline card for a stored comment (design D2): its body plus **Edit**
 * and **Delete** affordances, rendered as a full-width block inlay under the commented range by
 * [EditorReviewOverlay]. Purely a view — both buttons route through the store/controller, never
 * mutating a surface directly.
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

        val editButton = JButton("Edit").apply { addActionListener { onEdit() } }
        val deleteButton = JButton("Delete").apply { addActionListener { onDelete() } }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(editButton)
            add(deleteButton)
        }

        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = true
            background = editor.colorsScheme.defaultBackground
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 12),
            )
            cursor = Cursor.getDefaultCursor()
            add(bodyArea, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            // Swallow mouse events so the padding/background is a real event target and Swing doesn't
            // retarget clicks over the card to the editor beneath it (same pattern as CommentDraft).
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {}
            })
        }
    }
}
