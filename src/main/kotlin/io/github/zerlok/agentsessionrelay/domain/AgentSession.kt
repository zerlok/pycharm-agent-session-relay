package io.github.zerlok.agentsessionrelay.domain

/**
 * Stable, opaque identity of an [AgentSession] — the sole key the gateway routes events by (design
 * D5). Mirrors [CommentId]: a value class so the domain speaks in a typed id, never a bare `String`.
 * It is a non-secret routing key, not a credential (design D8), and MUST stay valid across IDE
 * restarts so a surviving agent keeps addressing the same registration (design D6).
 */
@JvmInline
value class SessionId(val value: String)

/**
 * Where an agent session runs. Purely descriptive metadata for display and badges (session-registry
 * spec); the plugin is environment-blind past launch (design D1) and never branches behaviour on it.
 */
enum class AgentEnvironment {
    LOCAL,
    DOCKER,
    SSH,
    CUSTOM,
}

/**
 * What a session's start-script config *declared* it can report, so rendering stays capability-honest
 * (design D11): no working state for an agent that never emits `turn.started`, no needs-input
 * indicator for one that never emits `needs.input`. The registry never suppresses events on this —
 * it is a rendering hint the view (task 4) consults; the last-event timestamp carries the residual.
 */
data class SessionCapabilities(
    val turnStarted: Boolean = false,
    val needsInput: Boolean = false,
)

/**
 * One launched agent session — inert, serializable-shaped data (ARCHITECTURE §3.1, §3.2, design D9).
 * The store holds only this, never a live `Project` or terminal handle: the launching project is kept
 * as its base-path [String] so the domain stays platform-free and the tool window/notifier can filter
 * to their own project (design D9).
 *
 * Registration metadata ([id], [agentLabel], [environment], [projectBasePath], [startScriptRef],
 * [capabilities], [lastEventAt]) persists (design D6). The live [state] does NOT: it is event-derived and
 * comes back [SessionState.Unknown] after a restart, while [lastEventAt] survives so a restored entry can
 * show its last-event time (design D6 line 141).
 */
data class AgentSession(
    val id: SessionId,
    /** Human-facing agent name from the launching config (e.g. "Claude Code"). */
    val agentLabel: String,
    val environment: AgentEnvironment,
    /** Base path of the project this session was launched from — a plain path, never a `Project` (D9). */
    val projectBasePath: String,
    /** Reference (config name) to the local Settings start-script that launched it (design D10). */
    val startScriptRef: String,
    val capabilities: SessionCapabilities = SessionCapabilities(),
    /** Live, event-derived (design D5); restored sessions come back [SessionState.Unknown] (design D6). */
    val state: SessionState = SessionState.Registered,
    /** Epoch millis of the last received event, or null before any event; preserved across restarts (D6). */
    val lastEventAt: Long? = null,
)
