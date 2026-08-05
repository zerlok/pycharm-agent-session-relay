package io.github.zerlok.agentsessionrelay.domain

/**
 * What a [ReviewComment] is about — an open (sealed) type so a comment is not limited to a single
 * line range (ARCHITECTURE §3). Line-anchored subjects carry a file `url` and 0-based line numbers
 * (editor convention); [Files] and [Project] need no line anchor.
 *
 * The MVP authors only [Line] / [LineRange]; the others are modeled so later scopes are additive,
 * never a model rewrite.
 */
sealed interface Subject {

    /** A single line of a file. */
    data class Line(val fileUrl: String, val line: Int) : Subject

    /** An inclusive line range of a file. */
    data class LineRange(val fileUrl: String, val startLine: Int, val endLine: Int) : Subject

    /** A whole file, no line range. */
    data class File(val fileUrl: String) : Subject

    /** Several files at once. */
    data class Files(val fileUrls: List<String>) : Subject

    /** The whole review — general feedback detached from any file. */
    data object Project : Subject
}

/**
 * Pure projections of a [Subject]: which file it names, which lines it names, and whether those
 * lines exist in a file of a given length.
 *
 * They live in the domain because more than one layer asks them and the answers must be the same
 * answer. In particular [fitsIn] is the single rule behind **every** [CommentStatus.ORPHANED]
 * verdict — the presentation layer's, decided against an open document, and the delivery layer's,
 * decided against a file's content at export. Two copies of that rule would be two definitions of
 * "these lines no longer exist".
 */
object Subjects {

    /** The file a subject is anchored to, or null for the subjects that name no single file. */
    fun fileUrlOf(subject: Subject): String? = when (subject) {
        is Subject.Line -> subject.fileUrl
        is Subject.LineRange -> subject.fileUrl
        is Subject.File -> subject.fileUrl
        is Subject.Files -> null
        Subject.Project -> null
    }

    /** The inclusive 0-based line range a subject names, or null when it is not line-anchored. */
    fun linesOf(subject: Subject): Pair<Int, Int>? = when (subject) {
        is Subject.Line -> subject.line to subject.line
        is Subject.LineRange -> subject.startLine to subject.endLine
        is Subject.File, is Subject.Files, Subject.Project -> null
    }

    /**
     * Whether [subject]'s recorded range exists in a file of [lineCount] lines. A subject with no
     * line range does not "fit" anything — it has no range to place.
     */
    fun fitsIn(subject: Subject, lineCount: Int): Boolean {
        val (startLine, endLine) = linesOf(subject) ?: return false
        return startLine >= 0 && endLine >= startLine && endLine <= lineCount - 1
    }
}
