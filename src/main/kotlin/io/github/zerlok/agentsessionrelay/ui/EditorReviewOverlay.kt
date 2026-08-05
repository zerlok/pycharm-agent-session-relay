package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

/**
 * The per-editor view of the review batch (ARCHITECTURE §3.2, §3.3). It owns what genuinely belongs
 * to *one editor*: the read-only **card inlays** (an `Inlay` lives on the editor that shows it), the
 * card-hover range highlight, and the edit suppression. A comment's position marker is **not** one of
 * those — the markup it rides is document-scoped, so it is owned once per document by
 * [DocumentReviewMarkers], which this overlay reads from for every live-position question.
 *
 * The store is the single source of truth: the overlay never keeps its own comment list. It seeds
 * from [ReviewBatchService.comments] on creation and, on every [ReviewBatchListener] event,
 * reconciles its cards **by diff** (retained-mode: add new, dispose removed, rebuild changed).
 *
 * A card is a full-width block inlay under a stored comment's range showing its body plus Edit and
 * Delete. Cards reconcile off the store events (rebuilding on `commentUpdated`, since a card's
 * body/position are baked in at creation) and additionally off [CommentEditingListener] — the comment
 * currently open in an edit box is skipped so its card and box never overlap (design D3). A comment
 * whose recorded range does not fit this document gets no card at all, matching the marker it also
 * does not get (design D4).
 *
 * Lifecycle is owned by [EditorReviewOverlayService], which parents this [Disposable] to the project
 * service and disposes it in `editorReleased`.
 */
class EditorReviewOverlay(
    private val project: Project,
    private val editor: Editor,
    private val markers: DocumentReviewMarkers,
) : ReviewBatchListener, CommentEditingListener, Disposable {

    // Internal so [EditorReviewOverlayService] can ref-count overlays against the document whose
    // markers they read (identity compare).
    internal val document: Document = editor.document
    private val fileUrl: String? = FileDocumentManager.getInstance().getFile(document)?.url

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
        reconcileCards()
    }

    // -- Store events: reconcile by diff (works uniformly for add / remove / update / clear). --

    override fun commentAdded(comment: ReviewComment) = reconcileCards()

    override fun commentRemoved(comment: ReviewComment) = reconcileCards()

    override fun commentUpdated(comment: ReviewComment) = reconcileCards()

    override fun batchCleared() = reconcileCards()

    // -- Editing state changed: a card must appear/disappear for the (un)edited comment. --

    override fun editingChanged() = reconcileCards()

    /**
     * Reconcile the read-only cards (design D2/D3). A card is wanted for every stored comment on this
     * file **except** the one currently open in an edit box, and except one whose recorded range does
     * not exist in this document (design D4 — an orphaned comment has no range to point at, so it
     * renders neither card nor gutter bar and lives in the tool window only). Because a card bakes in
     * its body and offset, a wanted comment whose record differs from what its card was built from is
     * rebuilt (dispose + add), not left in place — this is how a `commentUpdated` (body or position)
     * refreshes the card.
     */
    private fun reconcileCards() {
        val url = fileUrl ?: return
        val editingId = CommentDraftController.getInstance(project).editingCommentId
        val wanted = ReviewBatchService.getInstance(project).comments()
            .filter { DocumentReviewMarkers.fileUrlOf(it.subject) == url && it.id != editingId && markers.fits(it.subject) }
            .associateBy { it.id }

        // Dispose cards whose comment is gone, is now being edited, or no longer fits the document.
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

    /**
     * Places [comment]'s read-only card as a full-width block inlay under its range's bottom line —
     * the same [EditorEmbeddedComponentManager] placement the authoring box uses, so card and box
     * anchor identically. Card **Edit** re-opens the box seeded (via [CommentDraftController], passing
     * the comment at its *live* range so the box opens where the marker actually is); **Delete** routes
     * through the store so every surface reconciles off the resulting event.
     *
     * Only ever called for a comment whose range fits the document ([DocumentReviewMarkers.fits] gates
     * the reconcile above), so the line offsets below need no clamp.
     */
    private fun addCard(comment: ReviewComment) {
        val editorEx = editor as? EditorEx ?: return
        val (_, endLine) = DocumentReviewMarkers.linesOf(comment.subject) ?: return

        val panel = StoredCommentCard.build(
            editorEx,
            comment.body,
            onEdit = {
                // Open over the marker's CURRENT range (which tracks in-IDE edits), not the possibly
                // stale stored subject, so the edit box lines up with the card the user clicked.
                val live = markers.currentPositions()[comment.id]
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
            /* offset = */ document.getLineEndOffset(endLine),
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
     * comment's **live** marker range — the same source the sync points flush — replacing any current
     * one; on hover-out, dispose it. View-only: it never touches the store.
     */
    internal fun onCardHover(id: CommentId, hovered: Boolean) {
        if (hovered) showHoverHighlight(id) else clearHoverHighlightFor(id)
    }

    private fun showHoverHighlight(id: CommentId) {
        val live = markers.currentPositions()[id] ?: return
        val (startLine, endLine) = DocumentReviewMarkers.linesOf(live) ?: return
        clearHoverHighlight()
        hoverHighlight = RangeHighlight.create(editor, startLine, endLine, RelayStyle.RANGE_WASH)
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
        for (inlay in cards.values) if (inlay.isValid) Disposer.dispose(inlay)
        cards.clear()
        cardModels.clear()
        clearHoverHighlight()
    }
}
