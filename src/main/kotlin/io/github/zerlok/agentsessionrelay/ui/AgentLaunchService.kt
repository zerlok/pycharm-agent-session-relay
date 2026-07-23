package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.domain.RelayEnvContract
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.gateway.RelayGatewayEndpoint
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService
import java.util.UUID

/**
 * What a launch resolves to before any side effect (task 3.2): the minted [session] to register, the
 * placeholder-substituted [command] to run, and the [env] contract map to export into its terminal. Pure
 * data so [AgentLaunchService.planLaunch] is fixture-free testable without opening a terminal.
 */
data class LaunchPlan(
    val session: AgentSession,
    val command: String,
    val env: Map<String, String>,
)

/**
 * Launches a start-script config into a session terminal (design D1, agent-environments spec). Project-level
 * because it needs the open [Project] to tag the session and resolve `AGENT_SESSION_RELAY_PROJECT_DIR`
 * (design D2). It orchestrates lower layers only: it mints the id + builds the [AgentSession] and env
 * contract ([RelayEnvContract], domain), registers in-process via [SessionRegistryService] (logic), resolves
 * the loopback endpoint via [RelayGatewayEndpoint] (the one canonical port source, design D4), and opens the
 * terminal behind the [AgentSessionsTerminalHost] seam.
 *
 * The plugin is **environment-blind past the export** (design D1): it ships no script/template/binary into
 * the agent's environment beyond the four env vars and establishes no reachability (tunnels, host-gateway) —
 * that is the command's / the user's connection tooling's job. It writes **no** agent config file (design
 * D12): all hook wiring lives in the user's [EnvironmentConfig.command].
 *
 * Threading (ARCHITECTURE §5.3): [launch] registers in the registry, so it must be called on the EDT (like
 * every registry mutation); it is invoked from a UI action (task 4/6), already on the EDT.
 */
@Service(Service.Level.PROJECT)
class AgentLaunchService(private val project: Project) {

    /**
     * Resolves [config] into a [LaunchPlan] with **no** side effect — no registration, no terminal. It mints
     * the opaque [SessionId], resolves the loopback endpoint + the project's base path, builds the
     * [RelayEnvContract], and substitutes the `${AGENT_SESSION_RELAY_*}` placeholders in the command. Split
     * out from [launch] so the env-var map, the substitution and the session tagging are unit-testable
     * without a display (the terminal open stays behind the seam).
     */
    fun planLaunch(config: EnvironmentConfig): LaunchPlan {
        val id = SessionId(UUID.randomUUID().toString())
        val projectDir = project.basePath.orEmpty()
        val contract = RelayEnvContract(
            url = RelayGatewayEndpoint.baseUrl(),
            id = id.value,
            port = RelayGatewayEndpoint.port(),
            projectDir = projectDir,
        )
        val session = AgentSession(
            id = id,
            agentLabel = config.name,
            environment = config.environment,
            projectBasePath = projectDir,
            startScriptRef = config.name,
            capabilities = config.capabilities,
        )
        return LaunchPlan(
            session = session,
            command = contract.substitute(config.command),
            env = contract.asEnvMap(),
        )
    }

    /**
     * Launches [config] for the open project (agent-environments spec: "Launch a session into a terminal"):
     * plans the launch, registers the session in-process (design D7), then hands the terminal open to the
     * Agent Sessions tool window via [AgentSessionsTerminalHost]. Returns the registered [AgentSession].
     */
    fun launch(config: EnvironmentConfig): AgentSession {
        val plan = planLaunch(config)
        SessionRegistryService.getInstance().register(plan.session)
        AgentSessionsTerminalHost.getInstance(project).openSessionTerminal(plan.session, plan.command, plan.env)
        return plan.session
    }

    companion object {
        fun getInstance(project: Project): AgentLaunchService = project.service()
    }
}
