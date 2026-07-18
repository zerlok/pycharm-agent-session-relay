package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionId

/**
 * Dumb CRUD over [AgentSession] records keyed by [SessionId] — the storage layer (ARCHITECTURE §3.1),
 * mirroring [ReviewBatchStorage]. It holds no policy and no events; the logic layer
 * ([io.github.zerlok.agentsessionrelay.logic.SessionRegistryService]) mediates every read/write. *How*
 * records are held is hidden here so the persistence swap (in-memory → `PersistentStateComponent`)
 * touches storage alone.
 */
interface SessionRegistryStorage {
    /** Sessions in registration (insertion) order. */
    fun all(): List<AgentSession>

    fun get(id: SessionId): AgentSession?

    fun add(session: AgentSession)

    /** Replaces the record with the same id in place (keeping order); no-op if it isn't present. */
    fun update(session: AgentSession)

    /** Removes and returns the session, or null if it wasn't present. */
    fun remove(id: SessionId): AgentSession?

    fun clear()
}

/** In-memory registry backing — the constructible test store (persistence is the platform's concern). */
class InMemorySessionRegistryStorage : SessionRegistryStorage {

    // LinkedHashMap keeps registration order so the tool window lists sessions as they were launched.
    private val sessions = LinkedHashMap<SessionId, AgentSession>()

    override fun all(): List<AgentSession> = sessions.values.toList()

    override fun get(id: SessionId): AgentSession? = sessions[id]

    override fun add(session: AgentSession) {
        sessions[session.id] = session
    }

    // A LinkedHashMap put on an existing key replaces the value in place, preserving insertion order.
    override fun update(session: AgentSession) {
        if (sessions.containsKey(session.id)) sessions[session.id] = session
    }

    override fun remove(id: SessionId): AgentSession? = sessions.remove(id)

    override fun clear() = sessions.clear()
}
