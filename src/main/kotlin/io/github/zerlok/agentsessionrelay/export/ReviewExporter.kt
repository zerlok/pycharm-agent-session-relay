package io.github.zerlok.agentsessionrelay.export

import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject

/**
 * The Exporter stage: a **pure**, I/O-free function that serializes a review batch to
 * Claude-readable markdown using Claude Code's native `@path#L` reference syntax. No platform
 * imports, so it is testable without an editor, VFS, or filesystem.
 *
 * **Position contract.** This is pure over the *batch it is handed*: it renders each comment's
 * stored [Subject] as-is and never reads a live `RangeMarker`. Reflecting in-IDE edits made after
 * authoring is the caller's job — [ReviewDeliveryService][io.github.zerlok.agentsessionrelay.delivery.ReviewDeliveryService]
 * flushes current positions into the store **before** calling [export].
 *
 * Only line/range subjects are authored, so only those are rendered; [Subject.File],
 * [Subject.Files] and [Subject.Project] are skipped.
 */
object ReviewExporter {

    /**
     * Serializes [comments] to Claude-format markdown.
     *
     * Each renderable comment becomes a block of a reference line followed by its body:
     * ```
     * @src/app.py#L42
     * > fix the typo
     * ```
     * A single line renders as `@<relpath>#L<n>`; a range as `@<relpath>#L<start>-<end>`. Line
     * numbers are 1-based here (the domain is 0-based, so `+1`). Paths are made project-relative to
     * [projectBasePath] with forward slashes, so `@path#L` resolves regardless of host OS. Blocks
     * are ordered by relative path, then start line, and separated by a blank line.
     *
     * Bodies are free text, so each body line is quoted with a leading `> ` to neutralize it: a
     * body's own `@…` reference or a stray ``` code fence stays inside its blockquote and can neither
     * merge into the reference line above it nor bleed into the next comment's block.
     *
     * **Empty result.** If [comments] is empty — or contains no renderable (line/range) subject —
     * this returns the **empty string** `""`. That is the well-defined "nothing to submit" sentinel
     * the delivery stage checks (`export(...).isEmpty()`); a non-empty result always ends with a
     * trailing newline.
     */
    fun export(comments: List<ReviewComment>, projectBasePath: String): String {
        val blocks = comments
            .mapNotNull { comment -> render(comment, projectBasePath) }
            .sortedWith(compareBy({ it.path }, { it.startLine }))

        if (blocks.isEmpty()) return ""

        return blocks.joinToString(separator = "\n\n") { it.text } + "\n"
    }

    /** A rendered comment block plus the sort keys (relative path, then start line) it orders by. */
    private class Block(val path: String, val startLine: Int, val text: String)

    private fun render(comment: ReviewComment, projectBasePath: String): Block? {
        val ref = when (val subject = comment.subject) {
            is Subject.Line -> Ref(subject.fileUrl, subject.line, subject.line)
            is Subject.LineRange -> Ref(subject.fileUrl, subject.startLine, subject.endLine)
            // Not authored today — skip rather than emit a partial reference.
            is Subject.File, is Subject.Files, Subject.Project -> return null
        }

        val path = toRelativePath(ref.fileUrl, projectBasePath)
        // Domain lines are 0-based (editor convention); references are 1-based.
        val start = ref.startLine + 1
        val end = ref.endLine + 1
        val anchor = if (start == end) "@$path#L$start" else "@$path#L$start-$end"

        return Block(path, ref.startLine, anchor + flagOf(comment.status) + "\n" + quoteBody(comment.body))
    }

    /**
     * The untrustworthy-anchor flag, read from the comment's stored [CommentStatus] alone — the
     * exporter does no I/O and reads no marker, so "is this anchor still good?" is decided at the
     * export sync point and merely *rendered* here.
     *
     * The two non-[CommentStatus.ACTIVE] statuses get **distinguishable** markers, because the
     * agent's correct next move differs: a `STALE` comment's code may have moved or been edited and
     * is worth locating, while an `ORPHANED` comment's lines are gone and the feedback may be
     * obsolete. An `ORPHANED` reference keeps its last-known line numbers — they no longer resolve,
     * but they are the best clue to what the comment was about, and the marker already says not to
     * trust them.
     *
     * Each is a single short marker appended **after** the reference token, with no explanatory
     * sentence: it is a label, not documentation, and it appears once per affected comment in a file
     * the agent parses, so a repeated sentence would dilute the signal. The `@path#L…` token stays
     * byte-identical to the unflagged form and still resolves.
     */
    private fun flagOf(status: CommentStatus): String = when (status) {
        CommentStatus.ACTIVE -> ""
        CommentStatus.STALE -> "  $STALE_FLAG"
        CommentStatus.ORPHANED -> "  $ORPHANED_FLAG"
    }

    private const val STALE_FLAG = "⚠️ unverified anchor"

    private const val ORPHANED_FLAG = "⚠️ anchor deleted"

    private class Ref(val fileUrl: String, val startLine: Int, val endLine: Int)

    /**
     * Converts a VFS file url (`file:///abs/path`) to a [projectBasePath]-relative path with forward
     * slashes. Falls back to the plain (normalized) path if it is not under the base — an edge case
     * outside the single-root scope.
     */
    private fun toRelativePath(fileUrl: String, projectBasePath: String): String {
        val path = fileUrl.substringAfter("://", fileUrl).replace('\\', '/')
        val base = projectBasePath.replace('\\', '/').trimEnd('/')
        val prefix = "$base/"
        return if (base.isNotEmpty() && path.startsWith(prefix)) path.removePrefix(prefix) else path
    }

    /** Quotes every body line with `> `, so the body cannot break the surrounding markdown structure. */
    private fun quoteBody(body: String): String =
        body.trimEnd('\n').lines().joinToString("\n") { "> $it" }
}
