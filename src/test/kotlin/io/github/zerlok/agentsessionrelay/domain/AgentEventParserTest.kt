package io.github.zerlok.agentsessionrelay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests of [parseAgentEvent] (task 1.2) — each event type, `needs.input` kind refinement,
 * tolerated unknown type, and malformed input. No platform: the parser is transport-agnostic (design D3).
 */
class AgentEventParserTest {

    private fun parsed(type: String, kind: String? = null): AgentEvent {
        val result = parseAgentEvent(type, kind)
        assertTrue("expected Parsed for '$type', got $result", result is EventParseResult.Parsed)
        return (result as EventParseResult.Parsed).event
    }

    // -- Each event type --

    @Test
    fun `session_started parses`() {
        assertEquals(AgentEvent.SessionStarted, parsed(AgentEventType.SESSION_STARTED))
    }

    @Test
    fun `turn_started parses`() {
        assertEquals(AgentEvent.TurnStarted, parsed(AgentEventType.TURN_STARTED))
    }

    @Test
    fun `turn_completed parses`() {
        assertEquals(AgentEvent.TurnCompleted, parsed(AgentEventType.TURN_COMPLETED))
    }

    @Test
    fun `session_ended parses`() {
        assertEquals(AgentEvent.SessionEnded, parsed(AgentEventType.SESSION_ENDED))
    }

    // -- needs.input kind refinement --

    @Test
    fun `needs_input with kind permission refines to permission`() {
        assertEquals(AgentEvent.NeedsInput(NeedsInputKind.PERMISSION), parsed(AgentEventType.NEEDS_INPUT, "permission"))
    }

    @Test
    fun `needs_input with kind idle refines to idle`() {
        assertEquals(AgentEvent.NeedsInput(NeedsInputKind.IDLE), parsed(AgentEventType.NEEDS_INPUT, "idle"))
    }

    @Test
    fun `needs_input with kind question refines to question`() {
        assertEquals(AgentEvent.NeedsInput(NeedsInputKind.QUESTION), parsed(AgentEventType.NEEDS_INPUT, "question"))
    }

    @Test
    fun `needs_input kind is case-insensitive and trimmed`() {
        assertEquals(AgentEvent.NeedsInput(NeedsInputKind.PERMISSION), parsed(AgentEventType.NEEDS_INPUT, "  PERMISSION "))
    }

    @Test
    fun `needs_input with no kind falls back to question`() {
        assertEquals(AgentEvent.NeedsInput(NeedsInputKind.QUESTION), parsed(AgentEventType.NEEDS_INPUT, null))
    }

    @Test
    fun `needs_input with an unknown kind falls back to question tolerantly`() {
        assertEquals(AgentEvent.NeedsInput(NeedsInputKind.QUESTION), parsed(AgentEventType.NEEDS_INPUT, "bogus"))
    }

    // -- Unknown type (tolerated no-op) --

    @Test
    fun `an unrecognized but well-formed type is a tolerated unknown`() {
        assertEquals(EventParseResult.ToleratedUnknown, parseAgentEvent("some.future.event"))
    }

    // -- Malformed input --

    @Test
    fun `a blank type is malformed`() {
        assertTrue(parseAgentEvent("") is EventParseResult.Malformed)
        assertTrue(parseAgentEvent("   ") is EventParseResult.Malformed)
    }

    @Test
    fun `a body is ignored and does not affect parsing`() {
        assertEquals(
            AgentEvent.TurnCompleted,
            parsed2(parseAgentEvent(AgentEventType.TURN_COMPLETED, kind = null, body = "{\"summary\":\"x\",\"extra\":1}")),
        )
    }

    private fun parsed2(result: EventParseResult): AgentEvent =
        (result as EventParseResult.Parsed).event
}
