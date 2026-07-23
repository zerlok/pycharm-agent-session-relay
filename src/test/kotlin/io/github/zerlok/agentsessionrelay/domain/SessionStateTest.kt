package io.github.zerlok.agentsessionrelay.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests of the [SessionState.on] state machine (task 1.1) — no platform fixture, since the
 * machine is a pure function (design D5). Every transition in the gateway spec's "Session state machine"
 * is covered, including needs-input cleared by `turn.*` and the restored-`unknown` resume path.
 */
class SessionStateTest {

    @Test
    fun `registered plus session_started becomes idle`() {
        assertEquals(SessionState.Idle, SessionState.Registered.on(AgentEvent.SessionStarted))
    }

    @Test
    fun `registered plus turn_completed becomes idle`() {
        assertEquals(SessionState.Idle, SessionState.Registered.on(AgentEvent.TurnCompleted))
    }

    @Test
    fun `idle plus turn_started becomes working`() {
        assertEquals(SessionState.Working, SessionState.Idle.on(AgentEvent.TurnStarted))
    }

    @Test
    fun `working plus turn_completed becomes idle`() {
        assertEquals(SessionState.Idle, SessionState.Working.on(AgentEvent.TurnCompleted))
    }

    @Test
    fun `working plus needs_input becomes needs-input carrying the kind`() {
        assertEquals(
            SessionState.NeedsInput(NeedsInputKind.PERMISSION),
            SessionState.Working.on(AgentEvent.NeedsInput(NeedsInputKind.PERMISSION)),
        )
    }

    @Test
    fun `needs-input cleared by the next turn_started becomes working`() {
        val needsInput = SessionState.NeedsInput(NeedsInputKind.QUESTION)
        assertEquals(SessionState.Working, needsInput.on(AgentEvent.TurnStarted))
    }

    @Test
    fun `needs-input cleared by the next turn_completed becomes idle`() {
        val needsInput = SessionState.NeedsInput(NeedsInputKind.IDLE)
        assertEquals(SessionState.Idle, needsInput.on(AgentEvent.TurnCompleted))
    }

    @Test
    fun `session_ended ends the session from every non-terminal state`() {
        val states = listOf(
            SessionState.Registered,
            SessionState.Working,
            SessionState.Idle,
            SessionState.NeedsInput(NeedsInputKind.PERMISSION),
            SessionState.Unknown,
        )
        for (state in states) {
            assertEquals("from $state", SessionState.Ended, state.on(AgentEvent.SessionEnded))
        }
    }

    @Test
    fun `ended is terminal and absorbs further events`() {
        assertEquals(SessionState.Ended, SessionState.Ended.on(AgentEvent.TurnStarted))
        assertEquals(SessionState.Ended, SessionState.Ended.on(AgentEvent.SessionStarted))
        assertEquals(SessionState.Ended, SessionState.Ended.on(AgentEvent.SessionEnded))
    }

    @Test
    fun `restored unknown session resumes to idle on session_started`() {
        assertEquals(SessionState.Idle, SessionState.Unknown.on(AgentEvent.SessionStarted))
    }

    @Test
    fun `restored unknown session refreshes to idle on turn_completed`() {
        assertEquals(SessionState.Idle, SessionState.Unknown.on(AgentEvent.TurnCompleted))
    }

    @Test
    fun `restored unknown session refreshes to working on turn_started`() {
        assertEquals(SessionState.Working, SessionState.Unknown.on(AgentEvent.TurnStarted))
    }

    @Test
    fun `needs-input kind is preserved through the state`() {
        for (kind in NeedsInputKind.entries) {
            assertEquals(
                SessionState.NeedsInput(kind),
                SessionState.Idle.on(AgentEvent.NeedsInput(kind)),
            )
        }
    }
}
