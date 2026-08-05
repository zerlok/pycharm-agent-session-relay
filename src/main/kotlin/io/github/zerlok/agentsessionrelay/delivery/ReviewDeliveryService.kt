package io.github.zerlok.agentsessionrelay.delivery

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.ThreadingAssertions
import io.github.zerlok.agentsessionrelay.domain.Anchoring
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subjects
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import io.github.zerlok.agentsessionrelay.ui.EditorReviewOverlayService
import java.nio.file.Files
import java.nio.file.Path

/**
 * Owner of the whole submit pipeline (spec `review-delivery`, ARCHITECTURE §3): flush live positions
 * → verify anchors → plan with the pure [ReviewDelivery] → write `REVIEW.md` → refresh the VFS →
 * clear the batch on success, preserve it on failure. The presentation-layer action contributes only
 * the invocation and the notifications, so every decision here is reachable from a test without an
 * `AnActionEvent`.
 *
 * **Threading (ARCHITECTURE §5.3).** The pipeline hops once and only once:
 *
 * 1. **EDT** — flush live positions into the store and snapshot the batch. Store mutations are
 *    EDT-only.
 * 2. **Background** ([Task.Backgroundable]) — [verifyAnchors] reads file content and the write hits
 *    the filesystem. Neither is legal on the EDT, which is exactly why this stage could not live in
 *    the action and why verification used to be written against live editor markers instead of file
 *    content.
 * 3. **EDT** — apply the verdicts and take the clear-or-preserve decision, both store mutations.
 *
 * The exported text must reflect the verdicts, which are only *stored* in stage 3 — so stage 2 plans
 * from the snapshot with the verdicts applied in memory. [ReviewExporter][io.github.zerlok.agentsessionrelay.export.ReviewExporter]
 * stays a pure function of the batch it is handed, and the store still ends up carrying what was
 * exported.
 *
 * NOTE: this reaches *up* into [EditorReviewOverlayService] for one thing only — the live-marker
 * positions, which exist nowhere else. The delivery stage owns *when* the flush happens; the view
 * still owns the markers it reads.
 */
@Service(Service.Level.PROJECT)
class ReviewDeliveryService(private val project: Project) {

    /**
     * What a submit did. This is the observable the clear-versus-preserve invariant is asserted on:
     * the batch is cleared for [Written] and kept for everything else.
     */
    sealed interface Outcome {
        /** No renderable pending comment — nothing was written. */
        data object NothingToSubmit : Outcome

        /** The artifact was written at [path]; the batch has been cleared. */
        data class Written(val path: Path) : Outcome

        /** Nothing was written and the batch is intact. [cause] is null when the failure precedes any I/O. */
        data class Failed(val reason: String, val cause: Throwable?) : Outcome
    }

    /**
     * Runs the pipeline. Call on the EDT; [onOutcome] is invoked on the EDT once the batch has been
     * cleared or preserved, so a caller that renders the outcome sees a settled store.
     */
    fun submit(onOutcome: (Outcome) -> Unit = {}) {
        // Stages 1 and 3 mutate the store, which is EDT-only. Asserted rather than assumed: an EDT hop
        // that quietly stops happening is invisible until it corrupts state under a real workload.
        ThreadingAssertions.assertEventDispatchThread()

        val service = ReviewBatchService.getInstance(project)

        // Stage 1 (EDT): flush the live in-IDE positions into the store, so the ranges verified,
        // exported, and persisted below are the ones the user currently sees (ARCHITECTURE §3.2).
        for ((id, subject) in EditorReviewOverlayService.getInstance(project).currentPositions()) {
            service.updatePosition(id, subject)
        }
        // The immutable snapshot the background stage works from (ARCHITECTURE §5.3).
        val batch = service.comments()

        val basePath = project.basePath
        if (basePath == null) {
            onOutcome(Outcome.Failed("The project has no base path to write ${ReviewDelivery.FILE_NAME} to.", null))
            return
        }

        object : Task.Backgroundable(project, "Writing ${ReviewDelivery.FILE_NAME}", false) {
            private var verdicts: Map<CommentId, CommentStatus> = emptyMap()
            private var outcome: Outcome = Outcome.NothingToSubmit

            // Stage 2 (background): the reads and the write, neither legal on the EDT.
            override fun run(indicator: ProgressIndicator) {
                verdicts = verifyAnchors(batch)
                val verified = batch.map { comment -> verdicts[comment.id]?.let { comment.copy(status = it) } ?: comment }
                outcome = when (val plan = ReviewDelivery.plan(verified, basePath)) {
                    ReviewDelivery.Plan.NothingToSubmit -> Outcome.NothingToSubmit
                    is ReviewDelivery.Plan.WriteReview -> Outcome.Written(write(basePath, plan.content))
                }
            }

            // Task.Backgroundable runs onSuccess / onThrowable back on the EDT — stage 3.
            override fun onSuccess() = finish(outcome, verdicts, onOutcome)

            override fun onThrowable(error: Throwable) {
                // Relay's only log line: a write failure reported from the field has to be diagnosable
                // from idea.log, not only from a balloon the user has already dismissed.
                thisLogger().warn("Failed to write ${ReviewDelivery.FILE_NAME} under $basePath", error)
                finish(Outcome.Failed(error.message ?: error.javaClass.simpleName, error), verdicts, onOutcome)
            }
        }.queue()
    }

    /**
     * Stage 2's verification (spec `review-batch`): each line-anchored comment carrying a recorded
     * `anchorText` is checked against the text at its **recorded** range in its file's current
     * content — the in-memory document when the file is open, loaded from disk when it is not. One
     * path serves both cases: the position flush ran first, so an open file's recorded range already
     * *is* its live range.
     *
     * The returned map holds a verdict for exactly the comments that could be checked. A comment with
     * no recorded anchor text, one that is not line-anchored, and one whose file cannot be resolved or
     * read are **absent** — "we could not check" must not reach the agent as "we checked and it
     * moved". A closed file is not one of those cases.
     *
     * Reads file content, so it must run off the EDT (ARCHITECTURE §5.3); the read action is what
     * makes touching the VFS and its documents from there legal.
     *
     * This never moves a comment: it yields statuses, never subjects.
     */
    fun verifyAnchors(comments: List<ReviewComment>): Map<CommentId, CommentStatus> =
        ReadAction.compute<Map<CommentId, CommentStatus>, RuntimeException> {
            val fileDocuments = FileDocumentManager.getInstance()
            val virtualFiles = VirtualFileManager.getInstance()
            // One document load per file rather than per comment: the batch bounds the I/O either way,
            // but a file commented five times should still be read once.
            val documents = HashMap<String, Document?>()
            val verdicts = HashMap<CommentId, CommentStatus>()

            for (comment in comments) {
                val recorded = comment.anchorText ?: continue
                val lines = Subjects.linesOf(comment.subject) ?: continue
                val url = Subjects.fileUrlOf(comment.subject) ?: continue
                if (url !in documents) {
                    documents[url] = virtualFiles.findFileByUrl(url)?.let { fileDocuments.getDocument(it) }
                }
                val document = documents[url] ?: continue

                verdicts[comment.id] = when {
                    !Subjects.fitsIn(comment.subject, document.lineCount) -> CommentStatus.ORPHANED
                    Anchoring.matches(recorded, textAt(document, lines)) -> CommentStatus.ACTIVE
                    else -> CommentStatus.STALE
                }
            }
            verdicts
        }

    /** The text the recorded range spans, taken the same way the anchor text was captured from it. */
    private fun textAt(document: Document, lines: Pair<Int, Int>): String {
        val (startLine, endLine) = lines
        return document.getText(TextRange(document.getLineStartOffset(startLine), document.getLineEndOffset(endLine)))
    }

    /** Writes the artifact and registers it with the VFS. Off the EDT — both calls require it. */
    private fun write(basePath: String, content: String): Path {
        val reviewPath = Path.of(basePath, ReviewDelivery.FILE_NAME)
        Files.writeString(reviewPath, content)
        // The raw nio write bypasses the VFS, so the new file wouldn't appear in Project view until an
        // unrelated refresh. A targeted, synchronous VFS refresh registers it now — and must run here
        // off the EDT (a synchronous VFS refresh on the EDT is disallowed). A null return (path
        // unresolved) is benign: the file was just written.
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(reviewPath)
        return reviewPath
    }

    /** Stage 3 (EDT): record what verification found, then clear or preserve. */
    private fun finish(outcome: Outcome, verdicts: Map<CommentId, CommentStatus>, onOutcome: (Outcome) -> Unit) {
        if (project.isDisposed) return
        val service = ReviewBatchService.getInstance(project)
        // Idempotent: an unchanged status publishes nothing, so this cannot fight the editor-time
        // verdicts written by DocumentReviewMarkers.
        for ((id, status) in verdicts) service.updateStatus(id, status)
        // Clear only after a successful write: the comments are the user's only copy, there is no undo,
        // and a cleared batch is erased from persistent storage at the next save.
        if (outcome is Outcome.Written) service.clear()
        onOutcome(outcome)
    }

    companion object {
        fun getInstance(project: Project): ReviewDeliveryService = project.service<ReviewDeliveryService>()
    }
}
