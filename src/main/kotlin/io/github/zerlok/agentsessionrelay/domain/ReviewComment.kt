package io.github.zerlok.agentsessionrelay.domain

/** Stable identity of a [ReviewComment], independent of its position in a file. */
@JvmInline
value class CommentId(val value: String)

/**
 * Where a comment stands relative to the code it anchors to (ARCHITECTURE §5.2, §3.3). Every comment
 * is born [ACTIVE]; the other two are assigned by the presentation layer at the points where the
 * anchor can actually be checked, and both transition back to [ACTIVE] when the anchor is good again.
 */
enum class CommentStatus {
    /**
     * The comment's anchor is either verified to still match, or has not been checked (its file is not
     * open, or it carries no [ReviewComment.anchorText] to check against). Exported unflagged.
     */
    ACTIVE,

    /**
     * The text *under* the comment changed after it was written, so its line range is no longer known
     * to be correct. Detected at the export sync point by comparing [ReviewComment.anchorText] against
     * the text the live marker spans. The comment is still deliverable and is exported with a flag —
     * never silently dropped and never silently moved.
     */
    STALE,

    /**
     * The comment's recorded range does not exist in the current document at all. It keeps its recorded
     * position (a display-time clamp never becomes the anchor), stays listed in the tool window, and
     * renders no editor marker or card.
     */
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
