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
