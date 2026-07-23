package io.github.zerlok.agentsessionrelay.storage

import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [EnvironmentConfig] ↔ [PersistedEnvironmentConfig] DTO mapping (task 3.1): a domain →
 * DTO → domain round-trip preserves every field, and a schema-drifted environment tolerates back to
 * [AgentEnvironment.CUSTOM] rather than throwing (mirrors [PersistedSessionTest]).
 */
class PersistedEnvironmentConfigTest {

    @Test
    fun `round-trips every field`() {
        val config = EnvironmentConfig(
            name = "Claude Code",
            command = "curl \${AGENT_SESSION_RELAY_URL}; claude",
            environment = AgentEnvironment.DOCKER,
            capabilities = SessionCapabilities(turnStarted = true, needsInput = true),
        )
        assertEquals(config, config.toPersisted().toDomain())
    }

    @Test
    fun `default config round-trips`() {
        val config = EnvironmentConfig(name = "local", command = "claude")
        val restored = config.toPersisted().toDomain()
        assertEquals(AgentEnvironment.LOCAL, restored.environment)
        assertEquals(SessionCapabilities(), restored.capabilities)
        assertEquals(config, restored)
    }

    @Test
    fun `unknown environment falls back to CUSTOM`() {
        val dto = PersistedEnvironmentConfig().apply {
            name = "x"
            command = "y"
            environment = "MARS"
        }
        assertEquals(AgentEnvironment.CUSTOM, dto.toDomain().environment)
    }

    @Test
    fun `capabilities flags survive the round-trip independently`() {
        val onlyNeedsInput = EnvironmentConfig(
            name = "n",
            command = "c",
            capabilities = SessionCapabilities(turnStarted = false, needsInput = true),
        )
        assertEquals(onlyNeedsInput.capabilities, onlyNeedsInput.toPersisted().toDomain().capabilities)
    }
}
