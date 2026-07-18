package io.github.zerlok.agentsessionrelay.logic

import com.intellij.util.messages.Topic
import io.github.zerlok.agentsessionrelay.domain.AgentSession

/**
 * The seam between logic and presentation (ARCHITECTURE §3.1), mirroring [ReviewBatchListener] but at
 * **application** level (design D9): the registry outlives projects, so this is a `@Topic.AppLevel` topic
 * published on the application `MessageBus`. The tool window/notifier (task 4/5) subscribe and filter to
 * their own project; they never hold a storage handle — they re-query [SessionRegistryService]. Callbacks
 * fire on the EDT (ARCHITECTURE §5.3).
 *
 * Callbacks have default no-op bodies so a subscriber implements only what it cares about.
 */
interface SessionRegistryListener {

    /** A session was registered in-process at launch (design D7). */
    fun sessionRegistered(session: AgentSession) {}

    /** A session's live state changed in place (same id) — an event was applied (design D5). */
    fun sessionUpdated(session: AgentSession) {}

    /** A session was dismissed — dropped from the registry and its persistence, no side effects (D6). */
    fun sessionRemoved(session: AgentSession) {}

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<SessionRegistryListener> =
            Topic.create("Relay agent sessions", SessionRegistryListener::class.java)
    }
}
