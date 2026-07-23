package io.github.zerlok.agentsessionrelay.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.SessionState
import io.github.zerlok.agentsessionrelay.logic.AgentSettingsService
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryListener
import java.awt.Toolkit

/**
 * Presentation-layer subscriber on the app-level [SessionRegistryListener] seam (task 5.1/5.2, design D9):
 * turns a session's state change into a user-facing IDE notification and an optional beep. It holds no storage
 * handle — it reacts to the topic and re-reads only the sound toggles via [AgentSettingsService] (single
 * source of truth, ARCHITECTURE §3.1), mirroring [ReviewBatchNotifier] but at project scope over the
 * application bus (the registry outlives projects, so the topic is `@Topic.AppLevel`).
 *
 * Project-scoped (design D9): the registry is application-wide but this notifier fires only for sessions
 * launched from **its own** project ([AgentSession.projectBasePath] == [Project.getBasePath]); a foreign
 * project's events stay silent here. Callbacks fire on the EDT (ARCHITECTURE §5.3), so the [lastState] map is
 * single-threaded — no lock.
 *
 * Decisions are pure ([AgentSessionNotifications]): the seam carries the *resulting* [SessionState], not the
 * raw event, so a completed turn is recovered from the (previous, next) state pair, and the beep is gated by a
 * pure toggle decision with the actual `Toolkit.beep()` behind it (so tests never touch audio). Dismissal is
 * local-only — nothing is ever sent back to the agent (agent-notifications spec; design D8).
 */
@Service(Service.Level.PROJECT)
class AgentSessionNotifier(private val project: Project) : SessionRegistryListener, Disposable {

    // Last state seen per session, so a →idle transition can be told apart from the launch-time
    // session.started (both land on Idle; design D5). EDT-confined (callbacks run on the EDT), so unlocked.
    private val lastState = mutableMapOf<SessionId, SessionState>()

    init {
        // App-level topic (design D9): subscribe on the application message bus, parented to this service so
        // it disconnects when the project (and thus the service) is disposed.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SessionRegistryListener.TOPIC, this)
    }

    override fun sessionRegistered(session: AgentSession) {
        if (belongsHere(session)) lastState[session.id] = session.state
    }

    override fun sessionUpdated(session: AgentSession) {
        if (!belongsHere(session)) return
        val previous = lastState.put(session.id, session.state)
        val notification = AgentSessionNotifications.classify(previous, session.state) ?: return
        raise(session, notification)
    }

    override fun sessionRemoved(session: AgentSession) {
        lastState.remove(session.id)
    }

    override fun dispose() {
        // MessageBus connection was parented to `this`; it disconnects with the service.
    }

    /** Only sessions launched from this project notify here (design D9); a null base path matches nothing. */
    private fun belongsHere(session: AgentSession): Boolean = session.projectBasePath == project.basePath

    private fun raise(session: AgentSession, notification: SessionNotification) {
        val env = AgentSessionRenderModel.environmentBadge(session.environment)
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
        val ide = when (notification) {
            SessionNotification.TURN_COMPLETED -> group.createNotification(
                "Relay: agent turn completed",
                "${session.agentLabel} ($env) finished its turn",
                NotificationType.INFORMATION,
            )
            // Higher urgency than a completed turn: a distinct type (WARNING) and title (spec).
            SessionNotification.NEEDS_INPUT -> group.createNotification(
                "Relay: agent needs your input",
                "${session.agentLabel} ($env) is waiting: ${needsInputKindText(session)}",
                NotificationType.WARNING,
            )
        }
        // Activating (clicking) opens the Agent Sessions tool window at that session (spec). Nothing is sent
        // back to the agent — the user answers in the agent's own terminal (design D8).
        ide.addAction(NotificationAction.createSimple("Open Agent Sessions") { openSession(session) })
        ide.notify(project)

        if (AgentSessionNotifications.shouldBeep(notification, soundSettings())) {
            Toolkit.getDefaultToolkit().beep()
        }
    }

    /** Opens the Agent Sessions tool window (its id is the single source) and reveals [session]'s terminal. */
    private fun openSession(session: AgentSession) {
        ToolWindowManager.getInstance(project)
            .getToolWindow(AgentSessionsToolWindowFactory.TOOL_WINDOW_ID)
            ?.activate({ AgentSessionsTerminalHost.getInstance(project).revealSessionTerminal(session) }, true)
    }

    private fun needsInputKindText(session: AgentSession): String =
        (session.state as? SessionState.NeedsInput)?.kind?.name?.lowercase() ?: "your input"

    private fun soundSettings(): RelaySoundSettings = AgentSettingsService.getInstance().let {
        RelaySoundSettings(it.isTurnCompletedSoundEnabled(), it.isNeedsInputSoundEnabled())
    }

    companion object {
        // NOTE: must match the <notificationGroup id="…"> registered in META-INF/plugin.xml (the canonical
        // source); reuses the existing Relay group, exactly like ReviewBatchNotifier.
        private const val NOTIFICATION_GROUP_ID = "Agent Session Relay"
    }
}
