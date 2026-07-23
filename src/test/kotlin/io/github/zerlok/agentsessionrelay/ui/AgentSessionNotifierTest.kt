package io.github.zerlok.agentsessionrelay.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.AgentEnvironment
import io.github.zerlok.agentsessionrelay.domain.AgentEvent
import io.github.zerlok.agentsessionrelay.domain.AgentSession
import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService

/**
 * Real-platform test of [AgentSessionNotifier] (task 5.1): it subscribes to the real app-level
 * [io.github.zerlok.agentsessionrelay.logic.SessionRegistryListener] topic, and driving the registry through a
 * lifecycle raises real IDE notifications on the Relay group, captured here via [Notifications.TOPIC]. Verifies
 * the agent-notifications spec at the integration level: turn-completion identifies the session, needs-input is
 * higher urgency and states the kind, a foreign project stays silent, and the click-through action is wired.
 *
 * The pure classification/sound-gate decisions are covered separately in [AgentSessionNotificationsTest]; the
 * beep runs (headless AWT no-ops) but is not asserted here — audio stays out of tests.
 */
class AgentSessionNotifierTest : BasePlatformTestCase() {

    private lateinit var registry: SessionRegistryService
    private val received = mutableListOf<Notification>()

    private fun session(id: String, basePath: String) = AgentSession(
        id = SessionId(id),
        agentLabel = "Claude Code",
        environment = AgentEnvironment.LOCAL,
        projectBasePath = basePath,
        startScriptRef = "cfg",
    )

    override fun setUp() {
        super.setUp()
        registry = SessionRegistryService.getInstance()
        // The app-level registry is shared across the run — start each test from a clean slate.
        registry.sessions().forEach { registry.dismiss(it.id) }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == RELAY_GROUP_ID) received += notification
            }
        })
        // Force-create the project service so its init subscribes to the registry topic.
        project.service<AgentSessionNotifier>()
    }

    override fun tearDown() {
        try {
            registry.sessions().forEach { registry.dismiss(it.id) }
        } finally {
            super.tearDown()
        }
    }

    fun `test turn completion raises one info notification identifying the session`() {
        registry.register(session("1", project.basePath!!))
        registry.applyEvent(SessionId("1"), AgentEvent.TurnStarted)

        registry.applyEvent(SessionId("1"), AgentEvent.TurnCompleted)

        val notification = received.single()
        assertEquals(NotificationType.INFORMATION, notification.type)
        assertTrue(notification.content.contains("Claude Code"))
        assertTrue(notification.content.contains("local"))
        // Click-through to the Agent Sessions tool window is wired as a notification action.
        assertTrue(notification.actions.isNotEmpty())
    }

    fun `test needs-input notifies with the kind at higher urgency`() {
        registry.register(session("1", project.basePath!!))
        registry.applyEvent(SessionId("1"), AgentEvent.TurnStarted)

        registry.applyEvent(SessionId("1"), AgentEvent.NeedsInput(NeedsInputKind.PERMISSION))

        val notification = received.single()
        // Distinct type/title from a completed turn (spec: "visually distinct ... higher urgency").
        assertEquals(NotificationType.WARNING, notification.type)
        assertTrue(notification.content.contains("permission"))
    }

    fun `test a session launched from another project stays silent`() {
        registry.register(session("F", "/some/other/project"))
        registry.applyEvent(SessionId("F"), AgentEvent.TurnStarted)

        registry.applyEvent(SessionId("F"), AgentEvent.TurnCompleted)
        registry.applyEvent(SessionId("F"), AgentEvent.NeedsInput(NeedsInputKind.QUESTION))

        assertTrue(received.isEmpty())
    }

    fun `test launch-time session start does not notify a completed turn`() {
        registry.register(session("1", project.basePath!!))

        // session.started lands on Idle just like turn.completed (design D5); it must not notify at launch.
        registry.applyEvent(SessionId("1"), AgentEvent.SessionStarted)

        assertTrue(received.isEmpty())
    }

    companion object {
        private const val RELAY_GROUP_ID = "Agent Session Relay"
    }
}
