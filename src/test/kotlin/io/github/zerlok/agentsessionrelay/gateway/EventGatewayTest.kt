package io.github.zerlok.agentsessionrelay.gateway

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentEventType
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService

/**
 * Direct, real-platform test of the transport-agnostic seam (task 2.1), mirroring
 * [io.github.zerlok.agentsessionrelay.logic.SessionRegistryServiceTest]: it drives [EventGateway.accept]
 * with the wire primitives (id/type/kind strings) an HTTP request would carry — NOT through HTTP — and
 * checks both the returned [GatewayOutcome] the transport maps to a status and the real registry state.
 * The test body runs on the EDT (BasePlatformTestCase), satisfying [EventGateway.accept]'s EDT contract.
 */
class EventGatewayTest : BasePlatformTestCase() {

    private lateinit var gateway: EventGateway
    private lateinit var registry: SessionRegistryService

    private fun session(id: String) = AgentSession(
        id = SessionId(id),
        agentLabel = "Claude Code",
        environment = AgentEnvironment.LOCAL,
        projectBasePath = "/p/$id",
        startScriptRef = "cfg",
    )

    private fun state(id: String): SessionState? = registry.session(SessionId(id))?.state

    override fun setUp() {
        super.setUp()
        gateway = EventGateway.getInstance()
        registry = SessionRegistryService.getInstance()
        // The app-level registry is shared across the run — start each test from a clean slate.
        registry.sessions().forEach { registry.dismiss(it.id) }
    }

    override fun tearDown() {
        try {
            registry.sessions().forEach { registry.dismiss(it.id) }
        } finally {
            super.tearDown()
        }
    }

    // -- Each event type applied (state machine driven through the gateway) --

    fun `test session_started accepted and marks the session idle`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", AgentEventType.SESSION_STARTED)

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.Idle, state("1"))
    }

    fun `test turn_started accepted and marks the session working`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", AgentEventType.TURN_STARTED)

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.Working, state("1"))
    }

    fun `test turn_completed accepted and marks the session idle`() {
        registry.register(session("1"))
        gateway.accept("1", AgentEventType.TURN_STARTED)

        val outcome = gateway.accept("1", AgentEventType.TURN_COMPLETED)

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.Idle, state("1"))
    }

    fun `test session_ended accepted and marks the session ended`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", AgentEventType.SESSION_ENDED)

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.Ended, state("1"))
    }

    // -- needs.input + kind refinement --

    fun `test needs_input without kind falls back to question`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", AgentEventType.NEEDS_INPUT)

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.NeedsInput(NeedsInputKind.QUESTION), state("1"))
    }

    fun `test needs_input refined by the kind query`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", AgentEventType.NEEDS_INPUT, kind = "permission")

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.NeedsInput(NeedsInputKind.PERMISSION), state("1"))
    }

    // -- Unknown type tolerated (2xx / Accepted), no state change --

    fun `test unknown event type is tolerated and changes no state`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", "future.event")

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertEquals(SessionState.Registered, state("1"))
    }

    // -- Malformed input rejected (4xx / Rejected), no state change --

    fun `test blank event type is rejected and changes no state`() {
        registry.register(session("1"))

        val outcome = gateway.accept("1", "   ")

        assertTrue(outcome is GatewayOutcome.Rejected)
        assertEquals(SessionState.Registered, state("1"))
    }

    // -- Unknown id acknowledged-and-dropped (no state created, no error) --

    fun `test unknown session id is an acknowledged no-op`() {
        registry.register(session("1"))

        val outcome = gateway.accept("nope", AgentEventType.TURN_STARTED)

        assertEquals(GatewayOutcome.Accepted, outcome)
        assertNull(registry.session(SessionId("nope")))
        assertEquals(SessionState.Registered, state("1"))
    }

    // -- Session-id scoping: an event for A does not touch B --

    fun `test an event affects only the addressed session`() {
        registry.register(session("A"))
        registry.register(session("B"))

        gateway.accept("A", AgentEventType.TURN_STARTED)

        assertEquals(SessionState.Working, state("A"))
        assertEquals(SessionState.Registered, state("B"))
    }
}
