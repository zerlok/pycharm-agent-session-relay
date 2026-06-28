package io.github.zerlok.agentsessionrelay.hover

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * One in-progress review comment: a blue rectangle over the commented line range plus an
 * inline, full-width comment box rendered as a block inlay *below* the range (it pushes the
 * following code down rather than floating over it — GitHub/GitLab style).
 *
 * For now [submit] only logs the comment and shows a notification; persisting it into a review
 * batch is the next change.
 */
class CommentDraft private constructor(
    private val editor: EditorEx,
    private val highlighter: RangeHighlighter,
    private val inlay: Inlay<*>,
    private val startLine: Int,
    private val endLine: Int,
) : Disposable {

    private fun submit(body: String) {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val lines = if (startLine == endLine) "line ${startLine + 1}" else "lines ${startLine + 1}-${endLine + 1}"
        LOG.info("Relay add-comment: file=${file?.path} $lines body=\"${body.trim()}\"")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Agent Session Relay")
            .createNotification(
                "Relay: comment captured (not yet stored)",
                "$lines" + if (body.isBlank()) "" else " — " + body.trim().take(120),
                NotificationType.INFORMATION,
            )
            .notify(editor.project)
    }

    override fun dispose() {
        if (highlighter.isValid) {
            editor.markupModel.removeHighlighter(highlighter)
        }
        if (inlay.isValid) {
            Disposer.dispose(inlay)
        }
    }

    companion object {
        private val LOG = logger<CommentDraft>()

        /** Light blue wash over the commented lines, à la a pull-request review selection. */
        private val RANGE_BACKGROUND = JBColor(Color(0xDD, 0xE7, 0xFF), Color(0x2A, 0x3A, 0x5A))

        /**
         * Opens a draft over [startLine]..[endLine]. [onClose] is invoked when the user submits
         * or cancels, so the owner can dispose this draft. Returns null if the editor can't host
         * an inline component.
         */
        fun open(editor: Editor, startLine: Int, endLine: Int, onClose: () -> Unit): CommentDraft? {
            if (editor !is EditorEx) return null

            val document = editor.document
            if (document.lineCount == 0) return null
            val start = startLine.coerceIn(0, document.lineCount - 1)
            val end = endLine.coerceIn(start, document.lineCount - 1)

            val rangeStart = document.getLineStartOffset(start)
            val rangeEnd = document.getLineEndOffset(end)

            val attributes = TextAttributes().apply { backgroundColor = RANGE_BACKGROUND }
            val highlighter = editor.markupModel.addRangeHighlighter(
                rangeStart,
                rangeEnd,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE,
            )

            val textArea = JBTextArea(4, 0).apply {
                lineWrap = true
                wrapStyleWord = true
            }
            val panel = buildPanel(editor, textArea)

            val properties = EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* showWhenFolded = */ true,
                /* fullWidth = */ true,
                /* priority = */ 0,
                /* offset = */ rangeEnd,
            )
            val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, properties)
            if (inlay == null) {
                editor.markupModel.removeHighlighter(highlighter)
                return null
            }

            val draft = CommentDraft(editor, highlighter, inlay, start, end)

            wireActions(panel, textArea, submit = { draft.submit(textArea.text.orEmpty()); onClose() }, cancel = onClose)

            ApplicationManager.getApplication().invokeLater {
                if (inlay.isValid) textArea.requestFocusInWindow()
            }
            return draft
        }

        private fun buildPanel(editor: EditorEx, textArea: JBTextArea): JPanel {
            val addButton = JButton("Add review comment")
            val cancelButton = JButton("Cancel")
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(cancelButton)
                add(addButton)
            }
            return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                background = editor.colorsScheme.defaultBackground
                border = JBUI.Borders.empty(8, 12)
                add(JBScrollPane(textArea), BorderLayout.CENTER)
                add(buttons, BorderLayout.SOUTH)
                putClientProperty(ADD_BUTTON, addButton)
                putClientProperty(CANCEL_BUTTON, cancelButton)
            }
        }

        private const val ADD_BUTTON = "relay.addButton"
        private const val CANCEL_BUTTON = "relay.cancelButton"

        private fun wireActions(panel: JPanel, textArea: JBTextArea, submit: () -> Unit, cancel: () -> Unit) {
            (panel.getClientProperty(ADD_BUTTON) as JButton).addActionListener { submit() }
            (panel.getClientProperty(CANCEL_BUTTON) as JButton).addActionListener { cancel() }

            // Esc cancels; Ctrl/Cmd+Enter submits (like a PR review box).
            panel.registerKeyboardAction(
                { cancel() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            )
            for (modifier in intArrayOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK)) {
                panel.registerKeyboardAction(
                    { submit() },
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier),
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                )
            }
        }
    }
}
