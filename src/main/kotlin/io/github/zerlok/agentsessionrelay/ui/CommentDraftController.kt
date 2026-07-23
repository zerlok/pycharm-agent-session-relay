package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment

/**
 * Owns the single in-progress [CommentDraft] for the project. Opening a new draft — whether to
 * author a new comment or to edit an existing one — closes any previous one, so at most one comment
 * box is visible at a time (the single-active-box rule).
 *
 * It is also the owner of the "currently-editing comment id" (design D3): when an edit box is open,
 * [editingCommentId] names its comment, and every change (open/close) is announced on
 * [CommentEditingListener] so the [EditorReviewOverlay] cards reconcile — suppressing the edited
 * comment's read-only card while its box is open.
 */
@Service(Service.Level.PROJECT)
class CommentDraftController(private val project: Project) : Disposable {

    private var active: CommentDraft? = null
    private var editingId: CommentId? = null

    /**
     * The single in-progress draft, if any. Exposed so [RelayHoverListener] can route editor mouse
     * events to it for edge-drag range resizing (`adjustable-comment-range`) while it is open.
     */
    internal val activeDraft: CommentDraft? get() = active

    /** The comment currently open in an edit box, or null when authoring a new one / no box is open. */
    internal val editingCommentId: CommentId? get() = editingId

    /** Opens a draft to author a new comment over the given inclusive line range, replacing any current one. */
    fun open(editor: Editor, startLine: Int, endLine: Int) {
        close()
        active = CommentDraft.open(editor, startLine, endLine, onClose = ::close)
    }

    /**
     * Opens an edit box for [comment], seeded with its body and range (design D1). Goes through the
     * same [close]-first path so the single-active-box rule holds, then marks [comment] as the
     * currently-editing one and notifies overlays so its read-only card is suppressed.
     */
    fun openForEdit(editor: Editor, comment: ReviewComment) {
        close()
        val draft = CommentDraft.openForEdit(editor, comment, onClose = ::close) ?: return
        active = draft
        editingId = comment.id
        notifyEditingChanged()
    }

    fun close() {
        active?.let { Disposer.dispose(it) }
        active = null
        if (editingId != null) {
            editingId = null
            // The edit box closed (submit or cancel): let the overlay bring the card back.
            notifyEditingChanged()
        }
    }

    private fun notifyEditingChanged() {
        project.messageBus.syncPublisher(CommentEditingListener.TOPIC).editingChanged()
    }

    override fun dispose() = close()

    /**
     * Line range to comment on: the selection only when [clickedLine] is *contained* in it, else that
     * line alone. Without the containment test a selection anywhere in the file hijacked every gutter
     * "+", so the box opened nowhere near the click.
     *
     * Containment is tested against the **untrimmed** span while the trailing-line trim applies only
     * to the returned range. That asymmetry is what keeps both entry points on this one rule
     * (`review-annotation`: "Right-click with the caret on the excluded trailing line"): a top-down
     * drag-select leaves the caret on the very line the trim drops, and `AddReviewCommentAction`
     * passes that caret line.
     */
    fun rangeFor(editor: Editor, clickedLine: Int): Pair<Int, Int> {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return clickedLine to clickedLine

        val document = editor.document
        val first = document.getLineNumber(selection.selectionStart)
        val rawLast = document.getLineNumber(selection.selectionEnd)
        if (clickedLine < first || clickedLine > rawLast) return clickedLine to clickedLine

        // A selection ending exactly at a line start doesn't really include that trailing line.
        var last = rawLast
        if (last > first && selection.selectionEnd == document.getLineStartOffset(last)) last--
        return first to maxOf(first, last)
    }

    companion object {
        fun getInstance(project: Project): CommentDraftController =
            project.getService(CommentDraftController::class.java)
    }
}
