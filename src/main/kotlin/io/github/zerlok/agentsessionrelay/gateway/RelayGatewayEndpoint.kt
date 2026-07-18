package io.github.zerlok.agentsessionrelay.gateway

import org.jetbrains.ide.BuiltInServerManager

/**
 * Discovery of the gateway's loopback endpoint on the IDE built-in web server (design D2/D4). The launch
 * service (task 3.2) reads these to build the env contract it exports into the terminal:
 * `AGENT_SESSION_RELAY_URL` = [baseUrl], `AGENT_SESSION_RELAY_PORT` = [port]. Kept here so the port
 * source (`BuiltInServerManager.getInstance().port`, design D4 — no thread/port lifecycle to manage) has
 * one canonical caller, and the route shape ([RelayRoute]) stays pure/platform-free.
 */
object RelayGatewayEndpoint {

    /** The interface the built-in web server binds; nothing off-host reaches it directly (design D8). */
    const val LOOPBACK_HOST = "127.0.0.1"

    /**
     * The IDE built-in web server port (63342+, one per IDE — design D4). The connection tool uses it to
     * build the `ssh -R` reverse tunnel (design D2, exported as `AGENT_SESSION_RELAY_PORT`).
     */
    fun port(): Int = BuiltInServerManager.getInstance().port

    /** Locally-reachable base gateway URL — `AGENT_SESSION_RELAY_URL` (design D2). */
    fun baseUrl(): String = "http://$LOOPBACK_HOST:${port()}"

    /** The full events endpoint for a session id: [baseUrl] + [RelayRoute.eventsPath]. */
    fun eventsUrl(sessionId: String): String = baseUrl() + RelayRoute.eventsPath(sessionId)
}
