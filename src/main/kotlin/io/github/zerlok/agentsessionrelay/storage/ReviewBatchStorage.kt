package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment

/**
 * Dumb CRUD over [ReviewComment] records — the storage layer (ARCHITECTURE §3.1). It holds no
 * events and no policy; the logic layer mediates every read/write above it. *How* records are held
 * is hidden behind this interface so the persistence swap (in-memory → `PersistentStateComponent`)
 * touches storage alone.
 */
interface ReviewBatchStorage {
    /** Comments in insertion order. */
    fun all(): List<ReviewComment>

    fun get(id: CommentId): ReviewComment?

    fun add(comment: ReviewComment)

    /** Replaces the record with the same id in place (keeping order); no-op if it isn't present. */
    fun update(comment: ReviewComment)

    /** Removes and returns the comment, or null if it wasn't present. */
    fun remove(id: CommentId): ReviewComment?

    fun clear()
}

/** In-memory batch store — the MVP backing (persistence is deferred, ARCHITECTURE §5.4). */
class InMemoryReviewBatchStorage : ReviewBatchStorage {

    // LinkedHashMap keeps insertion order so surfaces list comments as they were authored.
    private val comments = LinkedHashMap<CommentId, ReviewComment>()

    override fun all(): List<ReviewComment> = comments.values.toList()

    override fun get(id: CommentId): ReviewComment? = comments[id]

    override fun add(comment: ReviewComment) {
        comments[comment.id] = comment
    }

    // A LinkedHashMap put on an existing key replaces the value in place, preserving insertion order.
    override fun update(comment: ReviewComment) {
        if (comments.containsKey(comment.id)) comments[comment.id] = comment
    }

    override fun remove(id: CommentId): ReviewComment? = comments.remove(id)

    override fun clear() = comments.clear()
}
