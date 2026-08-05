package io.github.zerlok.agentsessionrelay.export

import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [ReviewExporter] (tasks 2.1). No editor/VFS/filesystem needed — the
 * exporter is a pure function of the batch (design D5).
 */
class ReviewExporterTest {

    private val basePath = "/home/dev/project"

    private fun comment(subject: Subject, body: String, id: String = "id") =
        ReviewComment(id = CommentId(id), subject = subject, body = body)

    private fun url(relPath: String) = "file://$basePath/$relPath"

    @Test
    fun `single-line comment renders the pinned @path#L form`() {
        // Domain is 0-based; line 41 -> reference L42 (design D-fmt).
        val out = ReviewExporter.export(listOf(comment(Subject.Line(url("src/app.py"), 41), "fix it")), basePath)

        assertEquals("@src/app.py#L42\n> fix it\n", out)
    }

    @Test
    fun `range comment renders @path#Lstart-end`() {
        val out = ReviewExporter.export(listOf(comment(Subject.LineRange(url("src/app.py"), 9, 14), "extract this")), basePath)

        assertEquals("@src/app.py#L10-15\n> extract this\n", out)
    }

    @Test
    fun `a single-element range collapses to the single-line form`() {
        val out = ReviewExporter.export(listOf(comment(Subject.LineRange(url("src/app.py"), 41, 41), "x")), basePath)

        assertTrue(out.startsWith("@src/app.py#L42\n"))
    }

    @Test
    fun `multiple files are ordered by path then start line`() {
        val batch = listOf(
            comment(Subject.Line(url("src/util.py"), 4), "b", id = "1"),
            comment(Subject.Line(url("src/app.py"), 20), "c", id = "2"),
            comment(Subject.Line(url("src/app.py"), 3), "a", id = "3"),
        )

        val out = ReviewExporter.export(batch, basePath)

        val order = listOf("@src/app.py#L4", "@src/app.py#L21", "@src/util.py#L5")
        val positions = order.map { out.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun `empty batch yields the empty-string sentinel`() {
        assertEquals("", ReviewExporter.export(emptyList(), basePath))
    }

    @Test
    fun `a batch of only non-authored subjects yields the empty sentinel`() {
        val batch = listOf(comment(Subject.File(url("src/app.py")), "whole file"), comment(Subject.Project, "general"))

        assertEquals("", ReviewExporter.export(batch, basePath))
    }

    // -- The stale flag (trustworthy-comment-anchors, design D7) --

    private fun stale(subject: Subject, body: String, id: String = "id") =
        comment(subject, body, id).copy(status = CommentStatus.STALE)

    /**
     * A `STALE` comment is exported — never silently dropped — with the drift stated where the agent
     * reads the reference. The reference token itself is byte-identical to the unflagged form and stays
     * first on its line, so anything matching the `@path#L` syntax still resolves it; the note is
     * unquoted, so it can never be confused with the user's own (always `> `-quoted) text.
     */
    @Test
    fun `a stale comment keeps its reference and gains a flag and a note`() {
        val out = ReviewExporter.export(
            listOf(stale(Subject.LineRange(url("src/app.py"), 39, 41), "the retry loop needs a backoff")),
            basePath,
        )

        assertEquals(
            "@src/app.py#L40-42  ⚠️ unverified anchor\n" +
                "The code at these lines changed after this comment was written — " +
                "locate the intended code before acting on the line numbers.\n" +
                "> the retry loop needs a backoff\n",
            out,
        )
        val lines = out.trimEnd('\n').lines()
        assertTrue("the reference token is unchanged and leads the line", lines[0].startsWith("@src/app.py#L40-42"))
        assertFalse("the note is not quoted, so a body can never forge one", lines[1].startsWith(">"))
    }

    /**
     * The regression net for the whole exporter: with nothing drifted, the output is what it has always
     * been — the expected strings above are asserted here unchanged, byte for byte.
     */
    @Test
    fun `an all-active batch is byte-identical to the unflagged export`() {
        val single = listOf(comment(Subject.Line(url("src/app.py"), 41), "fix it"))
        val range = listOf(comment(Subject.LineRange(url("src/app.py"), 9, 14), "extract this"))

        assertEquals("@src/app.py#L42\n> fix it\n", ReviewExporter.export(single, basePath))
        assertEquals("@src/app.py#L10-15\n> extract this\n", ReviewExporter.export(range, basePath))
    }

    /** Status is a rendering concern only: it never reorders the file, which stays path-then-line. */
    @Test
    fun `a mixed batch is still ordered by path then start line`() {
        val batch = listOf(
            stale(Subject.Line(url("src/util.py"), 4), "b", id = "1"),
            comment(Subject.Line(url("src/app.py"), 20), "c", id = "2"),
            stale(Subject.Line(url("src/app.py"), 3), "a", id = "3"),
        )

        val out = ReviewExporter.export(batch, basePath)

        val order = listOf("@src/app.py#L4", "@src/app.py#L21", "@src/util.py#L5")
        val positions = order.map { out.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    /** An `ORPHANED` comment is still the user's feedback: it exports at its recorded, unflagged range. */
    @Test
    fun `an orphaned comment exports at its recorded range`() {
        val batch = listOf(comment(Subject.LineRange(url("src/app.py"), 299, 300), "past the end").copy(status = CommentStatus.ORPHANED))

        assertEquals("@src/app.py#L300-301\n> past the end\n", ReviewExporter.export(batch, basePath))
    }

    @Test
    fun `a body with markdown-significant characters cannot break the ref line or bleed`() {
        val body = "@other/file#L1\n```\nrm -rf /\n```"
        val batch = listOf(
            comment(Subject.Line(url("a.py"), 0), body, id = "1"),
            comment(Subject.Line(url("b.py"), 0), "next", id = "2"),
        )

        val out = ReviewExporter.export(batch, basePath)

        // The a.py reference is on its own line; the body's leading '@' does not merge into it.
        assertTrue(out.contains("@a.py#L1\n> @other/file#L1\n"))
        // Every body line is quoted, so a stray fence cannot swallow the following comment.
        assertTrue(out.contains("> ```"))
        assertTrue(out.contains("@b.py#L1\n> next"))
    }
}
