package io.github.zerlok.agentsessionrelay.logic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentEvent
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState

/**
 * Real-platform test of the app-level logic layer (task 1.3), mirroring [ReviewBatchServiceTest]. It
 * exercises [SessionRegistryService] end-to-end: it mutates the real app-level storage AND publishes on
 * the real [SessionRegistryListener] `MessageBus` topic. A probe subscribes on the **application** message
 * bus, exactly as the tool window/notifier will (design D9).
 */
class SessionRegistryServiceTest : BasePlatformTestCase() {

    private class Probe : SessionRegistryListener {
        val registered = mutableListOf<AgentSession>()
        val updated = mutableListOf<AgentSession>()
        val removed = mutableListOf<AgentSession>()

        override fun sessionRegistered(session: AgentSession) { registered += session }
        override fun sessionUpdated(session: AgentSession) { updated += session }
        override fun sessionRemoved(session: AgentSession) { removed += session }
    }

    private lateinit var service: SessionRegistryService
    private lateinit var probe: Probe

    private fun session(id: String, caps: SessionCapabilities = SessionCapabilities()) = AgentSession(
        id = SessionId(id),
        agentLabel = "Claude Code",
        environment = AgentEnvironment.LOCAL,
        projectBasePath = "/p/$id",
        startScriptRef = "cfg",
        capabilities = caps,
    )

    override fun setUp() {
        super.setUp()
        service = SessionRegistryService.getInstance()
        // The app-level registry is shared across the whole test run, so drop any leftovers BEFORE
        // subscribing — the probe must see a clean slate.
        service.sessions().forEach { service.dismiss(it.id) }
        probe = Probe()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(SessionRegistryListener.TOPIC, probe)
    }

    override fun tearDown() {
        try {
            service.sessions().forEach { service.dismiss(it.id) }
        } finally {
            super.tearDown()
        }
    }

    fun `test register stores the session and publishes sessionRegistered`() {
        val s = service.register(session("1"))

        assertEquals(listOf(s), service.sessions())
        assertEquals(listOf(s), probe.registered)
        assertEquals(SessionState.Registered, s.state)
    }

    fun `test applyEvent folds through the state machine and stamps lastEventAt`() {
        service.register(session("1"))

        service.applyEvent(SessionId("1"), AgentEvent.TurnStarted)

        val stored = service.session(SessionId("1"))!!
        assertEquals(SessionState.Working, stored.state)
        assertNotNull(stored.lastEventAt)
        assertEquals(listOf(stored), probe.updated)
    }

    fun `test applyEvent needs-input carries the kind`() {
        service.register(session("1"))

        service.applyEvent(SessionId("1"), AgentEvent.NeedsInput(NeedsInputKind.PERMISSION))

        assertEquals(SessionState.NeedsInput(NeedsInputKind.PERMISSION), service.session(SessionId("1"))!!.state)
    }

    fun `test needs-input cleared by the next turn`() {
        service.register(session("1"))
        service.applyEvent(SessionId("1"), AgentEvent.NeedsInput(NeedsInputKind.QUESTION))

        service.applyEvent(SessionId("1"), AgentEvent.TurnCompleted)

        assertEquals(SessionState.Idle, service.session(SessionId("1"))!!.state)
    }

    fun `test applyEvent for an unknown id is an acknowledged no-op`() {
        service.register(session("1"))

        service.applyEvent(SessionId("nope"), AgentEvent.TurnStarted)

        assertNull(service.session(SessionId("nope")))
        assertTrue(probe.updated.isEmpty())
    }

    fun `test an event affects only the addressed session`() {
        service.register(session("A"))
        service.register(session("B"))

        service.applyEvent(SessionId("A"), AgentEvent.TurnStarted)

        assertEquals(SessionState.Working, service.session(SessionId("A"))!!.state)
        assertEquals(SessionState.Registered, service.session(SessionId("B"))!!.state)
    }

    fun `test dismiss drops the session and publishes sessionRemoved`() {
        val s = service.register(session("1"))

        service.dismiss(SessionId("1"))

        assertTrue(service.sessions().isEmpty())
        assertEquals(listOf(s), probe.removed)
    }

    fun `test dismiss of an unknown id is a silent no-op`() {
        service.register(session("1"))

        service.dismiss(SessionId("nope"))

        assertEquals(1, service.sessions().size)
        assertTrue(probe.removed.isEmpty())
    }

    fun `test sessions preserves registration order`() {
        val a = service.register(session("a"))
        val b = service.register(session("b"))
        val c = service.register(session("c"))

        assertEquals(listOf(a, b, c), service.sessions())
    }
}
