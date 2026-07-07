package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project

/**
 * Tracks the line under the mouse and renders a single "+" gutter icon on it (GitHub/GitLab
 * style). Clicking the icon opens an inline comment draft via [CommentDraftController]. Only one
 * hover marker exists at a time.
 *
 * While a draft is open this same shared editor mouse channel drives its adjustable-range edge grips
 * (`adjustable-comment-range`): pointer moves within an edge grab zone show the resize affordance
 * (and suppress the competing "+"), a press on an edge claims the gesture (`consume()` so the editor
 * never text-selects), drags resize the range live, and release rebuilds the box. All edge work is
 * delegated to the active [CommentDraft] and is scoped to when a draft is open, so it never fights
 * the "+" hover or the gutter markers.
 */
class RelayHoverListener(private val project: Project) : EditorMouseMotionListener, EditorMouseListener {

    private data class Hover(val editor: Editor, val line: Int, val highlighter: RangeHighlighter)

    private var hover: Hover? = null

    private fun activeDraftFor(editor: Editor): CommentDraft? {
        if (editor.project != project) return null
        return CommentDraftController.getInstance(project).activeDraft?.takeIf { it.handles(editor) }
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor
        if (editor.project != project) return

        // While a draft is open, let it own the edge affordance first. If the pointer is on a range
        // edge, suppress the "+" so the two don't compete (task 2.3).
        val draft = activeDraftFor(editor)
        if (draft != null) {
            val onEdge = draft.onMouseMoved(e.mouseEvent.point.y, e.area == EditorMouseEventArea.EDITING_AREA)
            if (onEdge) {
                clear()
                return
            }
        }

        // Confine the "+" to the left gutter (D1): show it only while the pointer is over a gutter
        // sub-area and suppress it over the code (editing) area. "Gutter" is *not* EDITING_AREA — i.e.
        // LINE_NUMBERS_AREA, LINE_MARKERS_AREA, ANNOTATIONS_AREA, FOLDING_OUTLINE_AREA. Spanning every
        // non-editing gutter area (rather than the number column alone) keeps the icon clickable: the
        // "+" renders in the marker column (LINE_MARKERS_AREA), so moving the pointer onto it to click
        // must not leave the trigger zone and clear it first.
        if (e.area == null || e.area == EditorMouseEventArea.EDITING_AREA) {
            clear()
            return
        }

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

    override fun mouseDragged(e: EditorMouseEvent) {
        val draft = activeDraftFor(e.editor) ?: return
        if (draft.onMouseDragged(e.mouseEvent.point.y)) e.consume()
    }

    override fun mousePressed(e: EditorMouseEvent) {
        val draft = activeDraftFor(e.editor) ?: return
        if (draft.onMousePressed(e.mouseEvent.point.y, e.area == EditorMouseEventArea.EDITING_AREA)) {
            // Claim the gesture so the editor doesn't also start a text selection (D2). Drop the "+".
            clear()
            e.consume()
        }
    }

    override fun mouseReleased(e: EditorMouseEvent) {
        // A drag can end with the pointer off the editor; the release is still delivered here to the
        // component that captured the press, so the box always rebuilds (design "release outside").
        val draft = activeDraftFor(e.editor) ?: return
        if (draft.onMouseReleased()) e.consume()
    }

    override fun mouseExited(e: EditorMouseEvent) {
        if (e.editor.project != project) return
        activeDraftFor(e.editor)?.onMouseExited()
        clear()
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
