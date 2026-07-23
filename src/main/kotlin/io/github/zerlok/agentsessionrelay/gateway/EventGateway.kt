package io.github.zerlok.agentsessionrelay.gateway

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.github.zerlok.agentsessionrelay.domain.EventParseResult
import io.github.zerlok.agentsessionrelay.domain.SessionId
import io.github.zerlok.agentsessionrelay.domain.parseAgentEvent
import io.github.zerlok.agentsessionrelay.logic.SessionRegistryService

/**
 * The two responses the transport layer maps onto an HTTP status (design D3), keeping [EventGateway]
 * transport-agnostic. Mirrors the domain's [EventParseResult] shape.
 *  - [Accepted] → the event was applied, dropped as a tolerated unknown, or dropped as an unknown id;
 *    all are acknowledged no-error outcomes the handler answers `2xx` (design D5).
 *  - [Rejected] → unusable input (e.g. a blank type); the handler answers `4xx` and no state changed.
 */
sealed interface GatewayOutcome {
    data object Accepted : GatewayOutcome
    data class Rejected(val reason: String) : GatewayOutcome
}

/**
 * The transport-agnostic ingestion seam (design D3/D4): it turns the route/query/body primitives into a
 * normalized event via the pure [parseAgentEvent] and applies it to [SessionRegistryService], so the
 * HTTP layer ([RelayHttpRequestHandler]) is a thin shell and a future transport can replace it without
 * touching consumers. No netty, no HTTP types here — this is fixture-free testable exactly like
 * [SessionRegistryService], and the review-batch layering is preserved (the gateway edge depends on
 * logic, never the reverse).
 *
 * App-level (design D9), like the registry it drives. Threading (ARCHITECTURE §5.3): [accept] mutates
 * the registry, so it MUST be called on the EDT — the HTTP handler runs on a built-in-server thread and
 * hops to the EDT before invoking it (matching [SessionRegistryService.applyEvent]'s contract).
 */
@Service(Service.Level.APP)
class EventGateway {

    /**
     * Ingests one webhook event addressed to [sessionId] (design D5). Delegates classification to the
     * domain parser and folds a [EventParseResult.Parsed] event into the registry:
     *  - [EventParseResult.Parsed] → applied to the addressed session ([SessionRegistryService.applyEvent]
     *    itself acknowledges-and-drops an unknown id, so a stale/forged id is a safe no-op) → [Accepted].
     *  - [EventParseResult.ToleratedUnknown] → an unrecognized but well-formed `<type>`; no state change,
     *    still [Accepted] so old plugins tolerate newer hooks (design D5).
     *  - [EventParseResult.Malformed] → [Rejected]; no state change (design D5 "handler never disturbs").
     *
     * @param sessionId the opaque `<id>` route segment — identity is the route, event bodies carry none.
     * @param type the `<type>` route segment.
     * @param kind the optional `?kind=` query value (refines `needs.input`).
     * @param body the optional request body (reserved for `summary`/`reason`, ignored when absent).
     */
    fun accept(sessionId: String, type: String, kind: String? = null, body: String? = null): GatewayOutcome =
        when (val parsed = parseAgentEvent(type, kind, body)) {
            is EventParseResult.Malformed -> GatewayOutcome.Rejected(parsed.reason)
            EventParseResult.ToleratedUnknown -> GatewayOutcome.Accepted
            is EventParseResult.Parsed -> {
                SessionRegistryService.getInstance().applyEvent(SessionId(sessionId), parsed.event)
                GatewayOutcome.Accepted
            }
        }

    companion object {
        fun getInstance(): EventGateway = service()
    }
}
