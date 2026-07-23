package io.github.zerlok.agentsessionrelay.gateway

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService

/**
 * Real-platform test of the HTTP transport edge [RelayHttpRequestHandler] (task 2.2): the [isSupported]
 * method gate + route scoping, and that [process] never throws and maps outcomes onto a status (malformed
 * route → `BAD_REQUEST`, well-formed event → `ACCEPTED`). The route/state logic itself is covered one
 * layer below in [RelayRouteTest] and [EventGatewayTest]; this pins the netty shell (design D4/R5). A
 * [EmbeddedChannel] stands in for the built-in server so the response status can be read back.
 */
class RelayHttpRequestHandlerTest : BasePlatformTestCase() {

    private val handler = RelayHttpRequestHandler()

    override fun setUp() {
        super.setUp()
        // The app-level registry is shared across the run — start each test from a clean slate.
        SessionRegistryService.getInstance().sessions().forEach { SessionRegistryService.getInstance().dismiss(it.id) }
    }

    override fun tearDown() {
        try {
            SessionRegistryService.getInstance().sessions().forEach { SessionRegistryService.getInstance().dismiss(it.id) }
        } finally {
            super.tearDown()
        }
    }

    // -- isSupported: POST-only, scoped to the events namespace (design D4) --

    fun `test isSupported accepts POST on the events route`() {
        assertTrue(handler.isSupported(request(HttpMethod.POST, "/relay/v1/sessions/abc/events/turn.started")))
    }

    fun `test isSupported rejects GET on the events route`() {
        assertFalse(handler.isSupported(request(HttpMethod.GET, "/relay/v1/sessions/abc/events/turn.started")))
    }

    fun `test isSupported rejects PUT on the events route`() {
        assertFalse(handler.isSupported(request(HttpMethod.PUT, "/relay/v1/sessions/abc/events/turn.started")))
    }

    fun `test isSupported rejects POST off the events namespace`() {
        assertFalse(handler.isSupported(request(HttpMethod.POST, "/api/health")))
    }

    // -- process: never throws, maps outcome to status --

    fun `test process answers BAD_REQUEST for a malformed route`() {
        val response = process(HttpMethod.POST, "/relay/v1/sessions/abc/nope")
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status())
    }

    fun `test process answers ACCEPTED for a well-formed event`() {
        // An unknown id is an acknowledged no-op at the gateway (design D5) → 2xx; process must not throw.
        val response = process(HttpMethod.POST, "/relay/v1/sessions/unknown-id/events/turn.started")
        assertEquals(HttpResponseStatus.ACCEPTED, response.status())
    }

    private fun request(method: HttpMethod, uri: String): FullHttpRequest =
        DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri)

    /** Drives [RelayHttpRequestHandler.process] against an [EmbeddedChannel] and returns the sent response. */
    private fun process(method: HttpMethod, uri: String): HttpResponse {
        val adapter = object : ChannelInboundHandlerAdapter() {}
        val channel = EmbeddedChannel(adapter)
        val context = channel.pipeline().context(adapter)
        val handled = handler.process(QueryStringDecoder(uri), request(method, uri), context)
        assertTrue(handled)
        channel.flushOutbound()
        val out = channel.readOutbound<Any>()
        channel.close()
        return out as HttpResponse
    }
}
