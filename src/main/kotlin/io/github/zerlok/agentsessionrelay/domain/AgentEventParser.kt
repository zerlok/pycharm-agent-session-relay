package io.github.zerlok.agentsessionrelay.domain

/**
 * Outcome of parsing transport primitives into an [AgentEvent] (design D3/D5). Three cases map cleanly
 * onto the HTTP response the handler (task 2) will pick, keeping the parser transport-agnostic:
 *  - [Parsed] → apply to the registry, respond 2xx
 *  - [ToleratedUnknown] → an unrecognized but well-formed `<type>`; acknowledge-and-drop (2xx), so old
 *    plugins tolerate newer hooks (design D5)
 *  - [Malformed] → unusable input (e.g. a blank type); respond 4xx, change no registry state
 */
sealed interface EventParseResult {
    data class Parsed(val event: AgentEvent) : EventParseResult
    data object ToleratedUnknown : EventParseResult
    data class Malformed(val reason: String) : EventParseResult
}

/**
 * Strictly parses the normalized webhook's primitives into an [AgentEvent] (design D3/D5). Pure and
 * platform-free (no HTTP types) so the HTTP layer (task 2) is a thin shell over it and this is
 * fixture-free unit-testable.
 *
 * @param type the route `<type>` segment (required).
 * @param kind the optional `?kind=` query value; refines `needs.input`, ignored for other types.
 * @param body the optional request body; reserved for `summary`/`reason` extraction by later stages
 *   (tasks 2/5) and intentionally not interpreted here — unknown fields are ignored (design D5).
 */
@Suppress("UNUSED_PARAMETER")
fun parseAgentEvent(type: String, kind: String? = null, body: String? = null): EventParseResult {
    val name = type.trim()
    if (name.isEmpty()) return EventParseResult.Malformed("blank event type")

    val event = when (name) {
        AgentEventType.SESSION_STARTED -> AgentEvent.SessionStarted
        AgentEventType.TURN_STARTED -> AgentEvent.TurnStarted
        AgentEventType.TURN_COMPLETED -> AgentEvent.TurnCompleted
        AgentEventType.NEEDS_INPUT -> AgentEvent.NeedsInput(NeedsInputKind.fromWire(kind))
        AgentEventType.SESSION_ENDED -> AgentEvent.SessionEnded
        // A well-formed but unrecognized type is a tolerated no-op, not an error (design D5).
        else -> return EventParseResult.ToleratedUnknown
    }
    return EventParseResult.Parsed(event)
}
