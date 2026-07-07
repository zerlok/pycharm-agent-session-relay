package io.github.zerlok.agentsessionrelay.logic

import com.intellij.util.messages.Topic
import io.github.zerlok.agentsessionrelay.domain.ReviewComment

/**
 * The seam between logic and presentation (ARCHITECTURE §3.1): a `MessageBus` topic the view
 * subscribes to. The view never holds a storage handle — it reacts to these events and re-queries
 * [ReviewBatchService]. Callbacks fire on the EDT (ARCHITECTURE §5.3).
 *
 * Callbacks have default no-op bodies so a subscriber implements only what it cares about.
 */
interface ReviewBatchListener {

    fun commentAdded(comment: ReviewComment) {}

    fun commentRemoved(comment: ReviewComment) {}

    /**
     * A stored comment's data changed in place (same id) — e.g. its subject was re-synced to the
     * live marker's current line range at a sync point (ARCHITECTURE §3.2). Surfaces reconcile by
     * diff and refresh what they show for this id; the live marker stays the source of truth.
     */
    fun commentUpdated(comment: ReviewComment) {}

    fun batchCleared() {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<ReviewBatchListener> =
            Topic.create("Relay review batch", ReviewBatchListener::class.java)
    }
}
