package io.github.zerlok.agentsessionrelay.ui

import io.github.zerlok.agentsessionrelay.domain.SessionState

/**
 * The two Relay session notifications the plugin raises (agent-notifications spec): a completed turn and a
 * needs-input request. Split from the Swing [AgentSessionNotifier] so the two decisions the spec pins down —
 * *which* state change is notification-worthy and *whether* it beeps — are unit-testable without a display or
 * audio (design D5 keeps the state machine pure; this keeps its presentation decisions pure too).
 */
enum class SessionNotification {
    /** A turn finished — the addressed session went idle (`turn.completed`). */
    TURN_COMPLETED,

    /** The agent is blocked on the user (`needs.input`), higher urgency than [TURN_COMPLETED]. */
    NEEDS_INPUT,
}

/**
 * An inert snapshot of the two per-event sound toggles (agent-notifications spec; both ON by default), read
 * from [io.github.zerlok.agentsessionrelay.logic.AgentSettingsService] at the point of decision. A plain data
 * value so [AgentSessionNotifications.shouldBeep] stays pure — the gate is decided here, the actual
 * `Toolkit.beep()` happens behind it in the notifier, so tests never touch audio.
 */
data class RelaySoundSettings(
    val turnCompletedSound: Boolean,
    val needsInputSound: Boolean,
)

/**
 * The **pure** notification decisions of the session notifier (task 5.1/5.2). The registry seam
 * ([io.github.zerlok.agentsessionrelay.logic.SessionRegistryListener]) hands the notifier a session whose live
 * [SessionState] just changed — not the raw event — so the notification-worthy change is recovered here from
 * the (previous, next) state pair. No platform imports: it maps inert [SessionState] to a [SessionNotification].
 */
object AgentSessionNotifications {

    /**
     * The notification a state change from [previous] to [next] warrants, or null when it is not
     * user-facing (agent-notifications spec):
     *  - entering [SessionState.NeedsInput] → [SessionNotification.NEEDS_INPUT] (unambiguous — only
     *    `needs.input` reaches that state; design D5).
     *  - entering [SessionState.Idle] → [SessionNotification.TURN_COMPLETED], **except** the initial
     *    [SessionState.Registered]→[SessionState.Idle] hop, which is the launch-time `session.started`
     *    rather than a completed turn. Both events land on [SessionState.Idle] (design D5) and the seam
     *    carries only state, so this one launch transition is suppressed to avoid a spurious "turn
     *    completed" at start; a null [previous] (notifier subscribed mid-flight) is treated as a real
     *    completion so an actual `turn.completed` is never missed.
     *  - every other transition (→working / →ended / →unknown / →registered) → null.
     */
    fun classify(previous: SessionState?, next: SessionState): SessionNotification? = when (next) {
        is SessionState.NeedsInput -> SessionNotification.NEEDS_INPUT
        SessionState.Idle -> if (previous is SessionState.Registered) null else SessionNotification.TURN_COMPLETED
        else -> null
    }

    /**
     * Whether [notification] should play a short beep, gated by its own toggle in [settings]
     * (agent-notifications spec: "independently toggleable per event", both ON by default). The sole gate for
     * the audio side effect, kept pure so all four toggle combinations are testable without sound.
     */
    fun shouldBeep(notification: SessionNotification, settings: RelaySoundSettings): Boolean = when (notification) {
        SessionNotification.TURN_COMPLETED -> settings.turnCompletedSound
        SessionNotification.NEEDS_INPUT -> settings.needsInputSound
    }
}
