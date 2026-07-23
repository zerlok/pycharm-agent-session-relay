package io.github.zerlok.agentsessionrelay.ui

import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionState

/**
 * The capability-honest badge an Agent Sessions entry renders as (session-registry spec: "Live,
 * capability-honest state updates"). It is NOT [SessionState] one-to-one: a live [SessionState.Working]
 * or [SessionState.NeedsInput] is *demoted* to [IDLE] here when the session's config never declared the
 * matching capability (design D11), so the view never implies a signal the agent cannot emit — the
 * last-event timestamp carries the residual instead.
 */
enum class SessionDisplayState {
    REGISTERED,
    WORKING,
    IDLE,
    NEEDS_INPUT,
    ENDED,
    UNKNOWN,
}

/**
 * The **pure** presentation logic of the Agent Sessions tool window (task 4.1), split out from the Swing
 * [AgentSessionsToolWindowFactory] so the two decisions the spec pins down — project scoping and
 * capability-honest state rendering — are unit-testable without a display (design D11).
 *
 * No platform imports: it maps inert [AgentSession] domain records (ARCHITECTURE §3.1/§3.2) to display
 * values. The view calls these and only adds Swing (icons, colors, layout) on top.
 */
object AgentSessionRenderModel {

    /**
     * The sessions to list for the tool window's own project (design D9, session-registry spec: "Sessions
     * launched from other projects MUST NOT appear"). Filters by the tagged [AgentSession.projectBasePath]
     * against this project's base path. A null base path (project not yet fully open) lists nothing.
     */
    fun forProject(sessions: List<AgentSession>, projectBasePath: String?): List<AgentSession> =
        if (projectBasePath == null) emptyList()
        else sessions.filter { it.projectBasePath == projectBasePath }

    /**
     * The capability-honest badge for [session] (design D11): a live [SessionState.Working] renders as
     * [SessionDisplayState.WORKING] only when the config declared `turn.started`; a [SessionState.NeedsInput]
     * renders as [SessionDisplayState.NEEDS_INPUT] only when it declared `needs.input`. Otherwise the entry
     * falls back to [SessionDisplayState.IDLE] — turn-completion info only — because the plugin cannot
     * honestly claim a signal the agent never emits. [SessionState.Unknown] (restored) stays [UNKNOWN].
     */
    fun displayState(session: AgentSession): SessionDisplayState = when (session.state) {
        SessionState.Registered -> SessionDisplayState.REGISTERED
        SessionState.Idle -> SessionDisplayState.IDLE
        SessionState.Ended -> SessionDisplayState.ENDED
        SessionState.Unknown -> SessionDisplayState.UNKNOWN
        SessionState.Working ->
            if (session.capabilities.turnStarted) SessionDisplayState.WORKING else SessionDisplayState.IDLE
        is SessionState.NeedsInput ->
            if (session.capabilities.needsInput) SessionDisplayState.NEEDS_INPUT else SessionDisplayState.IDLE
    }

    /**
     * The kind to surface on a [SessionDisplayState.NEEDS_INPUT] entry, or null when there is nothing to
     * honestly show — either the session is not blocked, or its config never declared `needs.input` so the
     * indicator is suppressed (design D11). Mirrors the demotion in [displayState].
     */
    fun needsInputKind(session: AgentSession): NeedsInputKind? {
        val state = session.state
        return if (state is SessionState.NeedsInput && session.capabilities.needsInput) state.kind else null
    }

    /** The lowercase environment badge shown per entry (session-registry spec: `local`/`docker`/`ssh`/`custom`). */
    fun environmentBadge(environment: AgentEnvironment): String = when (environment) {
        AgentEnvironment.LOCAL -> "local"
        AgentEnvironment.DOCKER -> "docker"
        AgentEnvironment.SSH -> "ssh"
        AgentEnvironment.CUSTOM -> "custom"
    }
}
