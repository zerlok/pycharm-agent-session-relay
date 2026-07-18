package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.logic.AgentSettingsService
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryListener
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DateFormat
import java.util.Date
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The dedicated **Agent Sessions** tool window (task 4.1, design D9/D11). A pure view: it holds no storage
 * handle, subscribes to the app-level [SessionRegistryListener] topic, and rebuilds from
 * [SessionRegistryService] on every event (single source of truth, ARCHITECTURE §3.1). Because the registry
 * is application-level (design D9) it subscribes on the **application** message bus but filters to this
 * project's sessions ([AgentSessionRenderModel.forProject]).
 */
class AgentSessionsToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AgentSessionsPanel(project, toolWindow.disposable)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        // Must match the <toolWindow id="…"> in META-INF/plugin.xml.
        const val TOOL_WINDOW_ID = "Agent Sessions"
    }
}

/** The tool-window content: a flat tree over this project's sessions plus a toolbar, kept in sync with the registry. */
private class AgentSessionsPanel(
    private val project: Project,
    parent: Disposable,
) : SimpleToolWindowPanel(true, true), SessionRegistryListener {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = false
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = SessionCellRenderer()
    }

    init {
        toolbar = buildToolbar().component
        setContent(JBScrollPane(tree))

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Activating an entry reveals its terminal (session-registry spec).
                if (e.clickCount == 2) selectedSession()?.let(::revealTerminal)
            }
        })

        // App-level topic (design D9): subscribe on the application message bus, parented to the tool
        // window disposable so it disconnects with the window. Callbacks fire on the EDT (§5.3).
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(SessionRegistryListener.TOPIC, this)
        rebuild()
    }

    // -- Registry events: rebuild from the service (single source of truth), filtered to this project. --

    override fun sessionRegistered(session: AgentSession) = rebuild()

    override fun sessionUpdated(session: AgentSession) = rebuild()

    override fun sessionRemoved(session: AgentSession) = rebuild()

    private fun buildToolbar() = ActionManager.getInstance().createActionToolbar(
        "RelayAgentSessions",
        DefaultActionGroup(LaunchSessionAction(), DismissSessionAction()),
        true,
    ).also { it.targetComponent = tree }

    private fun rebuild() {
        root.removeAllChildren()
        for (session in AgentSessionRenderModel.forProject(SessionRegistryService.getInstance().sessions(), project.basePath)) {
            root.add(DefaultMutableTreeNode(session))
        }
        treeModel.reload()
        expandAll()
    }

    private fun expandAll() {
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }

    private fun selectedSession(): AgentSession? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? AgentSession

    private fun revealTerminal(session: AgentSession) =
        AgentSessionsTerminalHost.getInstance(project).revealSessionTerminal(session)

    /**
     * The user-reachable launch affordance (agent-environments spec "Launch a session into a terminal",
     * design D1): offers the app-level start-script configs ([AgentSettingsService.configs]) and launches
     * the chosen one via [AgentLaunchService], which mints + registers the session and opens its terminal.
     * The chooser callback fires on the EDT, satisfying [AgentLaunchService.launch]'s registry-mutation
     * EDT contract (ARCHITECTURE §5.3).
     */
    private inner class LaunchSessionAction :
        AnAction("Launch Session…", "Launch a start-script config into a new session terminal", AllIcons.General.Add),
        DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val configs = AgentSettingsService.getInstance().configs()
            if (configs.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "No start-script configs yet. Add one in Settings | Tools | Agent Session Relay.",
                    "Launch Agent Session",
                )
                return
            }
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(configs)
                .setTitle("Launch Agent Session")
                .setRenderer(SimpleListCellRenderer.create("") { it.name })
                .setItemChosenCallback { AgentLaunchService.getInstance(project).launch(it) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }

    private inner class DismissSessionAction :
        AnAction("Dismiss Session", "Drop the selected session from the registry (local only)", AllIcons.General.Remove),
        DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            // Any session is dismissable, locally only (session-registry spec; design D6).
            selectedSession()?.let { SessionRegistryService.getInstance().dismiss(it.id) }
        }
    }

    companion object {
        private val TIME_FORMAT: DateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)

        /** State badge text, capability-honest via [AgentSessionRenderModel] (design D11). */
        private fun stateText(session: AgentSession): String = when (AgentSessionRenderModel.displayState(session)) {
            SessionDisplayState.REGISTERED -> "registered"
            SessionDisplayState.WORKING -> "working"
            SessionDisplayState.IDLE -> "idle"
            SessionDisplayState.NEEDS_INPUT ->
                "needs input: ${AgentSessionRenderModel.needsInputKind(session)?.name?.lowercase() ?: "?"}"
            SessionDisplayState.ENDED -> "ended"
            SessionDisplayState.UNKNOWN -> "unknown"
        }

        private fun stateIcon(session: AgentSession) = when (AgentSessionRenderModel.displayState(session)) {
            SessionDisplayState.REGISTERED -> AllIcons.General.Information
            SessionDisplayState.WORKING -> AllIcons.Actions.Execute
            SessionDisplayState.IDLE -> AllIcons.General.InspectionsOK
            SessionDisplayState.NEEDS_INPUT -> AllIcons.General.Warning
            SessionDisplayState.ENDED -> AllIcons.Actions.Exit
            SessionDisplayState.UNKNOWN -> AllIcons.General.QuestionDialog
        }

        /** Last-event time; the residual signal a silent session carries (design D11). */
        private fun lastEventText(session: AgentSession): String =
            session.lastEventAt?.let { "last event ${TIME_FORMAT.format(Date(it))}" } ?: "no events yet"
    }

    private class SessionCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val session = (value as? DefaultMutableTreeNode)?.userObject as? AgentSession ?: return
            icon = stateIcon(session)
            append(session.agentLabel)
            append("  [${AgentSessionRenderModel.environmentBadge(session.environment)}]  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(stateText(session))
            append("   ${lastEventText(session)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
