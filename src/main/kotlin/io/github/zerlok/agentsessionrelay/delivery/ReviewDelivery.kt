package io.github.zerlok.agentsessionrelay.delivery

import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.export.ReviewExporter

/**
 * The pure decision of the Delivery stage (ARCHITECTURE §3): given the current batch, decide whether
 * there is anything to submit and, if so, produce the **exact** text of the `REVIEW.md` artifact.
 * No platform imports — trivially unit-testable without an editor, VFS, or filesystem (design D5,
 * mirroring [ReviewExporter]).
 *
 * The platform-facing action ([io.github.zerlok.agentsessionrelay.ui.SubmitReviewAction]) is left
 * with only side effects — flushing live positions, the off-EDT file write, the notification, and the
 * batch clear — while the empty-vs-nonempty branch and the written string live here.
 */
object ReviewDelivery {

    /** The Relay-owned generated artifact written at the project base path, overwritten each submit. */
    const val FILE_NAME = "REVIEW.md"

    // A minimal one-line header orienting the agent (delivery concern, kept out of the Exporter —
    // design open question). The Exporter's @path#L refs follow, so this never precedes a bare ref.
    private const val HEADER = "# Code review"

    /** The outcome of planning a submit: either nothing to submit, or the text to write. */
    sealed interface Plan {
        /** No renderable pending comments — the delivery stage writes nothing and tells the user. */
        data object NothingToSubmit : Plan

        /** The exact `REVIEW.md` contents to write (header + exported batch, ending in a newline). */
        data class WriteReview(val content: String) : Plan
    }

    /**
     * Serializes [comments] via [ReviewExporter] and decides the [Plan]. An empty export (no pending
     * comment, or none with a renderable line/range subject) is the [Plan.NothingToSubmit] no-op;
     * otherwise the content is the header, a blank line, then the exporter's output (already
     * newline-terminated) — so the whole file still ends with a trailing newline.
     */
    fun plan(comments: List<ReviewComment>, projectBasePath: String): Plan {
        val body = ReviewExporter.export(comments, projectBasePath)
        if (body.isEmpty()) return Plan.NothingToSubmit
        return Plan.WriteReview("$HEADER\n\n$body")
    }
}
