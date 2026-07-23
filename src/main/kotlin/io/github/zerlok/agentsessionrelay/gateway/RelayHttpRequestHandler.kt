package io.github.zerlok.agentsessionrelay.gateway

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send
import java.nio.charset.StandardCharsets

/**
 * The IDE built-in web server extension for the normalized webhook `POST
 * ${RelayRoute.PREFIX}/sessions/<id>/events/<type>` (design D3/D4). A **thin shell** over [EventGateway]:
 * it only parses transport primitives, hops to the EDT, delegates, and maps the [GatewayOutcome] to a
 * status. No agent-specific code and no state machine live here — those are in the domain/logic layers.
 *
 * Two built-in-server facts (design D4): [isSupported] must be overridden to accept POST (the base
 * accepts only GET/HEAD); and a raw `HttpRequestHandler` bypasses `RestService`'s trust checks — that is
 * intended here, the trust boundary is loopback + the user's ssh tunnel (design D8), and this handler
 * owns request handling entirely. Received events are non-executable (design D8): the worst a forged POST
 * does is a misleading notification / state dot.
 *
 * Robustness (design R5): [process] never throws into the built-in server — any failure is logged and
 * answered `4xx`. Threading (ARCHITECTURE §5.3): [process] runs on a server thread and hops to the EDT
 * before touching the registry via [EventGateway.accept].
 *
 * NOT registered in `plugin.xml` here — the wiring phase (task 6.1) adds, inside
 * `<extensions defaultExtensionNs="com.intellij">`:
 * `<httpRequestHandler implementation="io.github.zerlok.agentsessionrelay.gateway.RelayHttpRequestHandler"/>`
 */
class RelayHttpRequestHandler : HttpRequestHandler() {

    // Base isSupported accepts only GET/HEAD; the webhook is POST-only, scoped to our route namespace so
    // other handlers on the shared built-in server are never shadowed (design D4).
    override fun isSupported(request: FullHttpRequest): Boolean =
        request.method() == HttpMethod.POST && RelayRoute.looksLikeEventsRoute(request.uri())

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val status = try {
            resolve(urlDecoder, request)
        } catch (t: Throwable) {
            // Never propagate into the built-in server (design R5); a hook flood/garbage payload is a 4xx.
            thisLogger().warn("Relay gateway failed to handle ${request.uri()}", t)
            HttpResponseStatus.BAD_REQUEST
        }
        status.send(context.channel(), request)
        return true
    }

    private fun resolve(urlDecoder: QueryStringDecoder, request: FullHttpRequest): HttpResponseStatus {
        // A path under our namespace that is not a well-formed events route is malformed → 4xx (design D5).
        val route = RelayRoute.parse(urlDecoder.path()) ?: return HttpResponseStatus.BAD_REQUEST
        val kind = urlDecoder.parameters()[RelayRoute.KIND_PARAM]?.firstOrNull()
        val body = request.content().takeIf { it.isReadable }?.toString(StandardCharsets.UTF_8)

        // Hop to the EDT before mutating the registry (ARCHITECTURE §5.3); block briefly for the outcome
        // so the response status reflects it (Accepted → 2xx, Rejected → 4xx).
        var outcome: GatewayOutcome = GatewayOutcome.Rejected("not evaluated")
        ApplicationManager.getApplication().invokeAndWait {
            outcome = EventGateway.getInstance().accept(route.sessionId, route.type, kind, body)
        }
        return when (outcome) {
            GatewayOutcome.Accepted -> HttpResponseStatus.ACCEPTED
            is GatewayOutcome.Rejected -> HttpResponseStatus.BAD_REQUEST
        }
    }
}
