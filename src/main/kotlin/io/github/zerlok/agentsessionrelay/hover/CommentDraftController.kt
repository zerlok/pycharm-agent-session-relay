package io.github.zerlok.agentsessionrelay.hover

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

/**
 * Owns the single in-progress [CommentDraft] for the project. Opening a new draft closes any
 * previous one, so at most one comment box is visible at a time.
 */
@Service(Service.Level.PROJECT)
class CommentDraftController : Disposable {

    private var active: CommentDraft? = null

    /** Opens a draft over the given inclusive line range, replacing any current one. */
    fun open(editor: Editor, startLine: Int, endLine: Int) {
        close()
        active = CommentDraft.open(editor, startLine, endLine, onClose = ::close)
    }

    fun close() {
        active?.let { Disposer.dispose(it) }
        active = null
    }

    override fun dispose() = close()

    /** Line range to comment on: the current selection if any, otherwise the clicked line. */
    fun rangeFor(editor: Editor, clickedLine: Int): Pair<Int, Int> {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return clickedLine to clickedLine

        val document = editor.document
        val first = document.getLineNumber(selection.selectionStart)
        var last = document.getLineNumber(selection.selectionEnd)
        // A selection ending exactly at a line start doesn't really include that trailing line.
        if (last > first && selection.selectionEnd == document.getLineStartOffset(last)) last--
        return first to maxOf(first, last)
    }

    companion object {
        fun getInstance(project: Project): CommentDraftController =
            project.getService(CommentDraftController::class.java)
    }
}
