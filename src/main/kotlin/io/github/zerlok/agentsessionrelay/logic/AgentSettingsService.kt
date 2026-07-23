package io.github.zerlok.agentsessionrelay.logic

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.storage.PersistentAgentSettingsStorage

/**
 * The logic layer for the Relay Settings state (ARCHITECTURE §3.1, design D10): the **only** API the Settings
 * view ([io.github.zerlok.agentsessionrelay.ui.RelayConfigurable], task 3.3) and consumers see, mediating
 * every read/write to [PersistentAgentSettingsStorage]. No Swing, no editor imports.
 *
 * App-level (design D9), like the registry it sits beside: start-script configs and settings outlive
 * projects. Unlike [SessionRegistryService] it publishes on no topic — settings are edited synchronously in
 * the Settings dialog, not live-broadcast to a view. The launch service (task 3.2) reads [configs] to offer
 * launchable configs; the notifier's sound gate (task 5.2) reads [isTurnCompletedSoundEnabled] /
 * [isNeedsInputSoundEnabled].
 *
 * Threading (ARCHITECTURE §5.3): the setters mutate app-level persisted state and run from the Settings
 * `apply()` on the EDT.
 */
@Service(Service.Level.APP)
class AgentSettingsService {

    // The one place the concrete backing is named; obtained as a service so the platform owns its
    // loadState/getState lifecycle (see PersistentAgentSettingsStorage for why).
    private val storage: PersistentAgentSettingsStorage get() = service()

    // -- Start-script configs (design D10) --

    fun configs(): List<EnvironmentConfig> = storage.configs()

    fun setConfigs(configs: List<EnvironmentConfig>) = storage.setConfigs(configs)

    // -- Per-event sound toggles (agent-notifications spec; both ON by default) --

    fun isTurnCompletedSoundEnabled(): Boolean = storage.turnCompletedSoundEnabled

    fun setTurnCompletedSoundEnabled(enabled: Boolean) {
        storage.turnCompletedSoundEnabled = enabled
    }

    fun isNeedsInputSoundEnabled(): Boolean = storage.needsInputSoundEnabled

    fun setNeedsInputSoundEnabled(enabled: Boolean) {
        storage.needsInputSoundEnabled = enabled
    }

    companion object {
        fun getInstance(): AgentSettingsService = service()
    }
}
