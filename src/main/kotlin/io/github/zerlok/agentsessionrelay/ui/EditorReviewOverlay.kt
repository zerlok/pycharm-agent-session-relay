package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

/**
 * The per-editor view of the review batch (ARCHITECTURE §3.2, §3.3). It owns one live
 * [RangeHighlighter] per stored comment whose subject points at *this* editor's file, keyed by
 * [CommentId]. The marker carries **no gutter icon** (design D5): it is the invisible live position
 * source — the derived, per-editor projection of the inert domain record and, while the file is open,
 * the source of truth for the comment's position (queried via [currentPositions]) and for the
 * card-hover range highlight below. The stored-comment resting indicator is the always-visible card.
 *
 * The store is the single source of truth: the overlay never keeps its own comment list. It seeds
 * from [ReviewBatchService.comments] on creation and, on every [ReviewBatchListener] event,
 * reconciles its markers **by diff** (retained-mode: add new, dispose removed, leave the rest).
 *
 * Alongside the position markers it owns a parallel per-comment map of **read-only card inlays**
 * (design D2): a full-width block inlay under each stored comment's range showing its body plus Edit
 * and Delete. Cards reconcile off the same store events (rebuilding on `commentUpdated`, since a
 * card's body/position are baked in at creation) and additionally off [CommentEditingListener] — the
 * comment currently open in an edit box is skipped so its card and box never overlap (design D3).
 *
 * Lifecycle is owned by [EditorReviewOverlayService], which parents this [Disposable] to the project
 * service and disposes it in `editorReleased`.
 */
class EditorReviewOverlay(
    private val project: Project,
    private val editor: Editor,
) : ReviewBatchListener, CommentEditingListener, Disposable {

    // Internal so [EditorReviewOverlayService] can scope a document-save flush to the overlays anchored
    // to the saved document (identity compare); it is also this overlay's live position source.
    internal val document: Document = editor.document
    private val fileUrl: String? = FileDocumentManager.getInstance().getFile(document)?.url

    // Gutter/highlight ride the document markup so they show across every split of this file
    // (ARCHITECTURE §3.3); the overlay is still owned per-editor.
    private val markup: MarkupModel = DocumentMarkupModel.forDocument(document, project, true)

    private val markers = HashMap<CommentId, RangeHighlighter>()

    // The subject each marker was built from. A marker tracks in-IDE edits live (its offsets drift),
    // but the store subject it was seeded from does not — so an explicit store subject change (the user
    // re-editing the range) is detected by comparing the stored comment against this recorded value,
    // never against the marker's live offsets, which would fight the live-position-source role (§3.2).
    private val markerSubjects = HashMap<CommentId, Subject>()

    // The read-only card inlays, keyed by comment. Unlike a marker (whose position tracks edits
    // live), a card's body and offset are fixed at creation, so it is rebuilt when its comment
    // changes; [cardModels] records the comment each card was built from to detect that.
    private val cards = HashMap<CommentId, Inlay<*>>()
    private val cardModels = HashMap<CommentId, ReviewComment>()

    // The single transient "range + gutter" highlight shown for the comment whose card is currently
    // hovered (design D4). View-only and never touching the store (retained-mode, store-is-truth): at
    // most one exists at a time, built on hover-in from the comment's live marker range and disposed on
    // hover-out (and with the overlay). At rest there is no stored range wash.
    private var hoverHighlight: RangeHighlight? = null
    private var hoverHighlightId: CommentId? = null

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ReviewBatchListener.TOPIC, this)
        connection.subscribe(CommentEditingListener.TOPIC, this)
        reconcile()
    }

    // -- Store events: reconcile by diff (works uniformly for add / remove / update / clear). --

    override fun commentAdded(comment: ReviewComment) = reconcile()

    override fun commentRemoved(comment: ReviewComment) = reconcile()

    override fun commentUpdated(comment: ReviewComment) = reconcile()

    override fun batchCleared() = reconcile()

    // -- Editing state changed: a card must appear/disappear for the (un)edited comment. --

    override fun editingChanged() = reconcileCards()

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
        reconcileMarkers()
        reconcileCards()
    }

    private fun reconcileMarkers() {
        val url = fileUrl ?: return
        val wanted = ReviewBatchService.getInstance(project).comments()
            .filter { fileUrlOf(it.subject) == url }
            .associateBy { it.id }

        // Dispose markers whose comment is gone (deleted / cleared / moved off this file).
        for (id in markers.keys - wanted.keys) removeMarker(id)
        // Add markers new to this editor; reposition (recreate) one whose store subject changed since it
        // was built (the user edited its range); leave the rest so live in-IDE drift is preserved.
        for ((id, comment) in wanted) {
            if (id !in markers) addMarker(comment)
            else if (markerSubjects[id] != comment.subject) {
                removeMarker(id)
                addMarker(comment)
            }
        }
    }

    /**
     * Reconcile the read-only cards (design D2/D3). A card is wanted for every stored comment on this
     * file **except** the one currently open in an edit box. Because a card bakes in its body and
     * offset, a wanted comment whose record differs from what its card was built from is rebuilt
     * (dispose + add), not left in place — this is how a `commentUpdated` (body or position) refreshes
     * the card.
     */
    private fun reconcileCards() {
        val url = fileUrl ?: return
        val editingId = CommentDraftController.getInstance(project).editingCommentId
        val wanted = ReviewBatchService.getInstance(project).comments()
            .filter { fileUrlOf(it.subject) == url && it.id != editingId }
            .associateBy { it.id }

        // Dispose cards whose comment is gone or is now being edited.
        for (id in cards.keys.toList()) if (id !in wanted) removeCard(id)
        // Add cards for new comments; rebuild those whose body or position changed.
        for ((id, comment) in wanted) {
            val built = cardModels[id]
            if (built == null) addCard(comment)
            else if (built.body != comment.body || built.subject != comment.subject) {
                removeCard(id)
                addCard(comment)
            }
        }
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
        // No gutter icon (design D5): the marker is now purely the invisible live position source (and
        // the source for the card-hover range highlight). The stored-comment resting indicator is the
        // always-visible card; the range is revealed on card hover. `StoredCommentGutterIconRenderer`
        // is kept unwired in the tree for the deferred hide-comments change to re-attach here.
        markers[comment.id] = highlighter
        markerSubjects[comment.id] = comment.subject
    }

    private fun removeMarker(id: CommentId) {
        markerSubjects.remove(id)
        val highlighter = markers.remove(id) ?: return
        if (highlighter.isValid) markup.removeHighlighter(highlighter)
    }

    /**
     * Places [comment]'s read-only card as a full-width block inlay under its range's bottom line —
     * the same [EditorEmbeddedComponentManager] placement the authoring box uses, so card and box
     * anchor identically. Card **Edit** re-opens the box seeded (via [CommentDraftController], passing
     * the comment at its *live* range so the box opens where the marker actually is); **Delete** routes
     * through the store so every surface reconciles off the resulting event.
     */
    private fun addCard(comment: ReviewComment) {
        val editorEx = editor as? EditorEx ?: return
        val (startLine, endLine) = linesOf(comment.subject) ?: return
        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        val end = endLine.coerceIn(startLine.coerceIn(0, lastLine), lastLine)

        val panel = StoredCommentCard.build(
            editorEx,
            comment.body,
            onEdit = {
                // Open over the marker's CURRENT range (which tracks in-IDE edits), not the possibly
                // stale stored subject, so the edit box lines up with the card the user clicked.
                val live = currentPositions()[comment.id]
                val target = if (live != null) comment.copy(subject = live) else comment
                CommentDraftController.getInstance(project).openForEdit(editor, target)
            },
            onDelete = { ReviewBatchService.getInstance(project).removeComment(comment.id) },
            // Reveal / clear this comment's range as the pointer enters / leaves the card (design D4).
            onHover = { hovered -> onCardHover(comment.id, hovered) },
        )

        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(),
            null,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* showWhenFolded = */ true,
            /* fullWidth = */ true,
            /* priority = */ 0,
            /* offset = */ document.getLineEndOffset(end),
        )
        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editorEx, panel, properties) ?: return
        cards[comment.id] = inlay
        cardModels[comment.id] = comment
    }

    private fun removeCard(id: CommentId) {
        cards.remove(id)?.let { if (it.isValid) Disposer.dispose(it) }
        cardModels.remove(id)
        // A card being removed/rebuilt while hovered would otherwise leave its highlight orphaned.
        clearHoverHighlightFor(id)
    }

    /**
     * Card hover-in / hover-out for [id] (design D4), the seam [StoredCommentCard]'s enter/exit calls
     * back into (also a test seam). On hover-in, show the shared "range + gutter" highlight over the
     * comment's **live** marker range — the same source as [currentPositions] — replacing any current
     * one; on hover-out, dispose it. View-only: it never touches the store.
     */
    internal fun onCardHover(id: CommentId, hovered: Boolean) {
        if (hovered) showHoverHighlight(id) else clearHoverHighlightFor(id)
    }

    private fun showHoverHighlight(id: CommentId) {
        val marker = markers[id]?.takeIf { it.isValid } ?: return
        clearHoverHighlight()
        val startLine = document.getLineNumber(marker.startOffset)
        val endLine = document.getLineNumber(marker.endOffset)
        hoverHighlight = RangeHighlight.create(editor, startLine, endLine, CommentDraft.RANGE_BACKGROUND)
        hoverHighlightId = id
    }

    /** Clears the transient highlight only if it belongs to [id], so a stale exit can't drop a newer hover. */
    private fun clearHoverHighlightFor(id: CommentId) {
        if (hoverHighlightId == id) clearHoverHighlight()
    }

    private fun clearHoverHighlight() {
        hoverHighlight?.dispose()
        hoverHighlight = null
        hoverHighlightId = null
    }

    /** The comment whose range is currently highlighted on card hover, if any — a test seam for D4. */
    internal val hoverHighlightCommentId: CommentId? get() = hoverHighlightId

    /** The comments that currently have a read-only card in this editor — a test seam for reconcile. */
    internal val cardCommentIds: Set<CommentId> get() = cards.keys.toSet()

    override fun dispose() {
        // MessageBus connection was parented to `this` and disconnects with it.
        for (highlighter in markers.values) if (highlighter.isValid) markup.removeHighlighter(highlighter)
        markers.clear()
        markerSubjects.clear()
        for (inlay in cards.values) if (inlay.isValid) Disposer.dispose(inlay)
        cards.clear()
        cardModels.clear()
        clearHoverHighlight()
    }

    companion object {
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
