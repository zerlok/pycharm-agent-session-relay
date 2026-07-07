package io.github.zerlok.agentsessionrelay.logic

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject

/**
 * Real-platform test of the logic layer against a live project fixture (task 2 of the test brief).
 * Exercises [ReviewBatchService] end-to-end: it mutates the real storage AND publishes on the real
 * [ReviewBatchListener] `MessageBus` topic. No mocking of the platform — a probe listener subscribes
 * via the project message bus, exactly as a real view would (ARCHITECTURE §3.1).
 */
class ReviewBatchServiceTest : BasePlatformTestCase() {

    /** Records what the service published, so we assert on the real event stream, not internals. */
    private class Probe : ReviewBatchListener {
        val added = mutableListOf<ReviewComment>()
        val removed = mutableListOf<ReviewComment>()
        val updated = mutableListOf<ReviewComment>()
        var cleared = 0

        override fun commentAdded(comment: ReviewComment) { added += comment }
        override fun commentRemoved(comment: ReviewComment) { removed += comment }
        override fun commentUpdated(comment: ReviewComment) { updated += comment }
        override fun batchCleared() { cleared++ }
    }

    private lateinit var service: ReviewBatchService
    private lateinit var probe: Probe

    override fun setUp() {
        super.setUp()
        service = ReviewBatchService.getInstance(project)
        // The light-project fixture (and its project-level service) is reused across test methods, so
        // drop any batch a prior method left behind BEFORE subscribing — the probe must see a clean slate.
        service.clear()
        probe = Probe()
        // testRootDisposable tears the subscription down with the fixture.
        project.messageBus.connect(testRootDisposable).subscribe(ReviewBatchListener.TOPIC, probe)
    }

    fun `test addComment stores the comment and publishes commentAdded`() {
        val comment = service.addComment(Subject.Line("file:///a.py", 3), "fix it")

        assertEquals(listOf(comment), service.comments())
        assertEquals(listOf(comment), probe.added)
        assertEquals("fix it", comment.body)
        assertEquals(CommentStatus.ACTIVE, comment.status)
    }

    fun `test addComment carries anchor seeds into the stored record`() {
        val comment = service.addComment(Subject.Line("file:///a.py", 0), "b", anchorText = "def f()", contextHash = "deadbeef")

        val stored = service.comments().single()
        assertEquals("def f()", stored.anchorText)
        assertEquals("deadbeef", stored.contextHash)
        assertEquals(comment, stored)
    }

    fun `test removeComment drops it and publishes commentRemoved`() {
        val comment = service.addComment(Subject.Line("file:///a.py", 1), "x")

        service.removeComment(comment.id)

        assertTrue(service.comments().isEmpty())
        assertEquals(listOf(comment), probe.removed)
    }

    fun `test removeComment of an unknown id is a silent no-op`() {
        service.addComment(Subject.Line("file:///a.py", 1), "x")

        service.removeComment(io.github.zerlok.agentsessionrelay.domain.CommentId("nope"))

        assertEquals(1, service.comments().size)
        assertTrue(probe.removed.isEmpty())
    }

    fun `test clear empties the batch and publishes batchCleared`() {
        service.addComment(Subject.Line("file:///a.py", 1), "x")
        service.addComment(Subject.Line("file:///b.py", 2), "y")

        service.clear()

        assertTrue(service.comments().isEmpty())
        assertEquals(1, probe.cleared)
    }

    fun `test updatePosition rewrites the stored subject and publishes commentUpdated`() {
        val comment = service.addComment(Subject.Line("file:///a.py", 3), "x")

        service.updatePosition(comment.id, Subject.LineRange("file:///a.py", 10, 12))

        val stored = service.comments().single()
        assertEquals(Subject.LineRange("file:///a.py", 10, 12), stored.subject)
        assertEquals(comment.id, stored.id)
        assertEquals(listOf(stored), probe.updated)
    }

    fun `test updatePosition to the same subject does not republish`() {
        val comment = service.addComment(Subject.Line("file:///a.py", 3), "x")

        service.updatePosition(comment.id, Subject.Line("file:///a.py", 3))

        assertTrue(probe.updated.isEmpty())
    }

    fun `test updatePosition on an unknown id is a silent no-op`() {
        service.updatePosition(io.github.zerlok.agentsessionrelay.domain.CommentId("nope"), Subject.Line("file:///a.py", 1))

        assertTrue(service.comments().isEmpty())
        assertTrue(probe.updated.isEmpty())
    }

    fun `test updateBody replaces the body and publishes commentUpdated preserving everything else`() {
        val comment = service.addComment(
            Subject.LineRange("file:///a.py", 4, 6), "old", anchorText = "def f()", contextHash = "cafe",
        )

        service.updateBody(comment.id, "new")

        val stored = service.comments().single()
        assertEquals("new", stored.body)
        assertEquals(comment.id, stored.id)
        assertEquals(comment.subject, stored.subject)
        assertEquals("def f()", stored.anchorText)
        assertEquals("cafe", stored.contextHash)
        assertEquals(CommentStatus.ACTIVE, stored.status)
        assertEquals(listOf(stored), probe.updated)
    }

    fun `test updateBody to the same body does not republish`() {
        val comment = service.addComment(Subject.Line("file:///a.py", 3), "same")

        service.updateBody(comment.id, "same")

        assertTrue(probe.updated.isEmpty())
    }

    fun `test updateBody on an unknown id is a silent no-op`() {
        service.updateBody(io.github.zerlok.agentsessionrelay.domain.CommentId("nope"), "x")

        assertTrue(service.comments().isEmpty())
        assertTrue(probe.updated.isEmpty())
    }

    fun `test comments preserves insertion order`() {
        val a = service.addComment(Subject.Line("file:///a.py", 1), "a")
        val b = service.addComment(Subject.Line("file:///b.py", 2), "b")
        val c = service.addComment(Subject.Line("file:///c.py", 3), "c")

        assertEquals(listOf(a, b, c), service.comments())
    }
}
