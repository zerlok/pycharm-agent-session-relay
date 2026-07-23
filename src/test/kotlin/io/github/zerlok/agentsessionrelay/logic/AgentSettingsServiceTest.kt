package io.github.zerlok.agentsessionrelay.logic

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities

/**
 * Real-platform test of [AgentSettingsService] (task 3.1/3.3): it mediates reads/writes to the real
 * app-level [io.github.zerlok.agentsessionrelay.storage.PersistentAgentSettingsStorage], exposing the
 * start-script configs and the two per-event sound toggles as the only API the Settings view and the
 * notifier's sound gate see. The app-level store is shared across the run, so each test restores the prior
 * state.
 */
class AgentSettingsServiceTest : BasePlatformTestCase() {

    private lateinit var service: AgentSettingsService
    private lateinit var savedConfigs: List<EnvironmentConfig>
    private var savedTurn = true
    private var savedNeeds = true

    override fun setUp() {
        super.setUp()
        service = AgentSettingsService.getInstance()
        savedConfigs = service.configs()
        savedTurn = service.isTurnCompletedSoundEnabled()
        savedNeeds = service.isNeedsInputSoundEnabled()
    }

    override fun tearDown() {
        try {
            service.setConfigs(savedConfigs)
            service.setTurnCompletedSoundEnabled(savedTurn)
            service.setNeedsInputSoundEnabled(savedNeeds)
        } finally {
            super.tearDown()
        }
    }

    fun `test setConfigs then configs reads them back`() {
        val configs = listOf(
            EnvironmentConfig("local", "claude"),
            EnvironmentConfig("docker", "docker run img", AgentEnvironment.DOCKER, SessionCapabilities(needsInput = true)),
        )
        service.setConfigs(configs)
        assertEquals(configs, service.configs())
    }

    fun `test sound toggles round-trip through the service`() {
        service.setTurnCompletedSoundEnabled(false)
        service.setNeedsInputSoundEnabled(true)
        assertFalse(service.isTurnCompletedSoundEnabled())
        assertTrue(service.isNeedsInputSoundEnabled())
    }
}
