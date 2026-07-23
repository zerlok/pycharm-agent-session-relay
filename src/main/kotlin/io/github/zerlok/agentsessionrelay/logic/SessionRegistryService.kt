package io.github.zerlok.agentsessionrelay.logic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.zerlok.agentsessionrelay.domain.AgentEvent
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.on
import io.github.zerlok.agentsessionrelay.storage.PersistentSessionRegistryStorage
import io.github.zerlok.agentsessionrelay.storage.SessionRegistryStorage

/**
 * The logic layer for the session registry (ARCHITECTURE §3.1, design D9): the **only** API the
 * presentation layer (tool window, notifier) and the gateway see. It mediates every read/write to
 * [SessionRegistryStorage] and dispatches change events on [SessionRegistryListener]. No Swing, no
 * editor imports.
 *
 * App-level (design D9): the built-in web server and sessions outlive projects, so unlike
 * [ReviewBatchService] this is a `Service.Level.APP` service publishing on the **application** message
 * bus. State is derived exclusively from events (design D5): [applyEvent] folds an [AgentEvent] through
 * the pure [on] state machine.
 *
 * Threading (ARCHITECTURE §5.3): mutations and listener callbacks run on the EDT. The HTTP handler
 * (task 2) runs on a server thread and MUST hop to the EDT before calling [applyEvent].
 */
@Service(Service.Level.APP)
class SessionRegistryService {

    // The one place the concrete backing is named. Obtained as a service, never constructed, so the
    // platform owns its loadState/getState lifecycle.
    private val storage: SessionRegistryStorage = service<PersistentSessionRegistryStorage>()

    // -- Queries --

    fun sessions(): List<AgentSession> = storage.all()

    fun session(id: SessionId): AgentSession? = storage.get(id)

    // -- Commands --

    /**
     * Records an in-process registration at launch (design D7) and publishes [sessionRegistered]. The
     * caller (the launch service, task 3.2) mints the id and builds the [AgentSession] — this service
     * stays policy-thin, exactly like [ReviewBatchService.addComment] takes a built record's parts.
     */
    fun register(session: AgentSession): AgentSession {
        storage.add(session)
        publisher().sessionRegistered(session)
        return session
    }

    /**
     * Applies a normalized [event] to the addressed session (design D5): folds it through the pure [on]
     * state machine, stamps [AgentSession.lastEventAt], persists, and publishes [sessionUpdated]. An id
     * matching no registration is an **acknowledged no-op** — dropped without changing any state
     * (agent-event-gateway spec: "Unknown session id dropped safely"); an event can affect only the
     * session its route addresses.
     */
    fun applyEvent(id: SessionId, event: AgentEvent) {
        val existing = storage.get(id) ?: return
        val updated = existing.copy(
            state = existing.state.on(event),
            lastEventAt = System.currentTimeMillis(),
        )
        storage.update(updated)
        publisher().sessionUpdated(updated)
    }

    /**
     * Dismisses a session: drops it from the registry and its persistence and publishes
     * [sessionRemoved]. Local-only — nothing is sent to the agent or its host (session-registry spec:
     * "Any session is dismissable, locally only"; design D6). No-op on an unknown id.
     */
    fun dismiss(id: SessionId) {
        val removed = storage.remove(id) ?: return
        publisher().sessionRemoved(removed)
    }

    private fun publisher(): SessionRegistryListener =
        ApplicationManager.getApplication().messageBus.syncPublisher(SessionRegistryListener.TOPIC)

    companion object {
        fun getInstance(): SessionRegistryService = service()
    }
}
