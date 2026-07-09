package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the domain↔DTO mapper (tasks 1.1–1.3, 4.1, 4.3). Pure Kotlin — no platform fixture:
 * the mapper is a storage-boundary translation over inert records (ARCHITECTURE §3.1, §3.2).
 */
class PersistedCommentTest {

    /** Round-trip `ReviewComment → PersistedComment → ReviewComment` must be the identity. */
    private fun assertRoundTrips(comment: ReviewComment) {
        assertEquals(comment, comment.toPersisted().toDomain())
    }

    // -- Every Subject variant round-trips (task 4.1) --

    @Test
    fun `Line subject round-trips`() {
        assertRoundTrips(ReviewComment(CommentId("1"), Subject.Line("file:///a.py", 7), "b"))
    }

    @Test
    fun `LineRange subject round-trips`() {
        assertRoundTrips(ReviewComment(CommentId("2"), Subject.LineRange("file:///a.py", 4, 9), "b"))
    }

    @Test
    fun `File subject round-trips`() {
        assertRoundTrips(ReviewComment(CommentId("3"), Subject.File("file:///a.py"), "b"))
    }

    @Test
    fun `Files subject round-trips`() {
        assertRoundTrips(
            ReviewComment(CommentId("4"), Subject.Files(listOf("file:///a.py", "file:///b.py")), "b"),
        )
    }

    @Test
    fun `Project subject round-trips`() {
        assertRoundTrips(ReviewComment(CommentId("5"), Subject.Project, "b"))
    }

    // -- Anchoring fields, populated and empty (task 4.1) --

    @Test
    fun `populated anchoring fields round-trip`() {
        assertRoundTrips(
            ReviewComment(
                CommentId("6"), Subject.Line("file:///a.py", 0), "body",
                status = CommentStatus.STALE, anchorText = "def f()", contextHash = "deadbeef",
            ),
        )
    }

    @Test
    fun `null anchoring fields round-trip`() {
        val dto = ReviewComment(CommentId("7"), Subject.Project, "b").toPersisted()
        val back = dto.toDomain()
        assertEquals(null, back.anchorText)
        assertEquals(null, back.contextHash)
    }

    @Test
    fun `0-based line numbers are preserved on disk`() {
        val dto = ReviewComment(CommentId("8"), Subject.Line("file:///a.py", 0), "b").toPersisted()
        // 0-based domain convention, not the 1-based display form.
        assertEquals(0, dto.startLine)
    }

    @Test
    fun `every CommentStatus round-trips`() {
        for (status in CommentStatus.entries) {
            assertRoundTrips(
                ReviewComment(CommentId("s-$status"), Subject.Line("file:///a.py", 1), "b", status = status),
            )
        }
    }

    // -- Degenerate input (task 4.3) --

    @Test
    fun `unknown subjectKind maps to a safe Project fallback without throwing`() {
        val dto = PersistedComment().apply {
            id = "x"
            subjectKind = "TOTALLY_NEW_KIND"
            body = "b"
        }

        assertEquals(Subject.Project, dto.toDomain().subject)
    }

    @Test
    fun `a bare DTO with only defaults loads without throwing`() {
        // No fields set at all (blank subjectKind, empty id, default status).
        val loaded = PersistedComment().toDomain()

        assertEquals(Subject.Project, loaded.subject)
        assertEquals(CommentId(""), loaded.id)
        assertEquals(CommentStatus.ACTIVE, loaded.status)
    }

    @Test
    fun `unknown status falls back to ACTIVE`() {
        val dto = PersistedComment().apply {
            id = "x"
            subjectKind = "LINE"
            fileUrl = "file:///a.py"
            startLine = 1
            status = "NOT_A_REAL_STATUS"
        }

        assertEquals(CommentStatus.ACTIVE, dto.toDomain().status)
    }

    @Test
    fun `a LINE DTO with a missing fileUrl loads with an empty url`() {
        val dto = PersistedComment().apply {
            subjectKind = "LINE"
            startLine = 3
        }

        assertEquals(Subject.Line("", 3), dto.toDomain().subject)
    }
}
