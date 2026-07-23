package io.github.zerlok.agentsessionrelay.storage

import com.intellij.openapi.components.State
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PersistentSessionRegistryStorage] (task 1.4). Three concerns:
 *  - **CRUD parity** with [InMemorySessionRegistryStorage] behind the [SessionRegistryStorage] interface;
 *  - **restore path** — getState→loadState keeps registrations but restores them `unknown`, and **drops
 *    ended sessions** (design D6);
 *  - a **reflective guard** that `@State` (with the app storage file + EDT pin) is present, since that
 *    platform wiring can't be reached in a unit test (mirrors [PersistentReviewBatchStorageTest]).
 */
class PersistentSessionRegistryStorageTest {

    private fun session(id: String, state: SessionState = SessionState.Registered, lastEventAt: Long? = null) =
        AgentSession(
            id = SessionId(id),
            agentLabel = "Agent $id",
            environment = AgentEnvironment.LOCAL,
            projectBasePath = "/p/$id",
            startScriptRef = "cfg-$id",
            capabilities = SessionCapabilities(turnStarted = true),
            state = state,
            lastEventAt = lastEventAt,
        )

    // -- CRUD parity --

    @Test
    fun `add then get returns the stored session`() {
        val storage = PersistentSessionRegistryStorage()
        val s = session("1")
        storage.add(s)
        assertSame(s, storage.get(SessionId("1")))
    }

    @Test
    fun `get of an absent id returns null`() {
        assertNull(PersistentSessionRegistryStorage().get(SessionId("missing")))
    }

    @Test
    fun `all preserves insertion order`() {
        val storage = PersistentSessionRegistryStorage()
        storage.add(session("c"))
        storage.add(session("a"))
        storage.add(session("b"))
        assertEquals(listOf(SessionId("c"), SessionId("a"), SessionId("b")), storage.all().map { it.id })
    }

    @Test
    fun `update replaces the record in place for a present id`() {
        val storage = PersistentSessionRegistryStorage()
        storage.add(session("1"))
        storage.update(session("1", state = SessionState.Working))
        assertEquals(SessionState.Working, storage.get(SessionId("1"))!!.state)
    }

    @Test
    fun `update of an absent id is a no-op`() {
        val storage = PersistentSessionRegistryStorage()
        storage.add(session("1"))
        storage.update(session("missing"))
        assertNull(storage.get(SessionId("missing")))
        assertEquals(1, storage.all().size)
    }

    @Test
    fun `remove returns the removed value and drops it`() {
        val storage = PersistentSessionRegistryStorage()
        val s = session("1")
        storage.add(s)
        assertSame(s, storage.remove(SessionId("1")))
        assertNull(storage.get(SessionId("1")))
    }

    @Test
    fun `clear empties the registry`() {
        val storage = PersistentSessionRegistryStorage()
        storage.add(session("1"))
        storage.add(session("2"))
        storage.clear()
        assertTrue(storage.all().isEmpty())
    }

    // -- Restore path (getState → loadState) --

    @Test
    fun `getState then loadState resets state to unknown but keeps lastEventAt`() {
        val source = PersistentSessionRegistryStorage()
        source.add(session("a", state = SessionState.Working, lastEventAt = 111L))
        source.add(session("b", state = SessionState.Idle, lastEventAt = 222L))

        val restored = PersistentSessionRegistryStorage()
        restored.loadState(source.getState())

        assertEquals(listOf(SessionId("a"), SessionId("b")), restored.all().map { it.id })
        for (s in restored.all()) {
            assertEquals("restored session must be unknown", SessionState.Unknown, s.state)
        }
        assertEquals("last-event time is preserved across restart", 111L, restored.get(SessionId("a"))!!.lastEventAt)
        assertEquals(222L, restored.get(SessionId("b"))!!.lastEventAt)
        assertEquals("cfg-a", restored.get(SessionId("a"))!!.startScriptRef)
    }

    @Test
    fun `ended sessions are dropped on save and never restore`() {
        val source = PersistentSessionRegistryStorage()
        source.add(session("live", state = SessionState.Idle))
        source.add(session("done", state = SessionState.Ended))

        val restored = PersistentSessionRegistryStorage()
        restored.loadState(source.getState())

        assertEquals(listOf(SessionId("live")), restored.all().map { it.id })
        assertNull(restored.get(SessionId("done")))
    }

    @Test
    fun `an empty registry round-trips as empty`() {
        val restored = PersistentSessionRegistryStorage()
        restored.loadState(PersistentSessionRegistryStorage().getState())
        assertTrue(restored.all().isEmpty())
    }

    @Test
    fun `loadState replaces any prior in-memory contents`() {
        val storage = PersistentSessionRegistryStorage()
        storage.add(session("stale"))
        storage.loadState(PersistentSessionRegistryStorage.State())
        assertTrue(storage.all().isEmpty())
    }

    // -- Registration guard --
    // The platform drives loadState/getState only via @State; a bare @Storage on the class is inert
    // (that was the exact persist-review-batch bug). This reflective check fails fast if @State, its
    // app-level storage file, or the EDT pin for the getState race (ARCHITECTURE §5.3) is dropped.
    @Test
    fun `is registered with a State pointing at the app storage file`() {
        val state = PersistentSessionRegistryStorage::class.java.getAnnotation(State::class.java)
        assertNotNull("@State annotation is required for the platform to persist state", state)
        assertTrue("@State.name must be set", state.name.isNotBlank())
        assertEquals(1, state.storages.size)
        assertEquals("relayAgentSessions.xml", state.storages[0].value)
        assertTrue("getState must be pinned to the EDT to avoid the save-vs-mutation race", state.getStateRequiresEdt)
    }
}
