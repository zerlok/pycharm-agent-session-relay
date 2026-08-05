package io.github.zerlok.agentsessionrelay.export

import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject

/**
 * The Exporter stage of the relay pipeline (ARCHITECTURE §3): a **pure**, I/O-free function that
 * serializes a review batch to Claude-readable markdown using Claude Code's native `@path#L`
 * reference syntax. No platform imports, so it is trivially unit-testable without an editor, VFS, or
 * filesystem (design D5).
 *
 * **Purity / position contract (ARCHITECTURE §3.2, design D3).** This function is pure over the
 * *batch it is handed*: it renders each comment's stored [Subject] as-is and does **not** read from
 * any live `RangeMarker`. Reflecting in-IDE edits made after authoring is the caller's job — the
 * `review-delivery` stage flushes current positions into the store (via
 * `ReviewBatchService.updatePosition`) **before** calling [export], so the subjects passed here are
 * already the current ones.
 *
 * Only the line/range scope is authored in the MVP, so only [Subject.Line] / [Subject.LineRange] are
 * rendered; other subject kinds ([Subject.File], [Subject.Files], [Subject.Project]) are skipped.
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
     * A single line renders as `@<relpath>#L<n>`; a range as `@<relpath>#L<start>-<end>` (design
     * D-fmt). Line numbers are 1-based here (the domain is 0-based, so `+1`). Paths are made
     * project-relative to [projectBasePath] with forward slashes, so `@path#L` resolves regardless
     * of host OS (design D-order risks). Blocks are ordered by relative path, then start line
     * (design D-order), and separated by a blank line.
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
            // Not authored in the MVP — skip rather than emit a partial reference.
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
     * The stale flag (design D7), read from the comment's stored [CommentStatus] alone — the exporter
     * does no I/O and reads no marker, so "is this anchor still good?" is decided at the export sync
     * point and merely *rendered* here.
     *
     * It is appended **after** the reference token and followed by an unquoted note line, so:
     * the `@path#L…` form stays byte-identical to the unflagged one and still resolves for anything
     * matching the reference syntax; the note cannot be mistaken for user text (bodies are always
     * `> `-quoted, so a body can never forge a note either); and an `ACTIVE` comment renders exactly as
     * it did before flagging existed, which is what keeps a no-drift batch byte-identical.
     */
    private fun flagOf(status: CommentStatus): String =
        if (status != CommentStatus.STALE) "" else "  $STALE_FLAG\n$STALE_NOTE"

    private const val STALE_FLAG = "⚠️ unverified anchor"

    private const val STALE_NOTE =
        "The code at these lines changed after this comment was written — " +
            "locate the intended code before acting on the line numbers."

    private class Ref(val fileUrl: String, val startLine: Int, val endLine: Int)

    /**
     * Converts a VFS file url (`file:///abs/path`) to a [projectBasePath]-relative path with forward
     * slashes. Falls back to the plain (normalized) path if it is not under the base — an edge case
     * outside the single-root MVP.
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
