package io.github.zerlok.agentsessionrelay.gateway

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The single canonical source for the normalized webhook route shape (design D3/D5). The HTTP handler
 * ([RelayHttpRequestHandler]), the endpoint helper ([RelayGatewayEndpoint]) and `docs/ADAPTERS.md`
 * reference these constants rather than restating the path (user rule: no restating). Pure and
 * platform-free (no netty, no IDE types) so route parsing is unit-testable on its own.
 *
 * Route: `POST {AGENT_SESSION_RELAY_URL}${PREFIX}/sessions/<id>/events/<type>[?kind=...]` — the wire
 * `<type>` names live in [io.github.zerlok.agentsessionrelay.domain.AgentEventType], the `<id>` is the
 * opaque session id and the sole routing key (design D5).
 */
object RelayRoute {
    /** Versioned route prefix on the IDE built-in web server (design D4/D5); confirm no collision (D open Q). */
    const val PREFIX = "/relay/v1"

    /** The optional query parameter that refines `needs.input` (design D5); parsed by the domain layer. */
    const val KIND_PARAM = "kind"

    // Full path shape: <prefix>/sessions/<id>/events/<type>, optional trailing slash. Query is never part
    // of the decoded path netty hands us, so it is not matched here. Segments are non-empty, slash-free.
    private val PATH = Regex("^${Regex.escape(PREFIX)}/sessions/([^/]+)/events/([^/]+)/?$")

    /** The `<id>` and `<type>` segments extracted from a matched events path. */
    data class Parsed(val sessionId: String, val type: String)

    /**
     * Cheap prefix test for `isSupported` (design D4) — true for any path under the session-events
     * namespace, so unrelated built-in-server handlers are never shadowed. Accepts the raw request URI
     * (a trailing `?query` still matches, since the prefix is anchored at the start).
     */
    fun looksLikeEventsRoute(uri: String): Boolean = uri.startsWith("$PREFIX/sessions/")

    /**
     * Parses a decoded request path into its [Parsed] segments, or `null` when the path is not a
     * well-formed events route (the handler maps `null` to `4xx`). Percent-decodes each segment so an
     * encoded id/type round-trips.
     */
    fun parse(path: String): Parsed? {
        val m = PATH.matchEntire(path) ?: return null
        val id = decode(m.groupValues[1])
        val type = decode(m.groupValues[2])
        if (id.isEmpty() || type.isEmpty()) return null
        return Parsed(id, type)
    }

    /** The events sub-path for a session id (no host/port) — [RelayGatewayEndpoint] prefixes the base URL. */
    fun eventsPath(sessionId: String): String = "$PREFIX/sessions/$sessionId/events"

    private fun decode(segment: String): String = URLDecoder.decode(segment, StandardCharsets.UTF_8)
}
