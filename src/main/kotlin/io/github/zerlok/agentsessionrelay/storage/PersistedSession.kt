package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState

/**
 * The on-disk form of an [AgentSession] registration: a flat, `xmlb`-serializable bean (mirrors
 * [PersistedComment]). `xmlb` needs a no-arg constructor and mutable `var` properties and cannot
 * serialize a Kotlin `data class`/`value class`/sealed hierarchy — so the domain types stay inert
 * (ARCHITECTURE §3.1) and this DTO absorbs the serialization concessions.
 *
 * It carries **registration metadata only** (design D6): the live event-derived [SessionState.state] is
 * never persisted, so a restored session comes back `unknown` (design D6). [lastEventAt] *is* persisted —
 * it is a registration timestamp, not the live state, and design D6 (line 141) + the session-registry
 * "Restored sessions render as unknown" requirement both require a restored entry to show its last-event
 * time.
 */
class PersistedSession {
    var id: String = ""
    var agentLabel: String = ""

    /** One of [AgentEnvironment]; tolerated back to [AgentEnvironment.CUSTOM] on unknown input. */
    var environment: String = AgentEnvironment.LOCAL.name
    var projectBasePath: String = ""
    var startScriptRef: String = ""
    var capabilityTurnStarted: Boolean = false
    var capabilityNeedsInput: Boolean = false

    /** Epoch millis of the last received event, or null if none — restored to show a session's last-event time. */
    var lastEventAt: Long? = null
}

/** Domain → DTO: keeps registration metadata (incl. [AgentSession.lastEventAt]); the live [AgentSession.state] is dropped (D6). */
fun AgentSession.toPersisted(): PersistedSession = PersistedSession().also { dto ->
    dto.id = id.value
    dto.agentLabel = agentLabel
    dto.environment = environment.name
    dto.projectBasePath = projectBasePath
    dto.startScriptRef = startScriptRef
    dto.capabilityTurnStarted = capabilities.turnStarted
    dto.capabilityNeedsInput = capabilities.needsInput
    dto.lastEventAt = lastEventAt
}

/**
 * DTO → domain, restoring in the **unknown** state (the live state resets, design D6) while keeping the
 * persisted [PersistedSession.lastEventAt] so the entry can show its last-event time (design D6 line 141;
 * session-registry "Restored sessions render as unknown"). Tolerates degenerate input: an unrecognized
 * [PersistedSession.environment] falls back to [AgentEnvironment.CUSTOM] rather than throwing, so a
 * schema-drifted record still loads instead of aborting the whole restore.
 */
fun PersistedSession.toDomain(): AgentSession = AgentSession(
    id = SessionId(id),
    agentLabel = agentLabel,
    environment = runCatching { AgentEnvironment.valueOf(environment) }.getOrDefault(AgentEnvironment.CUSTOM),
    projectBasePath = projectBasePath,
    startScriptRef = startScriptRef,
    capabilities = SessionCapabilities(
        turnStarted = capabilityTurnStarted,
        needsInput = capabilityNeedsInput,
    ),
    state = SessionState.Unknown,
    lastEventAt = lastEventAt,
)
