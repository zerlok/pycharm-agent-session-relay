package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities

/**
 * The on-disk form of an [EnvironmentConfig] start-script config: a flat, `xmlb`-serializable bean (mirrors
 * [PersistedSession]/[PersistedComment]). `xmlb` needs a no-arg constructor and mutable `var` properties and
 * cannot serialize a Kotlin `data class`/`value class`/enum-in-record — so the domain types stay inert
 * (ARCHITECTURE §3.1) and this DTO absorbs the serialization concessions.
 */
class PersistedEnvironmentConfig {
    var name: String = ""
    var command: String = ""

    /** One of [AgentEnvironment]; tolerated back to [AgentEnvironment.CUSTOM] on unknown input. */
    var environment: String = AgentEnvironment.LOCAL.name
    var capabilityTurnStarted: Boolean = false
    var capabilityNeedsInput: Boolean = false
}

/** Domain → DTO: flattens [EnvironmentConfig.environment] and [EnvironmentConfig.capabilities] into `var`s. */
fun EnvironmentConfig.toPersisted(): PersistedEnvironmentConfig = PersistedEnvironmentConfig().also { dto ->
    dto.name = name
    dto.command = command
    dto.environment = environment.name
    dto.capabilityTurnStarted = capabilities.turnStarted
    dto.capabilityNeedsInput = capabilities.needsInput
}

/**
 * DTO → domain, tolerating degenerate input (mirrors [PersistedSession.toDomain]): an unrecognized
 * [PersistedEnvironmentConfig.environment] falls back to [AgentEnvironment.CUSTOM] rather than throwing, so a
 * schema-drifted record still loads instead of aborting the whole restore.
 */
fun PersistedEnvironmentConfig.toDomain(): EnvironmentConfig = EnvironmentConfig(
    name = name,
    command = command,
    environment = runCatching { AgentEnvironment.valueOf(environment) }.getOrDefault(AgentEnvironment.CUSTOM),
    capabilities = SessionCapabilities(
        turnStarted = capabilityTurnStarted,
        needsInput = capabilityNeedsInput,
    ),
)
