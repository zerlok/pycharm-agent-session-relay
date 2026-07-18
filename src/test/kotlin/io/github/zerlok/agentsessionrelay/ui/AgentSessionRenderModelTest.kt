package io.github.zerlok.agentsessionrelay.ui

import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure unit tests of the Agent Sessions tool window's presentation logic (task 4.1) — the two decisions the
 * session-registry spec pins down: project scoping and capability-honest state rendering (design D9/D11). No
 * platform fixture, since [AgentSessionRenderModel] is pure.
 */
class AgentSessionRenderModelTest {

    private fun session(
        id: String = "s",
        projectBasePath: String = "/p",
        state: SessionState = SessionState.Registered,
        caps: SessionCapabilities = SessionCapabilities(),
        lastEventAt: Long? = null,
    ) = AgentSession(
        id = SessionId(id),
        agentLabel = "Claude Code",
        environment = AgentEnvironment.LOCAL,
        projectBasePath = projectBasePath,
        startScriptRef = "cfg",
        capabilities = caps,
        state = state,
        lastEventAt = lastEventAt,
    )

    // -- Project scoping (session-registry spec: "Only this project's sessions listed") --

    @Test
    fun `forProject lists only sessions launched from this project`() {
        val mine = session(id = "mine", projectBasePath = "/here")
        val theirs = session(id = "theirs", projectBasePath = "/elsewhere")
        assertEquals(listOf(mine), AgentSessionRenderModel.forProject(listOf(mine, theirs), "/here"))
    }

    @Test
    fun `forProject with a null base path lists nothing`() {
        val any = session(projectBasePath = "/here")
        assertEquals(emptyList<AgentSession>(), AgentSessionRenderModel.forProject(listOf(any), null))
    }

    // -- Capability-honest rendering (session-registry spec; design D11) --

    @Test
    fun `needs-input renders when the config declared needs_input`() {
        val s = session(
            state = SessionState.NeedsInput(NeedsInputKind.PERMISSION),
            caps = SessionCapabilities(needsInput = true),
        )
        assertEquals(SessionDisplayState.NEEDS_INPUT, AgentSessionRenderModel.displayState(s))
        assertEquals(NeedsInputKind.PERMISSION, AgentSessionRenderModel.needsInputKind(s))
    }

    @Test
    fun `needs-input is suppressed to idle when the config declared no needs_input capability`() {
        val s = session(
            state = SessionState.NeedsInput(NeedsInputKind.PERMISSION),
            caps = SessionCapabilities(needsInput = false),
        )
        assertEquals(SessionDisplayState.IDLE, AgentSessionRenderModel.displayState(s))
        assertNull(AgentSessionRenderModel.needsInputKind(s))
    }

    @Test
    fun `working renders when the config declared turn_started`() {
        val s = session(state = SessionState.Working, caps = SessionCapabilities(turnStarted = true))
        assertEquals(SessionDisplayState.WORKING, AgentSessionRenderModel.displayState(s))
    }

    @Test
    fun `working is suppressed to idle when the config declared no turn_started capability`() {
        val s = session(state = SessionState.Working, caps = SessionCapabilities(turnStarted = false))
        assertEquals(SessionDisplayState.IDLE, AgentSessionRenderModel.displayState(s))
    }

    @Test
    fun `restored session renders as unknown`() {
        val s = session(state = SessionState.Unknown, lastEventAt = 123L)
        assertEquals(SessionDisplayState.UNKNOWN, AgentSessionRenderModel.displayState(s))
        assertNull(AgentSessionRenderModel.needsInputKind(s))
    }

    @Test
    fun `plain lifecycle states map straight through`() {
        assertEquals(SessionDisplayState.REGISTERED, AgentSessionRenderModel.displayState(session(state = SessionState.Registered)))
        assertEquals(SessionDisplayState.IDLE, AgentSessionRenderModel.displayState(session(state = SessionState.Idle)))
        assertEquals(SessionDisplayState.ENDED, AgentSessionRenderModel.displayState(session(state = SessionState.Ended)))
    }

    @Test
    fun `environment badge is the lowercase kind`() {
        assertEquals("local", AgentSessionRenderModel.environmentBadge(AgentEnvironment.LOCAL))
        assertEquals("docker", AgentSessionRenderModel.environmentBadge(AgentEnvironment.DOCKER))
        assertEquals("ssh", AgentSessionRenderModel.environmentBadge(AgentEnvironment.SSH))
        assertEquals("custom", AgentSessionRenderModel.environmentBadge(AgentEnvironment.CUSTOM))
    }
}
