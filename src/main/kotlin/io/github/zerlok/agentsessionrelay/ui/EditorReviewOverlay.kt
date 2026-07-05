package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

/**
 * The per-editor view of the review batch (ARCHITECTURE §3.2, §3.3). It owns one live
 * [RangeHighlighter] per stored comment whose subject points at *this* editor's file, keyed by
 * [CommentId], carrying the persistent stored-comment gutter marker. The domain record stays inert;
 * the highlighter is the derived, per-editor projection — and, while the file is open, the live
 * source of truth for the comment's position (queried via [currentPositions]).
 *
 * The store is the single source of truth: the overlay never keeps its own comment list. It seeds
 * from [ReviewBatchService.comments] on creation and, on every [ReviewBatchListener] event,
 * reconciles its markers **by diff** (retained-mode: add new, dispose removed, leave the rest).
 *
 * Lifecycle is owned by [EditorReviewOverlayService], which parents this [Disposable] to the project
 * service and disposes it in `editorReleased`.
 */
class EditorReviewOverlay(
    private val project: Project,
    private val editor: Editor,
) : ReviewBatchListener, Disposable {

    private val document: Document = editor.document
    private val fileUrl: String? = FileDocumentManager.getInstance().getFile(document)?.url

    // Gutter/highlight ride the document markup so they show across every split of this file
    // (ARCHITECTURE §3.3); the overlay is still owned per-editor.
    private val markup: MarkupModel = DocumentMarkupModel.forDocument(document, project, true)

    private val markers = HashMap<CommentId, RangeHighlighter>()

    init {
        project.messageBus.connect(this).subscribe(ReviewBatchListener.TOPIC, this)
        reconcile()
    }

    // -- Store events: reconcile by diff (works uniformly for add / remove / update / clear). --

    override fun commentAdded(comment: ReviewComment) = reconcile()

    override fun commentRemoved(comment: ReviewComment) = reconcile()

    override fun commentUpdated(comment: ReviewComment) = reconcile()

    override fun batchCleared() = reconcile()

    /**
     * The position-sync query the delivery stage flushes at submit time (ARCHITECTURE §3.2): each
     * owned comment's CURRENT line range, read off its live marker (which tracks in-IDE edits), as a
     * fresh [Subject]. Invalid (orphaned) markers are skipped.
     */
    fun currentPositions(): Map<CommentId, Subject> {
        val url = fileUrl ?: return emptyMap()
        val result = HashMap<CommentId, Subject>()
        for ((id, marker) in markers) {
            if (!marker.isValid) continue
            val startLine = document.getLineNumber(marker.startOffset)
            val endLine = document.getLineNumber(marker.endOffset)
            result[id] =
                if (startLine == endLine) Subject.Line(url, startLine)
                else Subject.LineRange(url, startLine, endLine)
        }
        return result
    }

    private fun reconcile() {
        val url = fileUrl ?: return
        val wanted = ReviewBatchService.getInstance(project).comments()
            .filter { fileUrlOf(it.subject) == url }
            .associateBy { it.id }

        // Dispose markers whose comment is gone (deleted / cleared / moved off this file).
        for (id in markers.keys - wanted.keys) removeMarker(id)
        // Add markers for comments new to this editor; leave existing ones (live source of truth).
        for ((id, comment) in wanted) if (id !in markers) addMarker(comment)
    }

    private fun addMarker(comment: ReviewComment) {
        val (startLine, endLine) = linesOf(comment.subject) ?: return
        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        val start = startLine.coerceIn(0, lastLine)
        val end = endLine.coerceIn(start, lastLine)

        val highlighter = markup.addRangeHighlighter(
            document.getLineStartOffset(start),
            document.getLineEndOffset(end),
            HighlighterLayer.LAST,
            null,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        highlighter.gutterIconRenderer =
            StoredCommentGutterIconRenderer(project, comment.id, tooltipFor(comment))
        markers[comment.id] = highlighter
    }

    private fun removeMarker(id: CommentId) {
        val highlighter = markers.remove(id) ?: return
        if (highlighter.isValid) markup.removeHighlighter(highlighter)
    }

    private fun tooltipFor(comment: ReviewComment): String {
        val snippet = comment.body.trim().take(TOOLTIP_CHARS)
        return if (snippet.isBlank()) "Relay review comment" else "Relay: $snippet"
    }

    override fun dispose() {
        // MessageBus connection was parented to `this` and disconnects with it.
        for (highlighter in markers.values) if (highlighter.isValid) markup.removeHighlighter(highlighter)
        markers.clear()
    }

    companion object {
        private const val TOOLTIP_CHARS = 120

        private fun fileUrlOf(subject: Subject): String? = when (subject) {
            is Subject.Line -> subject.fileUrl
            is Subject.LineRange -> subject.fileUrl
            is Subject.File -> subject.fileUrl
            is Subject.Files -> null
            Subject.Project -> null
        }

        private fun linesOf(subject: Subject): Pair<Int, Int>? = when (subject) {
            is Subject.Line -> subject.line to subject.line
            is Subject.LineRange -> subject.startLine to subject.endLine
            else -> null
        }
    }
}
