package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.domain.Subjects
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

/**
 * A comment's live anchor, read from its marker in **one** pass: where the marker is now ([subject])
 * and the text it spans now ([text]). The two are deliberately produced together — asking for them
 * separately lets a document change land in between, and then the anchor check compares one range's
 * text against another range's position, which is precisely the confident-but-wrong answer anchor
 * validation exists to prevent.
 */
class LiveAnchor(val subject: Subject, val text: String)

/**
 * Owner of the stored-comment position markers for **one [Document]** (ARCHITECTURE.md — "View
 * objects and their lifetimes"): one [RangeHighlighter] per stored comment whose subject points at
 * this document's file, keyed by [CommentId], written into the document-scoped [DocumentMarkupModel].
 *
 * Ownership is per-document rather than per-editor because the markup these markers live in is
 * per-document: a file shown in two splits has one markup model, so a per-editor owner painted the
 * same range twice. It also makes "the comment's live position" a single answer per document instead
 * of one answer per open editor that callers would have to reconcile.
 *
 * The marker is two things at once — the live position source (via [liveState] / [currentPositions],
 * and the source for the card-hover range highlight [EditorReviewOverlay] draws) and the at-rest
 * gutter bar saying these lines carry a comment. It carries **no gutter icon** and no text attributes,
 * so nothing washes the code at rest.
 *
 * The store is the single source of truth: this holds no comment list of its own. It seeds from
 * [ReviewBatchService.comments] on creation and reconciles **by diff** on every [ReviewBatchListener]
 * event (add new, dispose removed, leave the rest).
 *
 * A comment whose recorded range does not fit this document gets **no marker** and is marked
 * [CommentStatus.ORPHANED] — never clamped into range. Because it then has no entry in [liveState],
 * no sync point can flush a substitute position over its recorded one; that is closed structurally
 * rather than by a guard a later edit could drop.
 *
 * Lifecycle is owned by [EditorReviewOverlayService], which creates this on the first qualifying
 * editor for the document and disposes it (after the close flush) when the last one is released.
 */
class DocumentReviewMarkers(
    private val project: Project,
    private val document: Document,
) : ReviewBatchListener, Disposable {

    private val fileUrl: String? = FileDocumentManager.getInstance().getFile(document)?.url

    private val markup: MarkupModel = DocumentMarkupModel.forDocument(document, project, true)

    private val markers = HashMap<CommentId, RangeHighlighter>()

    // The subject each marker was built from. A marker tracks in-IDE edits live (its offsets drift),
    // but the store subject it was seeded from does not — so an explicit store subject change (the
    // user re-editing the range) is detected by comparing the stored comment against this recorded
    // value, never against the marker's live offsets, which would fight its live-position role.
    private val markerSubjects = HashMap<CommentId, Subject>()

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
     * Every owned comment's live anchor — position **and** current text — read off each valid marker
     * in a single pass. Invalid markers, and comments with no marker at all (orphaned, or in another
     * file), have no entry: absence means "no live anchor", never "moved somewhere else".
     */
    fun liveState(): Map<CommentId, LiveAnchor> {
        val url = fileUrl ?: return emptyMap()
        val result = HashMap<CommentId, LiveAnchor>()
        for ((id, marker) in markers) {
            if (!marker.isValid) continue
            // One read of this marker's offsets feeds both the subject and the text below, so the two
            // cannot describe different instants.
            val startOffset = marker.startOffset
            val endOffset = marker.endOffset
            val startLine = document.getLineNumber(startOffset)
            val endLine = document.getLineNumber(endOffset)
            val subject =
                if (startLine == endLine) Subject.Line(url, startLine)
                else Subject.LineRange(url, startLine, endLine)
            result[id] = LiveAnchor(subject, document.getText(TextRange(startOffset, endOffset)))
        }
        return result
    }

    /**
     * The projection of [liveState] the save, editor-close, and export sync points flush into the
     * store: each owned comment's CURRENT line range as a fresh [Subject]. A projection rather than
     * its own marker read, so a flushed position can never disagree with the anchor text validated
     * beside it.
     */
    fun currentPositions(): Map<CommentId, Subject> = liveState().mapValues { (_, anchor) -> anchor.subject }

    /**
     * Whether [subject]'s recorded range exists in this document — the rule deciding whether a comment
     * gets a marker and a card at all. It is [Subjects.fitsIn], the same rule the delivery layer
     * applies to a file's content at export, so an editor-time and an export-time `ORPHANED` verdict
     * can never disagree.
     */
    fun fits(subject: Subject): Boolean = Subjects.fitsIn(subject, document.lineCount)

    private fun reconcile() {
        val url = fileUrl ?: return
        val wanted = ReviewBatchService.getInstance(project).comments()
            .filter { Subjects.fileUrlOf(it.subject) == url }
            .associateBy { it.id }

        // Dispose markers whose comment is gone (deleted / cleared / moved off this file).
        for (id in markers.keys - wanted.keys) removeMarker(id)
        // Add markers new to this document; reposition (recreate) one whose store subject changed since
        // it was built (the user edited its range); leave the rest so live in-IDE drift is preserved.
        for ((id, comment) in wanted) {
            if (id !in markers) addMarker(comment)
            else if (markerSubjects[id] != comment.subject) {
                removeMarker(id)
                addMarker(comment)
            }
        }
    }

    /**
     * Places [comment]'s marker, or orphans it when its recorded range does not exist here.
     *
     * **Both status writes happen after this map is in its final state**, because a status change
     * publishes `commentUpdated` and so re-enters [reconcile] synchronously. Writing the status first
     * would let that second pass see "no marker yet" and add one, which this pass would then overwrite
     * and leak. With the map settled first, the re-entered pass finds nothing to change and the
     * cascade stops there — [ReviewBatchService.updateStatus] publishes nothing for an unchanged status.
     */
    private fun addMarker(comment: ReviewComment) {
        val (startLine, endLine) = Subjects.linesOf(comment.subject) ?: return
        if (!fits(comment.subject)) {
            markStatus(comment, CommentStatus.ORPHANED)
            return
        }

        val highlighter = markup.addRangeHighlighter(
            document.getLineStartOffset(startLine),
            document.getLineEndOffset(endLine),
            HighlighterLayer.LAST,
            null,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        // No gutter *icon* and no text attributes, so nothing washes the code at rest. The marker
        // does carry the resting gutter bar, making it both the live position source and the "these
        // lines have a comment" signal: riding the marker that is already kept live means the bar
        // drifts with in-IDE edits for free and needs no reconcile path of its own. This bar is the
        // ONLY place a commented range wears the accent — the card deliberately carries none, so
        // there is one mark per range rather than two parallel lines.
        highlighter.lineMarkerRenderer = RangeHighlight.gutterBar(RelayStyle.ACCENT)
        markers[comment.id] = highlighter
        markerSubjects[comment.id] = comment.subject

        // The file grew back / the comment was re-placed: it has a real position again.
        if (comment.status == CommentStatus.ORPHANED) markStatus(comment, CommentStatus.ACTIVE)
    }

    // Only ORPHANED is decided here; a STALE comment is left alone (its range still fits — it is its
    // *text* that changed, which only the export sync point checks).
    private fun markStatus(comment: ReviewComment, status: CommentStatus) {
        ReviewBatchService.getInstance(project).updateStatus(comment.id, status)
    }

    private fun removeMarker(id: CommentId) {
        markerSubjects.remove(id)
        val highlighter = markers.remove(id) ?: return
        if (highlighter.isValid) markup.removeHighlighter(highlighter)
    }

    override fun dispose() {
        // MessageBus connection was parented to `this` and disconnects with it.
        for (highlighter in markers.values) if (highlighter.isValid) markup.removeHighlighter(highlighter)
        markers.clear()
        markerSubjects.clear()
    }

}
