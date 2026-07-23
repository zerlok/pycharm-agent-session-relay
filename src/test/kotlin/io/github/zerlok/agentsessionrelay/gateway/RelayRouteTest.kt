package io.github.zerlok.agentsessionrelay.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests of the platform-free route parser [RelayRoute] (task 2.2). Covers the canonical/
 * trailing-slash match, the null (→ 4xx) cases the handler maps to `BAD_REQUEST`, percent-decoding of
 * segments, and the [RelayRoute.looksLikeEventsRoute] prefix gate. No fixture: the parser is transport-
 * agnostic (design D3/D5), exercised directly rather than through netty.
 */
class RelayRouteTest {

    // -- parse: well-formed routes --

    @Test
    fun `parses a canonical events path`() {
        val parsed = RelayRoute.parse("/relay/v1/sessions/abc-123/events/turn.started")
        assertEquals(RelayRoute.Parsed("abc-123", "turn.started"), parsed)
    }

    @Test
    fun `parses a path with an optional trailing slash`() {
        val parsed = RelayRoute.parse("/relay/v1/sessions/abc-123/events/turn.completed/")
        assertEquals(RelayRoute.Parsed("abc-123", "turn.completed"), parsed)
    }

    @Test
    fun `percent-decoded segments round-trip`() {
        val parsed = RelayRoute.parse("/relay/v1/sessions/a%20b/events/needs.input")
        assertEquals(RelayRoute.Parsed("a b", "needs.input"), parsed)
    }

    // -- parse: malformed routes → null (handler answers 4xx) --

    @Test
    fun `returns null for a wrong prefix`() {
        assertNull(RelayRoute.parse("/relay/v2/sessions/abc/events/turn.started"))
        assertNull(RelayRoute.parse("/some/other/path"))
    }

    @Test
    fun `returns null when the events segment is missing`() {
        assertNull(RelayRoute.parse("/relay/v1/sessions/abc"))
    }

    @Test
    fun `returns null when the type segment is missing`() {
        assertNull(RelayRoute.parse("/relay/v1/sessions/abc/events"))
        assertNull(RelayRoute.parse("/relay/v1/sessions/abc/events/"))
    }

    @Test
    fun `returns null for an empty id segment`() {
        assertNull(RelayRoute.parse("/relay/v1/sessions//events/turn.started"))
    }

    @Test
    fun `returns null for a trailing extra segment`() {
        assertNull(RelayRoute.parse("/relay/v1/sessions/abc/events/turn.started/extra"))
    }

    // -- looksLikeEventsRoute: cheap prefix gate for isSupported --

    @Test
    fun `looksLikeEventsRoute is true under the sessions namespace, even with a query`() {
        assertTrue(RelayRoute.looksLikeEventsRoute("/relay/v1/sessions/abc/events/needs.input"))
        assertTrue(RelayRoute.looksLikeEventsRoute("/relay/v1/sessions/abc/events/needs.input?kind=permission"))
    }

    @Test
    fun `looksLikeEventsRoute is false off the namespace`() {
        assertFalse(RelayRoute.looksLikeEventsRoute("/relay/v1/other"))
        assertFalse(RelayRoute.looksLikeEventsRoute("/api/health"))
    }

    // -- eventsPath: the canonical sub-path a config exports --

    @Test
    fun `eventsPath composes the canonical sub-path for a session`() {
        assertEquals("/relay/v1/sessions/abc/events", RelayRoute.eventsPath("abc"))
    }
}
