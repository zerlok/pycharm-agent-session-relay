package io.github.zerlok.agentsessionrelay.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.EnvironmentConfig
import io.github.zerlok.agentsessionrelay.domain.RelayEnvVars
import io.github.zerlok.agentsessionrelay.domain.SessionCapabilities
import io.github.zerlok.agentsessionrelay.domain.SessionState
import io.github.zerlok.agentsessionrelay.gateway.RelayGatewayEndpoint
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService

/**
 * Real-platform test of [AgentLaunchService] (task 3.2). It covers the pure planning
 * ([AgentLaunchService.planLaunch] — env-var map + placeholder substitution + project tagging) and the
 * in-process registration effect on the app-level registry (registering the planned session, the exact call
 * [AgentLaunchService.launch] makes). The terminal open is deliberately **not** exercised: it lives behind
 * the [AgentSessionsTerminalHost] seam (implemented by the tool window, task 4) precisely so no display /
 * Terminal plugin is needed here (agent-environments spec "Launch a session into a terminal").
 */
class AgentLaunchServiceTest : BasePlatformTestCase() {

    private lateinit var service: AgentLaunchService

    private val config = EnvironmentConfig(
        name = "Claude Code",
        command = "cd \${AGENT_SESSION_RELAY_PROJECT_DIR}; ID=\${AGENT_SESSION_RELAY_ID} claude",
        environment = AgentEnvironment.DOCKER,
        capabilities = SessionCapabilities(turnStarted = true, needsInput = true),
    )

    override fun setUp() {
        super.setUp()
        service = AgentLaunchService.getInstance(project)
        // The app-level registry is shared across the run; start from a clean slate.
        SessionRegistryService.getInstance().sessions().forEach { SessionRegistryService.getInstance().dismiss(it.id) }
    }

    override fun tearDown() {
        try {
            SessionRegistryService.getInstance().sessions().forEach { SessionRegistryService.getInstance().dismiss(it.id) }
        } finally {
            super.tearDown()
        }
    }

    fun `test planLaunch exports exactly the four env vars`() {
        val env = service.planLaunch(config).env
        assertEquals(
            setOf(RelayEnvVars.URL, RelayEnvVars.ID, RelayEnvVars.PORT, RelayEnvVars.PROJECT_DIR),
            env.keys,
        )
    }

    fun `test planLaunch resolves the loopback endpoint and project dir`() {
        val plan = service.planLaunch(config)
        assertEquals(RelayGatewayEndpoint.baseUrl(), plan.env[RelayEnvVars.URL])
        assertEquals(RelayGatewayEndpoint.port().toString(), plan.env[RelayEnvVars.PORT])
        assertEquals(project.basePath, plan.env[RelayEnvVars.PROJECT_DIR])
        assertEquals(plan.session.id.value, plan.env[RelayEnvVars.ID])
    }

    fun `test planLaunch substitutes placeholders in the command`() {
        val plan = service.planLaunch(config)
        assertEquals("cd ${project.basePath}; ID=${plan.session.id.value} claude", plan.command)
    }

    fun `test planLaunch tags the session with the project and config`() {
        val session = service.planLaunch(config).session
        assertEquals("Claude Code", session.agentLabel)
        assertEquals("Claude Code", session.startScriptRef)
        assertEquals(AgentEnvironment.DOCKER, session.environment)
        assertEquals(project.basePath, session.projectBasePath)
        assertEquals(SessionCapabilities(turnStarted = true, needsInput = true), session.capabilities)
        assertEquals(SessionState.Registered, session.state)
    }

    fun `test planLaunch mints a unique id per call`() {
        assertNotSame(service.planLaunch(config).session.id, service.planLaunch(config).session.id)
    }

    fun `test planLaunch has no side effect on the registry`() {
        service.planLaunch(config)
        assertTrue(SessionRegistryService.getInstance().sessions().isEmpty())
    }

    fun `test registering a planned session lands it in the registry tagged with the project`() {
        // The exact in-process registration effect launch() produces (design D7), without the terminal open.
        val plan = service.planLaunch(config)
        SessionRegistryService.getInstance().register(plan.session)

        val registered = SessionRegistryService.getInstance().session(plan.session.id)
        assertNotNull(registered)
        assertEquals(project.basePath, registered!!.projectBasePath)
        assertEquals("Claude Code", registered.agentLabel)
        assertEquals(SessionState.Registered, registered.state)
    }

    fun `test launch registers the session and hosts its terminal behind the seam`() {
        // The exact effect the Agent Sessions tool-window "Launch Session…" action produces (design D1):
        // launch() registers the session in-process and opens its terminal via AgentSessionsTerminalHost.
        // The seam is replaced with a recording double so no display / Terminal plugin is needed here.
        val host = RecordingTerminalHost()
        project.replaceService(AgentSessionsTerminalHost::class.java, host, testRootDisposable)

        val session = service.launch(config)

        val registered = SessionRegistryService.getInstance().session(session.id)
        assertNotNull(registered)
        assertEquals(SessionState.Registered, registered!!.state)
        assertEquals(project.basePath, registered.projectBasePath)
        // The terminal was hosted for the same planned session, with exactly the four-var env contract.
        assertEquals(session.id, host.openedSession?.id)
        assertEquals(
            setOf(RelayEnvVars.URL, RelayEnvVars.ID, RelayEnvVars.PORT, RelayEnvVars.PROJECT_DIR),
            host.openedEnv?.keys,
        )
    }

    /** A recording [AgentSessionsTerminalHost] double so [AgentLaunchService.launch] runs without a display. */
    private class RecordingTerminalHost : AgentSessionsTerminalHost {
        var openedSession: AgentSession? = null
        var openedEnv: Map<String, String>? = null

        override fun openSessionTerminal(session: AgentSession, command: String, env: Map<String, String>) {
            openedSession = session
            openedEnv = env
        }
    }
}
