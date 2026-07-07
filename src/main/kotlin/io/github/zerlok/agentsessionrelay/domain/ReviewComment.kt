package io.github.zerlok.agentsessionrelay.domain

/** Stable identity of a [ReviewComment], independent of its position in a file. */
@JvmInline
value class CommentId(val value: String)

/**
 * Where a comment stands relative to the code it anchors to.
 *
 * The MVP only ever sets [ACTIVE]; [STALE] and [ORPHANED] are the anchor-drift states a later
 * re-anchoring change will set (ARCHITECTURE §5.2, §3.3).
 */
enum class CommentStatus {
    /** Anchor resolves cleanly; the comment points where it was authored. */
    ACTIVE,

    /** The anchor moved ambiguously (e.g. an out-of-IDE edit); surfaced to the human, never mis-pointed. */
    STALE,

    /** The anchored line was deleted; the comment survives in the batch but has no live position. */
    ORPHANED,
}

/**
 * One review comment — a [body] linked to a [Subject] (ARCHITECTURE §3). Pure, serializable data:
 * the store holds only this, never live platform objects. [anchorText] + [contextHash] are the
 * re-anchoring seeds (unused by the MVP beyond capture); they are null for subjects without a line
 * anchor ([Subject.Files], [Subject.Project]).
 */
data class ReviewComment(
    val id: CommentId,
    val subject: Subject,
    val body: String,
    val status: CommentStatus = CommentStatus.ACTIVE,
    val anchorText: String? = null,
    val contextHash: String? = null,
)
