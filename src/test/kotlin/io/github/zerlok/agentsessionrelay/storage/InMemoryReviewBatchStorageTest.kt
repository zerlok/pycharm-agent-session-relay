package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the dumb-CRUD storage layer ([InMemoryReviewBatchStorage], task 8.1). Pure Kotlin —
 * no platform fixture: storage holds only inert records (ARCHITECTURE §3.1, §3.2).
 */
class InMemoryReviewBatchStorageTest {

    private fun comment(id: String, line: Int = 0, body: String = "b") =
        ReviewComment(id = CommentId(id), subject = Subject.Line("file:///a.py", line), body = body)

    @Test
    fun `add then get returns the stored comment`() {
        val storage = InMemoryReviewBatchStorage()
        val c = comment("1")

        storage.add(c)

        assertSame(c, storage.get(CommentId("1")))
    }

    @Test
    fun `get of an absent id returns null`() {
        assertNull(InMemoryReviewBatchStorage().get(CommentId("missing")))
    }

    @Test
    fun `all preserves insertion order`() {
        val storage = InMemoryReviewBatchStorage()
        val a = comment("a")
        val b = comment("b")
        val c = comment("c")

        // Insert out of lexical order to prove it is insertion order, not key order.
        storage.add(c)
        storage.add(a)
        storage.add(b)

        assertEquals(listOf(c, a, b), storage.all())
    }

    @Test
    fun `all returns an independent snapshot`() {
        val storage = InMemoryReviewBatchStorage()
        storage.add(comment("1"))

        val snapshot = storage.all()
        storage.add(comment("2"))

        // The earlier list is not mutated by the later add.
        assertEquals(1, snapshot.size)
        assertEquals(2, storage.all().size)
    }

    @Test
    fun `add with an existing id overwrites the record but keeps its position`() {
        val storage = InMemoryReviewBatchStorage()
        storage.add(comment("1", body = "first"))
        storage.add(comment("2", body = "second"))

        storage.add(comment("1", body = "updated"))

        val all = storage.all()
        // Re-adding key "1" keeps it at its original index 0 (LinkedHashMap replace semantics).
        assertEquals(listOf(CommentId("1"), CommentId("2")), all.map { it.id })
        assertEquals("updated", all[0].body)
    }

    @Test
    fun `update replaces the record in place for a present id`() {
        val storage = InMemoryReviewBatchStorage()
        storage.add(comment("1", line = 5))
        storage.add(comment("2"))

        storage.update(comment("1", line = 99))

        assertEquals(Subject.Line("file:///a.py", 99), storage.get(CommentId("1"))!!.subject)
        // Order is preserved by the in-place replace.
        assertEquals(listOf(CommentId("1"), CommentId("2")), storage.all().map { it.id })
    }

    @Test
    fun `update of an absent id is a no-op and does not insert`() {
        val storage = InMemoryReviewBatchStorage()
        storage.add(comment("1"))

        storage.update(comment("missing", body = "ghost"))

        assertNull(storage.get(CommentId("missing")))
        assertEquals(1, storage.all().size)
    }

    @Test
    fun `remove returns the removed value and drops it from the batch`() {
        val storage = InMemoryReviewBatchStorage()
        val c = comment("1")
        storage.add(c)

        val removed = storage.remove(CommentId("1"))

        assertSame(c, removed)
        assertNull(storage.get(CommentId("1")))
        assertTrue(storage.all().isEmpty())
    }

    @Test
    fun `remove of an absent id returns null`() {
        assertNull(InMemoryReviewBatchStorage().remove(CommentId("missing")))
    }

    @Test
    fun `clear empties the batch`() {
        val storage = InMemoryReviewBatchStorage()
        storage.add(comment("1"))
        storage.add(comment("2"))

        storage.clear()

        assertTrue(storage.all().isEmpty())
        assertNull(storage.get(CommentId("1")))
    }
}
