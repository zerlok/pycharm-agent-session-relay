package io.github.zerlok.agentsessionrelay.domain

/** Stable identity of a [ReviewComment], independent of its position in a file. */
@JvmInline
value class CommentId(val value: String)

/**
 * Where a comment stands relative to the code it anchors to (ARCHITECTURE.md — "Positions and
 * anchoring"). Every comment is born [ACTIVE]; the other two are assigned at the points where the
 * anchor can actually be checked, and both transition back to [ACTIVE] once it is good again.
 */
enum class CommentStatus {
    /**
     * The comment's anchor is either verified to still match, or could not be checked at all — it
     * carries no [ReviewComment.anchorText], or its file cannot be resolved or read. (A file merely
     * being closed is *not* one of those cases: verification reads content, not markers.) Exported
     * unflagged.
     */
    ACTIVE,

    /**
     * The text *under* the comment changed after it was written, so its line range is no longer
     * known to be correct. The comment is still deliverable and is exported with a flag — never
     * silently dropped and never silently moved.
     */
    STALE,

    /**
     * The comment's recorded range does not exist in the file's current content at all. It keeps
     * its recorded position — a display-time clamp never becomes the anchor — stays listed in the
     * tool window, renders no editor marker or card, and is exported with its own flag.
     */
    ORPHANED,
}

/**
 * One review comment — a [body] linked to a [Subject]. Pure, serializable data: the store holds
 * only this, never live platform objects. [anchorText] + [contextHash] are the anchoring seeds;
 * they are null for subjects without a line anchor ([Subject.Files], [Subject.Project]).
 */
data class ReviewComment(
    val id: CommentId,
    val subject: Subject,
    val body: String,
    val status: CommentStatus = CommentStatus.ACTIVE,
    val anchorText: String? = null,
    val contextHash: String? = null,
)
