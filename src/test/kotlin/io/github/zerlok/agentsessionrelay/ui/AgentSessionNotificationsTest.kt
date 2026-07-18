package io.github.zerlok.agentsessionrelay.ui

import io.github.zerlok.agentsessionrelay.domain.NeedsInputKind
import io.github.zerlok.agentsessionrelay.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests of the session notifier's two decisions (task 5.1/5.2) — no platform fixture, since
 * [AgentSessionNotifications] is pure. Covers the agent-notifications spec scenarios: turn-completion vs
 * needs-input classification (incl. the suppressed launch-time session.started) and the per-event sound gate
 * for all four toggle combinations.
 */
class AgentSessionNotificationsTest {

    // -- classify: which state change notifies (agent-notifications spec) --

    @Test
    fun `working to idle is a completed turn`() {
        // turn.completed after a turn.started (design D5) — the canonical "turn completion notifies".
        assertEquals(
            SessionNotification.TURN_COMPLETED,
            AgentSessionNotifications.classify(SessionState.Working, SessionState.Idle),
        )
    }

    @Test
    fun `needs-input to idle is a completed turn`() {
        // needs.input cleared by the next turn.completed (design D5).
        assertEquals(
            SessionNotification.TURN_COMPLETED,
            AgentSessionNotifications.classify(SessionState.NeedsInput(NeedsInputKind.QUESTION), SessionState.Idle),
        )
    }

    @Test
    fun `launch-time registered to idle does not notify`() {
        // session.started at launch also lands on Idle (design D5); it is not a completed turn.
        assertNull(AgentSessionNotifications.classify(SessionState.Registered, SessionState.Idle))
    }

    @Test
    fun `idle reached with unknown previous is treated as a completed turn`() {
        // Notifier subscribed mid-flight: favour not missing a real turn.completed.
        assertEquals(
            SessionNotification.TURN_COMPLETED,
            AgentSessionNotifications.classify(null, SessionState.Idle),
        )
    }

    @Test
    fun `entering needs-input notifies regardless of kind`() {
        for (kind in NeedsInputKind.entries) {
            assertEquals(
                SessionNotification.NEEDS_INPUT,
                AgentSessionNotifications.classify(SessionState.Working, SessionState.NeedsInput(kind)),
            )
        }
    }

    @Test
    fun `entering working does not notify`() {
        assertNull(AgentSessionNotifications.classify(SessionState.Idle, SessionState.Working))
    }

    @Test
    fun `entering ended does not notify`() {
        assertNull(AgentSessionNotifications.classify(SessionState.Working, SessionState.Ended))
    }

    @Test
    fun `restored unknown state does not notify`() {
        assertNull(AgentSessionNotifications.classify(null, SessionState.Unknown))
    }

    // -- shouldBeep: per-event toggle gate, all four combinations (agent-notifications spec) --

    @Test
    fun `both sounds on by default beeps on each event`() {
        val on = RelaySoundSettings(turnCompletedSound = true, needsInputSound = true)
        assertTrue(AgentSessionNotifications.shouldBeep(SessionNotification.TURN_COMPLETED, on))
        assertTrue(AgentSessionNotifications.shouldBeep(SessionNotification.NEEDS_INPUT, on))
    }

    @Test
    fun `disabled turn-completed sound is silent while its notification still shows`() {
        val settings = RelaySoundSettings(turnCompletedSound = false, needsInputSound = true)
        assertFalse(AgentSessionNotifications.shouldBeep(SessionNotification.TURN_COMPLETED, settings))
        // The other event's sound is unaffected — the toggles are independent.
        assertTrue(AgentSessionNotifications.shouldBeep(SessionNotification.NEEDS_INPUT, settings))
    }

    @Test
    fun `disabled needs-input sound is silent`() {
        val settings = RelaySoundSettings(turnCompletedSound = true, needsInputSound = false)
        assertTrue(AgentSessionNotifications.shouldBeep(SessionNotification.TURN_COMPLETED, settings))
        assertFalse(AgentSessionNotifications.shouldBeep(SessionNotification.NEEDS_INPUT, settings))
    }

    @Test
    fun `both sounds off suppresses every beep`() {
        val off = RelaySoundSettings(turnCompletedSound = false, needsInputSound = false)
        assertFalse(AgentSessionNotifications.shouldBeep(SessionNotification.TURN_COMPLETED, off))
        assertFalse(AgentSessionNotifications.shouldBeep(SessionNotification.NEEDS_INPUT, off))
    }
}
