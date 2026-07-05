package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project

/**
 * Tracks the line under the mouse and renders a single "+" gutter icon on it (GitHub/GitLab
 * style). Clicking the icon opens an inline comment draft via [CommentDraftController]. Only one
 * hover marker exists at a time.
 */
class RelayHoverListener(private val project: Project) : EditorMouseMotionListener, EditorMouseListener {

    private data class Hover(val editor: Editor, val line: Int, val highlighter: RangeHighlighter)

    private var hover: Hover? = null

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        if (editor.project != project) return

        val line = lineAt(editor, e)
        if (line == null) {
            clear()
            return
        }

        val current = hover
        if (current != null && current.editor === editor && current.line == line) return

        clear()
        val highlighter = editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.LAST)
        highlighter.gutterIconRenderer = AddCommentGutterIconRenderer(editor, line)
        hover = Hover(editor, line, highlighter)
    }

    override fun mouseExited(e: EditorMouseEvent) {
        if (e.editor.project == project) clear()
    }

    private fun lineAt(editor: Editor, e: EditorMouseEvent): Int? {
        val line = editor.xyToLogicalPosition(e.mouseEvent.point).line
        if (line < 0 || line >= editor.document.lineCount) return null
        return line
    }

    private fun clear() {
        val current = hover ?: return
        hover = null
        if (current.highlighter.isValid) {
            current.editor.markupModel.removeHighlighter(current.highlighter)
        }
    }
}
