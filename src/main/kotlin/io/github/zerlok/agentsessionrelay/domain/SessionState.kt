package io.github.zerlok.agentsessionrelay.domain

/**
 * The live state of an [AgentSession], derived **exclusively** from gateway events (design D5). Never
 * persisted (design D6): a restored session comes back [Unknown] and corrects itself on its next event.
 */
sealed interface SessionState {
    /** Registered in-process at launch; no event seen yet. */
    data object Registered : SessionState

    /** A turn is in progress. */
    data object Working : SessionState

    /** Between turns; not blocked. */
    data object Idle : SessionState

    /** Blocked on the user, refined by [kind]; cleared by the next `turn.*` (design D5). */
    data class NeedsInput(val kind: NeedsInputKind) : SessionState

    /** Terminal — the agent reported `session.ended`. Absorbing: further events do not resurrect it. */
    data object Ended : SessionState

    /** Restored from persistence after an IDE restart; no live signal since startup (design D6). */
    data object Unknown : SessionState
}

/**
 * The registry's state machine as a **pure** function (design D5) — no platform, so every transition is
 * unit-testable. Target state is a function of the event alone (source state does not gate it), which is
 * exactly what the design's diagram encodes; the one exception is [SessionState.Ended], which is terminal
 * and absorbs any further event so a late post cannot zombie-resurrect an ended session.
 *
 * Covered transitions (gateway spec "Session state machine"):
 *  - `session.started` / `turn.completed` → [SessionState.Idle] (incl. resuming an [SessionState.Unknown]
 *    restored session)
 *  - `turn.started` → [SessionState.Working]
 *  - `needs.input(kind)` → [SessionState.NeedsInput]; the next `turn.*` clears it
 *  - `session.ended` → [SessionState.Ended]
 */
fun SessionState.on(event: AgentEvent): SessionState {
    if (this is SessionState.Ended) return SessionState.Ended
    return when (event) {
        AgentEvent.SessionStarted -> SessionState.Idle
        AgentEvent.TurnStarted -> SessionState.Working
        AgentEvent.TurnCompleted -> SessionState.Idle
        is AgentEvent.NeedsInput -> SessionState.NeedsInput(event.kind)
        AgentEvent.SessionEnded -> SessionState.Ended
    }
}
