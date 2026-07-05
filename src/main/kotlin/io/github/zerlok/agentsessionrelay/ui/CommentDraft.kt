package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
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
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.zerlok.agentsessionrelay.domain.Anchoring
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * One in-progress review comment: a blue rectangle over the commented line range plus an
 * inline, full-width comment box rendered as a block inlay *below* the range (it pushes the
 * following code down rather than floating over it — GitHub/GitLab style).
 *
 * [submit] hands the captured comment to [ReviewBatchService] (the logic layer); user feedback is
 * driven off the store event by `ReviewBatchNotifier`, not raised here.
 */
class CommentDraft private constructor(
    private val editor: EditorEx,
    private val highlighter: RangeHighlighter,
    private val inlay: Inlay<*>,
    private val startLine: Int,
    private val endLine: Int,
) : Disposable {

    private fun submit(body: String) {
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val document = editor.document

        val anchorText = document.getText(TextRange(rangeStartOffset(document), rangeEndOffset(document)))
        val contextHash = Anchoring.contextHash(contextWindow(document))
        val subject =
            if (startLine == endLine) Subject.Line(file.url, startLine)
            else Subject.LineRange(file.url, startLine, endLine)

        ReviewBatchService.getInstance(project).addComment(subject, body.trim(), anchorText, contextHash)
    }

    private fun rangeStartOffset(document: Document): Int = document.getLineStartOffset(startLine)

    private fun rangeEndOffset(document: Document): Int = document.getLineEndOffset(endLine)

    /** The comment's lines plus [CONTEXT_LINES] of surrounding code — the re-anchoring seed. */
    private fun contextWindow(document: Document): String {
        val first = (startLine - CONTEXT_LINES).coerceAtLeast(0)
        val last = (endLine + CONTEXT_LINES).coerceAtMost(document.lineCount - 1)
        return document.getText(TextRange(document.getLineStartOffset(first), document.getLineEndOffset(last)))
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
        // Lines of surrounding code hashed into the anchor seed on each side of the range.
        private const val CONTEXT_LINES = 3

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
            val addButton = JButton("Add review comment")
            val cancelButton = JButton("Cancel")
            val panel = buildPanel(editor, textArea, addButton, cancelButton)

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

            val submit = { draft.submit(textArea.text.orEmpty()); onClose() }
            addButton.addActionListener { submit() }
            cancelButton.addActionListener { onClose() }
            registerShortcuts(textArea, draft, submit = submit, cancel = onClose)

            // The inlay isn't laid out yet, so requesting focus now (or via a bare
            // requestFocusInWindow) no-ops and the editor keeps the keyboard — every keystroke
            // then edits the code, not the box. Defer to after layout and route through
            // IdeFocusManager, which owns the async focus queue and wins over the editor
            // re-grabbing focus after the gutter click.
            val focusManager = editor.project?.let { IdeFocusManager.getInstance(it) }
            ApplicationManager.getApplication().invokeLater {
                if (!inlay.isValid) return@invokeLater
                if (focusManager != null) focusManager.requestFocus(textArea, true)
                else textArea.requestFocusInWindow()
            }
            return draft
        }

        private fun buildPanel(
            editor: EditorEx,
            textArea: JBTextArea,
            addButton: JButton,
            cancelButton: JButton,
        ): JPanel {
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(cancelButton)
                add(addButton)
            }
            return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = true
                background = editor.colorsScheme.defaultBackground
                border = JBUI.Borders.empty(8, 12)
                // Show a normal arrow (not the editor's text I-beam) while hovering the box chrome.
                cursor = Cursor.getDefaultCursor()
                add(JBScrollPane(textArea), BorderLayout.CENTER)
                add(buttons, BorderLayout.SOUTH)
                // Without a mouse listener the panel's padding/background isn't an event target, so
                // Swing retargets clicks and drags over it to the editor underneath — which then
                // selects code. Listening here makes the panel swallow those events, and a click on
                // the box chrome moves focus into the text area.
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        textArea.requestFocusInWindow()
                    }
                })
            }
        }

        private fun registerShortcuts(
            textArea: JBTextArea,
            parent: Disposable,
            submit: () -> Unit,
            cancel: () -> Unit,
        ) {
            // The box lives inside the editor's content component, so Enter/Ctrl+Enter/Esc are first
            // seen by the IDE key dispatcher, which resolves the surrounding editor from the focus
            // owner's ancestors and runs the *editor's* action (Enter -> newline in the code) before
            // Swing ever delivers the key. A plain Swing registerKeyboardAction loses that race.
            // Actions registered on the focused component itself, however, are dispatched ahead of
            // keymap/editor actions — so these win while the text area has focus.
            fun anAction(run: () -> Unit) = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) = run()
            }
            fun shortcuts(vararg keyStrokes: KeyStroke) =
                CustomShortcutSet(*keyStrokes.map { KeyboardShortcut(it, null) }.toTypedArray())
            // Plain/Shift Enter inserts a newline in the box (the editor would otherwise eat it).
            anAction { textArea.replaceSelection("\n") }.registerCustomShortcutSet(
                shortcuts(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                ),
                textArea,
                parent,
            )
            // Ctrl/Cmd+Enter submits (like a PR review box).
            anAction(submit).registerCustomShortcutSet(
                shortcuts(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK),
                ),
                textArea,
                parent,
            )
            // Esc cancels.
            anAction(cancel).registerCustomShortcutSet(
                shortcuts(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                textArea,
                parent,
            )
        }
    }
}
