package io.github.zerlok.agentsessionrelay.delivery

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.concurrency.ThreadingAssertions
import io.github.zerlok.agentsessionrelay.domain.Anchoring
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subjects
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import io.github.zerlok.agentsessionrelay.ui.EditorReviewOverlayService
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
 * 2. **Background** ([Task.Backgroundable]) — [verifyAnchors] reads every commented file's content,
 *    and resolving the artifact in the VFS needs a synchronous refresh. Neither is legal on the EDT,
 *    which is exactly why this stage could not live in the action and why verification used to be
 *    written against live editor markers instead of file content.
 * 3. **EDT** — write the artifact through its [Document], apply the verdicts, and take the
 *    clear-or-preserve decision.
 *
 * The write sits in stage 3, not stage 2, because **the export has to be visible to justify clearing
 * the batch**. A raw filesystem write goes behind the platform's Document layer, so an open
 * `REVIEW.md` keeps showing the previous export while the comments — the user's only copy, with no
 * undo — are destroyed on the strength of the write not throwing. Writing the [Document] makes
 * "written" and "visible" the same event, and Document mutations are EDT-plus-write-action by
 * platform contract. What the off-EDT rule protects — a submit that does not freeze the IDE — is
 * carried by stage 2, which holds the work that can actually block.
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
            private var plan: ReviewDelivery.Plan = ReviewDelivery.Plan.NothingToSubmit
            private var directory: VirtualFile? = null

            // Stage 2 (background): the content reads, the plan, and the VFS lookup — none legal on
            // the EDT. It decides *what* to write and hands stage 3 the exact text.
            override fun run(indicator: ProgressIndicator) {
                verdicts = verifyAnchors(batch)
                val verified = batch.map { comment -> verdicts[comment.id]?.let { comment.copy(status = it) } ?: comment }
                plan = ReviewDelivery.plan(verified, basePath)
                if (plan is ReviewDelivery.Plan.WriteReview) {
                    directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(basePath))
                        // Reload the directory's children, and do not settle for refreshing the
                        // artifact's own path: a REVIEW.md deleted outside the IDE stays in the VFS as
                        // a valid-looking file that a path refresh does not retract, and writing into
                        // that phantom entry silently produces no file — a submit reporting success
                        // over nothing, which is the failure this whole pipeline exists to prevent.
                        ?.also { VfsUtil.markDirtyAndRefresh(/* async = */ false, /* recursive = */ false, true, it) }
                }
            }

            // Task.Backgroundable runs onSuccess / onThrowable back on the EDT — stage 3.
            override fun onSuccess() {
                val outcome = when (val current = plan) {
                    ReviewDelivery.Plan.NothingToSubmit -> Outcome.NothingToSubmit
                    is ReviewDelivery.Plan.WriteReview -> write(basePath, directory, current.content)
                }
                finish(outcome, verdicts, onOutcome)
            }

            override fun onThrowable(error: Throwable) = finish(failed(basePath, error), verdicts, onOutcome)
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

    /**
     * Stage 3's write: put [content] into the artifact's [Document] — the object an open editor
     * renders — inside a write action, then save it to disk.
     *
     * This is the whole point of the stage split changing. Writing the file directly leaves an open
     * `REVIEW.md` showing the previous export, and a submit cannot tell that state apart from a
     * successful one, so it clears the batch over it. Here the text the user sees and the text on
     * disk are set by the same action, and a failure to reach the Document is a failure to submit.
     *
     * The write action is wrapped in a *command* because the target may be an open editor: the
     * platform's undo and PSI-commit machinery expects a document change to arrive commanded.
     *
     * [directory] is the project base directory as stage 2 resolved it; a null one means the VFS
     * cannot see the project root, which is a failure rather than something to write around.
     */
    private fun write(basePath: String, directory: VirtualFile?, content: String): Outcome {
        ThreadingAssertions.assertEventDispatchThread()
        if (directory == null) {
            return Outcome.Failed("The project directory $basePath is not visible to the IDE.", null)
        }
        return try {
            var document: Document? = null
            WriteCommandAction.runWriteCommandAction(project, "Write ${ReviewDelivery.FILE_NAME}", null, {
                val file = directory.findChild(ReviewDelivery.FILE_NAME)
                    ?: directory.createChildData(this, ReviewDelivery.FILE_NAME)
                document = FileDocumentManager.getInstance().getDocument(file)?.also {
                    it.setText(content)
                    // Saved here rather than left for the platform's autosave: the agent reads the
                    // file, not the IDE's in-memory copy, and it is told the review is ready now.
                    FileDocumentManager.getInstance().saveDocument(it)
                }
            })
            when (document) {
                // No document for the path: it is a directory, a binary, or otherwise not text. The
                // artifact was NOT written, and the batch must survive to say so.
                null -> Outcome.Failed("${ReviewDelivery.FILE_NAME} under $basePath cannot be written as text.", null)
                else -> Outcome.Written(artifactPath(basePath))
            }
        } catch (error: Throwable) {
            failed(basePath, error)
        }
    }

    /** The artifact's path — what a caller renders, and what stage 2 refreshes into the VFS. */
    private fun artifactPath(basePath: String): Path = Path.of(basePath, ReviewDelivery.FILE_NAME)

    /**
     * Relay's only log line: a write failure reported from the field has to be diagnosable from
     * idea.log, not only from a balloon the user has already dismissed.
     */
    private fun failed(basePath: String, error: Throwable): Outcome.Failed {
        thisLogger().warn("Failed to write ${ReviewDelivery.FILE_NAME} under $basePath", error)
        return Outcome.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    /** Stage 3 (EDT): record what verification found, then clear or preserve. */
    private fun finish(outcome: Outcome, verdicts: Map<CommentId, CommentStatus>, onOutcome: (Outcome) -> Unit) {
        if (project.isDisposed) return
        val service = ReviewBatchService.getInstance(project)
        // Idempotent: an unchanged status publishes nothing, so this cannot fight the editor-time
        // verdicts written by DocumentReviewMarkers.
        for ((id, status) in verdicts) service.updateStatus(id, status)
        // Clear only against an export the user can see: the comments are the user's only copy, there
        // is no undo, and a cleared batch is erased from persistent storage at the next save. Written
        // is produced by the write action that put the text in the Document, so it cannot be true
        // while the editor still shows the previous export.
        if (outcome is Outcome.Written) service.clear()
        onOutcome(outcome)
    }

    companion object {
        fun getInstance(project: Project): ReviewDeliveryService = project.service<ReviewDeliveryService>()
    }
}
