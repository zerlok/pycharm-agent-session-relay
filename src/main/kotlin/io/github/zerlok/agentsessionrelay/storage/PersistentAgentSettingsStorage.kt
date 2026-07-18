package io.github.zerlok.agentsessionrelay.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig

/**
 * The durable backing for the Relay Settings page (design D10, agent-environments + agent-notifications
 * specs): the app-level home for the named start-script [configs] and the two per-event sound toggles. Like
 * [PersistentSessionRegistryStorage] it is the app-level deviation of design D9 — configs and settings
 * outlive projects — so this is an `APP`-level `@Service` written to its own app-config file
 * (`relayAgentEnvironments.xml`), not the project-scoped `workspace.xml`.
 *
 * Storage stays dumb (ARCHITECTURE §3.1): it holds inert data and maps to the flat [PersistedEnvironmentConfig]
 * DTO only at the persistence boundary; the logic layer
 * ([io.github.zerlok.agentsessionrelay.logic.AgentSettingsService]) mediates every read/write and is the only
 * API the Settings view (task 3.3) and consumers (the notifier's sound gate, task 5.2) see.
 *
 * Persistence gotchas (unit tests can't catch these — see the reflective guard in the test):
 *  - `@State` is REQUIRED; a bare `@Storage` on the class is inert, so state would silently never persist.
 *  - `getStateRequiresEdt = true` pins the otherwise-background [getState] to the EDT, where the Settings
 *    `apply()` mutation already runs (ARCHITECTURE §5.3), so a save can't iterate mid-mutation.
 *
 * Both sound toggles default **on** (agent-notifications spec: "both toggles ON by default") via the field
 * and DTO defaults, so a fresh install with no persisted state plays both sounds.
 */
@Service(Service.Level.APP)
@State(
    name = "RelayAgentSettings",
    storages = [Storage("relayAgentEnvironments.xml")],
    getStateRequiresEdt = true,
)
class PersistentAgentSettingsStorage :
    PersistentStateComponent<PersistentAgentSettingsStorage.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var environments: MutableList<PersistedEnvironmentConfig> = mutableListOf()

        /** Play a short sound on `turn.completed`; ON by default (agent-notifications spec). */
        var turnCompletedSound: Boolean = true

        /** Play a short sound on `needs.input`; ON by default (agent-notifications spec). */
        var needsInputSound: Boolean = true
    }

    // In-memory inert domain state; mapped to/from the DTO only at the persistence boundary.
    private var configs: MutableList<EnvironmentConfig> = mutableListOf()
    var turnCompletedSoundEnabled: Boolean = true
    var needsInputSoundEnabled: Boolean = true

    // -- PersistentStateComponent --

    override fun getState(): State = State().also { state ->
        state.environments = configs.mapTo(mutableListOf()) { it.toPersisted() }
        state.turnCompletedSound = turnCompletedSoundEnabled
        state.needsInputSound = needsInputSoundEnabled
    }

    override fun loadState(state: State) {
        configs = state.environments.mapTo(mutableListOf()) { it.toDomain() }
        turnCompletedSoundEnabled = state.turnCompletedSound
        needsInputSoundEnabled = state.needsInputSound
    }

    // -- Config CRUD (the whole list is replaced wholesale from the Settings editor's working copy) --

    fun configs(): List<EnvironmentConfig> = configs.toList()

    fun setConfigs(value: List<EnvironmentConfig>) {
        configs = value.toMutableList()
    }
}
