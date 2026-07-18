package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.AgentSession

/**
 * The seam by which the launch service (task 3.2) asks the **Agent Sessions tool window** (task 4) to host a
 * session's terminal, so this side depends on an interface, not on the tool-window UI. The tool window
 * provides the implementation and registers it as a project service under this interface (plugin.xml:
 * `<projectService serviceInterface="…ui.AgentSessionsTerminalHost" serviceImplementation="…"/>`), wired in
 * the tasks 4/6 phase.
 *
 * Kept behind this seam precisely so the terminal open is **not** exercised in the launch service's unit
 * tests (there is no display here): tests cover the pure parts ([AgentLaunchService.planLaunch] — env-var map
 * + placeholder substitution — and the in-process registration effect), while [openSessionTerminal] is stubbed
 * by the real tool window at runtime.
 */
interface AgentSessionsTerminalHost {

    /**
     * Opens a terminal tab for [session] in the Agent Sessions tool window, exports [env] (exactly the four
     * `AGENT_SESSION_RELAY_*` vars — agent-environments spec: nothing else crosses to the agent) into that
     * terminal, and runs the already-placeholder-substituted [command] there. The working directory is the
     * session's [AgentSession.projectBasePath]. The plugin ships no script/template/binary into the agent's
     * environment beyond [env] and establishes no reachability itself (design D1).
     */
    fun openSessionTerminal(session: AgentSession, command: String, env: Map<String, String>)

    /**
     * Reveals [session]'s already-open terminal (session-registry spec: "Selecting or activating a session
     * SHALL reveal its terminal") — activates the hosting tool window and selects its tab. A no-op when the
     * session has no live terminal (e.g. a restored session, whose terminal revival is out of scope for this
     * change). Default no-op so the launch side (task 3.2) — which only calls [openSessionTerminal] — is
     * unaffected; the Agent Sessions tool window (task 4.1) drives this on entry activation.
     */
    fun revealSessionTerminal(session: AgentSession) {}

    companion object {
        fun getInstance(project: Project): AgentSessionsTerminalHost = project.service()
    }
}
