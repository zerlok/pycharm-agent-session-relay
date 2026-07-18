package io.github.zerlok.agentsessionrelay.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState

/**
 * The durable backing for the session registry (design D6, D9): a [SessionRegistryStorage] whose
 * **registrations** also persist via [PersistentStateComponent]. Mirrors [PersistentReviewBatchStorage],
 * with the deliberate app-level deviation of design D9 — the registry outlives projects, so this is an
 * `APP`-level `@Service` written to an app-config file (`relayAgentSessions.xml`), not the project-scoped
 * `workspace.xml`.
 *
 * Persistence gotchas (unit tests can't catch these — see the reflective guard in the test):
 *  - `@State` is REQUIRED; a bare `@Storage` on the class is inert, so state would silently never persist.
 *  - `getStateRequiresEdt = true` pins the otherwise-background [getState] to the EDT, where every
 *    registry mutation already runs (ARCHITECTURE §5.3), so a save can't iterate mid-mutation.
 *
 * Design D6 is enforced at the persistence boundary:
 *  - [getState] writes registration fields only and **drops ended sessions**, so they never restore.
 *  - [loadState] rebuilds every survivor in [SessionState.Unknown] with no last-event time.
 */
@Service(Service.Level.APP)
@State(
    name = "RelayAgentSessions",
    storages = [Storage("relayAgentSessions.xml")],
    getStateRequiresEdt = true,
)
class PersistentSessionRegistryStorage :
    SessionRegistryStorage,
    PersistentStateComponent<PersistentSessionRegistryStorage.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var sessions: MutableList<PersistedSession> = mutableListOf()
    }

    // LinkedHashMap: all() must return sessions in registration (insertion) order.
    private val sessions = LinkedHashMap<SessionId, AgentSession>()

    // -- PersistentStateComponent --

    override fun getState(): State = State().also { state ->
        state.sessions = sessions.values
            // Ended sessions are dropped from persistence (design D6): filtered out here so they are
            // never written and thus never restored.
            .filter { it.state !is SessionState.Ended }
            .mapTo(mutableListOf()) { it.toPersisted() }
    }

    override fun loadState(state: State) {
        sessions.clear()
        for (dto in state.sessions) {
            val session = dto.toDomain() // live state resets to SessionState.Unknown; lastEventAt kept (D6)
            sessions[session.id] = session
        }
    }

    // -- SessionRegistryStorage CRUD (semantics identical to InMemorySessionRegistryStorage) --

    override fun all(): List<AgentSession> = sessions.values.toList()

    override fun get(id: SessionId): AgentSession? = sessions[id]

    override fun add(session: AgentSession) {
        sessions[session.id] = session
    }

    override fun update(session: AgentSession) {
        if (sessions.containsKey(session.id)) sessions[session.id] = session
    }

    override fun remove(id: SessionId): AgentSession? = sessions.remove(id)

    override fun clear() = sessions.clear()
}
