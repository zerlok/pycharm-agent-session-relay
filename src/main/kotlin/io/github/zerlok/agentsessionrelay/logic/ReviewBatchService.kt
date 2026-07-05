package io.github.zerlok.agentsessionrelay.logic

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.storage.InMemoryReviewBatchStorage
import io.github.zerlok.agentsessionrelay.storage.ReviewBatchStorage
import java.util.UUID

/**
 * The logic layer (ARCHITECTURE §3.1): the **only** API the presentation layer sees. It mediates
 * every read/write to [ReviewBatchStorage] and dispatches change events on [ReviewBatchListener].
 * No Swing, no editor imports. In-memory storage now; swappable for a persistent backing behind the
 * same interface without the view or this API changing.
 *
 * Commands and event dispatch run on the EDT (ARCHITECTURE §5.3): they mutate Relay's own state and
 * drive UI-affecting listeners, so callers invoke them from the EDT.
 */
@Service(Service.Level.PROJECT)
class ReviewBatchService(private val project: Project) {

    // The one place the concrete backing is named; swapping it is the whole persistence change.
    private val storage: ReviewBatchStorage = InMemoryReviewBatchStorage()

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
     * The position-sync seam (ARCHITECTURE §3.2): replaces a stored comment's [subject] with the
     * position the view read from the live marker, then publishes the change. This is the flush a
     * later stage (review-delivery) runs at submit/export time so the exported line range is the
     * *current* one — not a per-keystroke write. No-op if the id is unknown.
     */
    fun updatePosition(id: CommentId, subject: Subject) {
        val existing = storage.get(id) ?: return
        if (existing.subject == subject) return
        val updated = existing.copy(subject = subject)
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
