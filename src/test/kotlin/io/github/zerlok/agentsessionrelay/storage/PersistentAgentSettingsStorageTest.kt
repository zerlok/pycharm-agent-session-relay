package io.github.zerlok.agentsessionrelay.storage

import com.intellij.openapi.components.State
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PersistentAgentSettingsStorage] (task 3.1/3.3). Three concerns:
 *  - **configs round-trip** — getState → loadState preserves every start-script config unchanged;
 *  - **toggle round-trip + defaults** — both sound toggles default ON, and their values survive a save/load;
 *  - a **reflective guard** that `@State` (with the app storage file + EDT pin) is present, since that
 *    platform wiring can't be reached in a unit test (mirrors [PersistentSessionRegistryStorageTest]).
 */
class PersistentAgentSettingsStorageTest {

    private fun config(name: String) = EnvironmentConfig(
        name = name,
        command = "run \${AGENT_SESSION_RELAY_ID}",
        environment = AgentEnvironment.SSH,
        capabilities = SessionCapabilities(turnStarted = true),
    )

    // -- Config round-trip --

    @Test
    fun `configs round-trip through getState then loadState`() {
        val source = PersistentAgentSettingsStorage()
        source.setConfigs(listOf(config("a"), config("b")))

        val restored = PersistentAgentSettingsStorage()
        restored.loadState(source.getState())

        assertEquals(listOf(config("a"), config("b")), restored.configs())
    }

    @Test
    fun `an empty settings store round-trips as empty with default toggles`() {
        val restored = PersistentAgentSettingsStorage()
        restored.loadState(PersistentAgentSettingsStorage().getState())
        assertTrue(restored.configs().isEmpty())
        assertTrue(restored.turnCompletedSoundEnabled)
        assertTrue(restored.needsInputSoundEnabled)
    }

    // -- Toggle defaults + round-trip --

    @Test
    fun `both sound toggles default ON`() {
        val storage = PersistentAgentSettingsStorage()
        assertTrue("turn.completed sound defaults ON", storage.turnCompletedSoundEnabled)
        assertTrue("needs.input sound defaults ON", storage.needsInputSoundEnabled)
    }

    @Test
    fun `toggle values survive a save and load`() {
        val source = PersistentAgentSettingsStorage()
        source.turnCompletedSoundEnabled = false
        source.needsInputSoundEnabled = true

        val restored = PersistentAgentSettingsStorage()
        restored.loadState(source.getState())

        assertEquals(false, restored.turnCompletedSoundEnabled)
        assertEquals(true, restored.needsInputSoundEnabled)
    }

    @Test
    fun `loadState replaces any prior in-memory contents`() {
        val storage = PersistentAgentSettingsStorage()
        storage.setConfigs(listOf(config("stale")))
        storage.loadState(PersistentAgentSettingsStorage.State())
        assertTrue(storage.configs().isEmpty())
    }

    // -- Registration guard (mirrors PersistentSessionRegistryStorageTest) --

    @Test
    fun `is registered with a State pointing at the app storage file`() {
        val state = PersistentAgentSettingsStorage::class.java.getAnnotation(State::class.java)
        assertNotNull("@State annotation is required for the platform to persist state", state)
        assertTrue("@State.name must be set", state.name.isNotBlank())
        assertEquals(1, state.storages.size)
        assertEquals("relayAgentEnvironments.xml", state.storages[0].value)
        assertTrue("getState must be pinned to the EDT to avoid the save-vs-mutation race", state.getStateRequiresEdt)
    }
}
