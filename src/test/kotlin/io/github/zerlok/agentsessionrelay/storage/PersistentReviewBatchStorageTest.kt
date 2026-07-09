package io.github.zerlok.agentsessionrelay.storage

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PersistentReviewBatchStorage] (tasks 4.2). Two concerns:
 *  - **CRUD parity** with [InMemoryReviewBatchStorage] — the durable store must behave identically
 *    behind the [ReviewBatchStorage] interface (design D1).
 *  - **getState→loadState round-trip** — a snapshot serialized and inflated preserves the comments,
 *    their insertion order, and their identity (design D2, D3, D4).
 *
 * Pure Kotlin: the CRUD map and the state map/inflate are plain methods, so no platform fixture is
 * needed to exercise them (the `@Service` lifecycle is the platform's concern, not this logic's).
 */
class PersistentReviewBatchStorageTest {

    private fun comment(id: String, line: Int = 0, body: String = "b") =
        ReviewComment(id = CommentId(id), subject = Subject.Line("file:///a.py", line), body = body)

    // -- CRUD parity with InMemoryReviewBatchStorage --

    @Test
    fun `add then get returns the stored comment`() {
        val storage = PersistentReviewBatchStorage()
        val c = comment("1")

        storage.add(c)

        assertSame(c, storage.get(CommentId("1")))
    }

    @Test
    fun `get of an absent id returns null`() {
        assertNull(PersistentReviewBatchStorage().get(CommentId("missing")))
    }

    @Test
    fun `all preserves insertion order`() {
        val storage = PersistentReviewBatchStorage()
        val a = comment("a")
        val b = comment("b")
        val c = comment("c")

        storage.add(c)
        storage.add(a)
        storage.add(b)

        assertEquals(listOf(c, a, b), storage.all())
    }

    @Test
    fun `update replaces the record in place for a present id`() {
        val storage = PersistentReviewBatchStorage()
        storage.add(comment("1", line = 5))
        storage.add(comment("2"))

        storage.update(comment("1", line = 99))

        assertEquals(Subject.Line("file:///a.py", 99), storage.get(CommentId("1"))!!.subject)
        assertEquals(listOf(CommentId("1"), CommentId("2")), storage.all().map { it.id })
    }

    @Test
    fun `update of an absent id is a no-op and does not insert`() {
        val storage = PersistentReviewBatchStorage()
        storage.add(comment("1"))

        storage.update(comment("missing", body = "ghost"))

        assertNull(storage.get(CommentId("missing")))
        assertEquals(1, storage.all().size)
    }

    @Test
    fun `remove returns the removed value and drops it`() {
        val storage = PersistentReviewBatchStorage()
        val c = comment("1")
        storage.add(c)

        assertSame(c, storage.remove(CommentId("1")))
        assertNull(storage.get(CommentId("1")))
        assertTrue(storage.all().isEmpty())
    }

    @Test
    fun `remove of an absent id returns null`() {
        assertNull(PersistentReviewBatchStorage().remove(CommentId("missing")))
    }

    @Test
    fun `clear empties the batch`() {
        val storage = PersistentReviewBatchStorage()
        storage.add(comment("1"))
        storage.add(comment("2"))

        storage.clear()

        assertTrue(storage.all().isEmpty())
    }

    // -- getState → loadState round-trip --

    @Test
    fun `getState then loadState preserves comments, order, and identity`() {
        val source = PersistentReviewBatchStorage()
        val a = comment("a", line = 1, body = "first")
        val b = ReviewComment(
            CommentId("b"), Subject.LineRange("file:///b.py", 3, 5), "second",
            status = CommentStatus.STALE, anchorText = "def f()", contextHash = "cafe",
        )
        val c = ReviewComment(CommentId("c"), Subject.Project, "third")
        // Insert out of lexical order to prove order is insertion order, not key order.
        source.add(c)
        source.add(a)
        source.add(b)

        val state = source.getState()
        val restored = PersistentReviewBatchStorage()
        restored.loadState(state)

        assertEquals(listOf(c, a, b), restored.all())
        assertEquals(listOf(CommentId("c"), CommentId("a"), CommentId("b")), restored.all().map { it.id })
        val restoredB = restored.get(CommentId("b"))!!
        assertEquals(b, restoredB)
        assertEquals(CommentStatus.STALE, restoredB.status)
        assertEquals("cafe", restoredB.contextHash)
    }

    @Test
    fun `an empty batch round-trips as empty`() {
        val restored = PersistentReviewBatchStorage()
        restored.loadState(PersistentReviewBatchStorage().getState())

        assertTrue(restored.all().isEmpty())
    }

    @Test
    fun `loadState replaces any prior in-memory contents`() {
        val storage = PersistentReviewBatchStorage()
        storage.add(comment("stale"))

        // An empty incoming state must not leave the stale comment behind.
        storage.loadState(PersistentReviewBatchStorage.State())

        assertTrue(storage.all().isEmpty())
    }

    // -- Registration guard --
    // The platform drives loadState/getState only via the @State annotation; a bare @Storage on the
    // class is inert, so persistence would silently break with no test to catch it (that was the exact
    // bug). This reflective check fails fast if @State, its WORKSPACE_FILE storage, or the EDT pin for
    // the getState race (ARCHITECTURE §5.3) is ever dropped.
    @Test
    fun `is registered with a State pointing at the workspace file`() {
        val state = PersistentReviewBatchStorage::class.java.getAnnotation(State::class.java)
        assertNotNull("@State annotation is required for the platform to persist state", state)
        assertTrue("@State.name must be set", state.name.isNotBlank())
        assertEquals(1, state.storages.size)
        assertEquals(StoragePathMacros.WORKSPACE_FILE, state.storages[0].value)
        assertTrue("getState must be pinned to the EDT to avoid the save-vs-mutation race", state.getStateRequiresEdt)
    }
}
