package io.github.zerlok.agentsessionrelay.domain

/**
 * Schema version of the normalized event contract (design D5). Bumped only on a breaking wire change;
 * a single canonical source for the HTTP layer and any future transport to reference.
 */
const val AGENT_EVENT_SCHEMA: Int = 1

/**
 * The wire `<type>` names of the normalized webhook route `POST
 * /relay/v1/sessions/<id>/events/<type>` (design D3/D5) — defined once here so the parser, the HTTP
 * handler (task 2) and `docs/ADAPTERS.md` share one source of truth (user rule: no restating).
 */
object AgentEventType {
    const val SESSION_STARTED = "session.started"
    const val TURN_STARTED = "turn.started"
    const val TURN_COMPLETED = "turn.completed"
    const val NEEDS_INPUT = "needs.input"
    const val SESSION_ENDED = "session.ended"
}

/**
 * How a `needs.input` event refines what the agent is waiting on (the `?kind=` query param, design D5).
 * Also the kind carried in [SessionState.NeedsInput]. Unknown/absent kinds map tolerantly to [QUESTION]
 * (design D5: unknown fields ignored) so a bare `needs.input` still surfaces as "awaiting your input".
 */
enum class NeedsInputKind {
    PERMISSION,
    IDLE,
    QUESTION;

    companion object {
        /** Wire `?kind=` value → kind; absent or unrecognized falls back to [QUESTION] (tolerant, D5). */
        fun fromWire(raw: String?): NeedsInputKind = when (raw?.trim()?.lowercase()) {
            "permission" -> PERMISSION
            "idle" -> IDLE
            "question" -> QUESTION
            else -> QUESTION
        }
    }
}

/**
 * A normalized agent lifecycle event (`schema: 1`, design D5) — pure, platform-free data so the state
 * machine ([SessionState.on]) and this type are unit-testable without a fixture, and the HTTP layer
 * (task 2) parses transport primitives into this before touching any consumer. Event bodies carry **no**
 * session id: identity is the id in the route (design D5).
 */
sealed interface AgentEvent {
    /** Agent process is up and idle. */
    data object SessionStarted : AgentEvent

    /** A turn began — the agent is working. */
    data object TurnStarted : AgentEvent

    /** A turn finished — the agent is idle. Also clears a prior needs-input. */
    data object TurnCompleted : AgentEvent

    /** The agent is blocked on the user, refined by [kind]. Cleared by the next `turn.*` (design D5). */
    data class NeedsInput(val kind: NeedsInputKind) : AgentEvent

    /** The session ended (terminal). */
    data object SessionEnded : AgentEvent
}
