package io.github.zerlok.agentsessionrelay.logic

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.storage.PersistentReviewBatchStorage
import io.github.zerlok.agentsessionrelay.storage.ReviewBatchStorage
import java.util.UUID

/**
 * The application layer (ARCHITECTURE.md — "Layers"): the **only** API the presentation layer
 * sees. It mediates every read/write to [ReviewBatchStorage] and dispatches change events on
 * [ReviewBatchListener]. No Swing, no editor imports.
 *
 * Commands and event dispatch run on the EDT: they mutate Relay's own state and drive UI-affecting
 * listeners, so callers invoke them from the EDT.
 */
@Service(Service.Level.PROJECT)
class ReviewBatchService(private val project: Project) {

    // The one place the concrete backing is named. Obtained as a service, not constructed: see
    // PersistentReviewBatchStorage for why the platform must own its lifecycle.
    private val storage: ReviewBatchStorage = project.service<PersistentReviewBatchStorage>()

    // -- Queries --

    fun comments(): List<ReviewComment> = storage.all()

    // -- Commands --

    fun addComment(
        subject: Subject,
        body: String,
        anchorText: String? = null,
        contextHash: String? = null,
    ): ReviewComment {
        val comment = ReviewComment(
            id = CommentId(UUID.randomUUID().toString()),
            subject = subject,
            body = body,
            anchorText = anchorText,
            contextHash = contextHash,
        )
        storage.add(comment)
        publisher().commentAdded(comment)
        return comment
    }

    /**
     * The position-sync seam (ARCHITECTURE.md — "Positions and anchoring"): replaces a stored
     * comment's [subject] with the position the view read from the live marker, then publishes the
     * change. Run at the sync points, so the exported line range is the *current* one without a
     * per-keystroke write. No-op if the id is unknown.
     */
    fun updatePosition(id: CommentId, subject: Subject) {
        val existing = storage.get(id) ?: return
        if (existing.subject == subject) return
        val updated = existing.copy(subject = subject)
        storage.update(updated)
        publisher().commentUpdated(updated)
    }

    /**
     * The body-edit seam, mirroring [updatePosition]: replaces a stored comment's [body] in place,
     * preserving its id, subject, and all anchoring data, then publishes the change. An edit
     * resubmit calls this alongside [updatePosition]; both drive every surface's reconcile off the
     * same `commentUpdated` event — never delete-and-re-add. No-op if the id is unknown or the body
     * is unchanged.
     */
    fun updateBody(id: CommentId, body: String) {
        val existing = storage.get(id) ?: return
        if (existing.body == body) return
        val updated = existing.copy(body = body)
        storage.update(updated)
        publisher().commentUpdated(updated)
    }

    /**
     * The anchor-status seam, mirroring [updatePosition] / [updateBody]: replaces a stored comment's
     * [CommentStatus] in place, preserving its id, subject, body, and anchoring data, then publishes
     * the change. Set by the presentation layer when a comment's recorded range stops fitting its
     * open document ([CommentStatus.ORPHANED]) and by the delivery layer at the export sync point,
     * which reaches both verdicts from the file's content.
     *
     * No-op — with **no event** — if the id is unknown or the status is unchanged. That idempotence is
     * load-bearing, not just an optimization: the view sets a status from inside its own reconcile, so
     * the resulting `commentUpdated` re-enters that reconcile, and it is this no-op that makes the
     * second pass find nothing to do and the cascade terminate.
     */
    fun updateStatus(id: CommentId, status: CommentStatus) {
        val existing = storage.get(id) ?: return
        if (existing.status == status) return
        val updated = existing.copy(status = status)
        storage.update(updated)
        publisher().commentUpdated(updated)
    }

    fun removeComment(id: CommentId) {
        val removed = storage.remove(id) ?: return
        publisher().commentRemoved(removed)
    }

    fun clear() {
        storage.clear()
        publisher().batchCleared()
    }

    private fun publisher(): ReviewBatchListener =
        project.messageBus.syncPublisher(ReviewBatchListener.TOPIC)

    companion object {
        fun getInstance(project: Project): ReviewBatchService = project.service<ReviewBatchService>()
    }
}
