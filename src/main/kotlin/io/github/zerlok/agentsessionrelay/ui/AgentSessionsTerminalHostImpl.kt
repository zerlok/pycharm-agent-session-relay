package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionId
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.IOException
import javax.swing.SwingUtilities

/**
 * The [AgentSessionsTerminalHost] implementation (tasks 4.2 / R7). Registered as the project service under
 * the [AgentSessionsTerminalHost] interface (plugin.xml `<projectService serviceInterface=…
 * serviceImplementation=…/>`), so the launch service depends only on the seam.
 *
 * **R7 decision (validated against PyCharm Community 2024.2.5):** dedicated in-tool-window terminal hosting
 * would require constructing and embedding a terminal widget's component inside the Agent Sessions panel,
 * which cannot be exercised here (no display). Per design R7 this falls back to the **platform Terminal tool
 * window**: `TerminalToolWindowManager.createLocalShellWidget(workingDir, tabName)` opens a real tab in the
 * agent's [AgentSession.projectBasePath] and returns a [ShellTerminalWidget]; the env contract is exported
 * and the command run via [ShellTerminalWidget.executeCommand] ([AgentTerminalCommand.compose]).
 * [revealSessionTerminal] re-selects that tab. The tab, not a panel embed, is the session's terminal.
 *
 * Threading (ARCHITECTURE §5.3): both methods touch the platform terminal + tool-window UI and MUST be
 * called on the EDT; the launch service and the tool-window activation that call them already are.
 *
 * Registered in `plugin.xml` under the [AgentSessionsTerminalHost] interface (`<projectService
 * serviceInterface=… serviceImplementation=…/>`), so it carries **no** `@Service` annotation — a
 * plugin.xml-registered class must not also be a light `@Service` (the two registration mechanisms are
 * mutually exclusive; the interface lookup in [AgentSessionsTerminalHost.getInstance] resolves via the XML
 * mapping).
 */
class AgentSessionsTerminalHostImpl(private val project: Project) : AgentSessionsTerminalHost {

    // Live terminal widget per session, so a later activation can re-reveal the same tab. Not persisted:
    // restored sessions have no live terminal (session-registry spec: terminal revival is out of scope).
    private val widgets = mutableMapOf<SessionId, ShellTerminalWidget>()

    // createLocalShellWidget is @Deprecated in 2024.2.5 (favouring the TerminalWidget-returning
    // createShellWidget) but is present and returns the ShellTerminalWidget whose executeCommand we need;
    // the successor adds an asShellJediTermWidget hop for no gain behind this seam. Revisit at the next
    // platform bump (R7).
    @Suppress("DEPRECATION")
    override fun openSessionTerminal(session: AgentSession, command: String, env: Map<String, String>) {
        val manager = TerminalToolWindowManager.getInstance(project)
        val workingDir = session.projectBasePath.ifBlank { null }
        val widget = manager.createLocalShellWidget(workingDir, session.agentLabel)
        widgets[session.id] = widget
        try {
            widget.executeCommand(AgentTerminalCommand.compose(env, command))
        } catch (e: IOException) {
            // The terminal opened but the command could not be typed in; the user still has a live shell
            // with the env exported to run it manually. Do not throw into the caller (the launch action).
            LOG.warn("Failed to run launch command in terminal for session ${session.id.value}", e)
        }
    }

    override fun revealSessionTerminal(session: AgentSession) {
        val widget = widgets[session.id] ?: return
        val toolWindow = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
        toolWindow.activate {
            val contentManager = toolWindow.contentManager
            contentManager.contents
                .firstOrNull { SwingUtilities.isDescendingFrom(widget.component, it.component) }
                ?.let(contentManager::setSelectedContent)
            widget.component.requestFocusInWindow()
        }
    }

    companion object {
        private val LOG = logger<AgentSessionsTerminalHostImpl>()
    }
}
