package io.github.zerlok.agentsessionrelay.delivery

import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [ReviewDelivery] decision (empty-vs-nonempty + the exact written string).
 * No editor/VFS/filesystem needed — it is a pure function of the batch (design D5).
 */
class ReviewDeliveryTest {

    private val basePath = "/home/dev/project"

    private fun comment(subject: Subject, body: String, id: String = "id") =
        ReviewComment(id = CommentId(id), subject = subject, body = body)

    private fun url(relPath: String) = "file://$basePath/$relPath"

    @Test
    fun `an empty batch plans nothing to submit`() {
        assertEquals(ReviewDelivery.Plan.NothingToSubmit, ReviewDelivery.plan(emptyList(), basePath))
    }

    @Test
    fun `a batch of only non-authored subjects plans nothing to submit`() {
        val batch = listOf(comment(Subject.File(url("src/app.py")), "whole file"), comment(Subject.Project, "general"))

        assertEquals(ReviewDelivery.Plan.NothingToSubmit, ReviewDelivery.plan(batch, basePath))
    }

    @Test
    fun `a non-empty batch plans the exact review contents`() {
        val plan = ReviewDelivery.plan(listOf(comment(Subject.LineRange(url("src/app.py"), 9, 14), "extract this")), basePath)

        assertEquals(
            ReviewDelivery.Plan.WriteReview("# Code review\n\n@src/app.py#L10-15\n> extract this\n"),
            plan,
        )
    }

    @Test
    fun `written contents end with a trailing newline`() {
        val plan = ReviewDelivery.plan(listOf(comment(Subject.Line(url("a.py"), 0), "x")), basePath)

        assertTrue((plan as ReviewDelivery.Plan.WriteReview).content.endsWith("\n"))
    }
}
