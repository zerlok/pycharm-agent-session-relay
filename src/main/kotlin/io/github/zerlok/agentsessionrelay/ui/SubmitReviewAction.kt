package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.delivery.ReviewDelivery
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Submit review" — the Delivery stage controller (ARCHITECTURE §3, spec `review-delivery`). It is
 * instantiated on the tool-window toolbar built by `comment-batch` (not registered in `plugin.xml`,
 * matching [RefreshAndReviewAction]). On submit it:
 *
 * 1. Flushes current in-IDE positions into the store (ARCHITECTURE §3.2 export sync point): reads
 *    each open comment's live marker range via [EditorReviewOverlayService.currentPositions] and
 *    pushes it through [ReviewBatchService.updatePosition], so the export reflects in-IDE edits.
 * 2. Plans the submit with the pure [ReviewDelivery] (empty-vs-nonempty + the exact text). An empty
 *    batch is a no-op: nothing is written and the user is told there is nothing to submit.
 * 3. Writes `REVIEW.md` at [Project.getBasePath] **off the EDT** ([Task.Backgroundable], ARCHITECTURE
 *    §5.3) — it is a Relay-owned generated artifact, overwritten each submit.
 * 4. On success (back on the EDT) notifies the user and clears the batch via [ReviewBatchService.clear]
 *    (gutter + tool window empty via the listener); on failure notifies an error and leaves the batch
 *    intact so the user can retry.
 *
 * Opening an agent connection or typing into a terminal is explicitly out of scope for this change.
 */
class SubmitReviewAction :
    AnAction("Submit Review", "Write REVIEW.md at the project root and clear the batch", AllIcons.Actions.Upload),
    DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Flush live positions into the store before reading the batch (ARCHITECTURE §3.2). Both the
        // read and the updatePosition commands run on the EDT — this action fires on the EDT.
        val service = ReviewBatchService.getInstance(project)
        for ((id, subject) in EditorReviewOverlayService.getInstance(project).currentPositions()) {
            service.updatePosition(id, subject)
        }

        val basePath = project.basePath
        if (basePath == null) {
            notify(project, "Relay: cannot submit", "The project has no base path to write ${ReviewDelivery.FILE_NAME} to.", NotificationType.ERROR)
            return
        }

        when (val plan = ReviewDelivery.plan(service.comments(), basePath)) {
            ReviewDelivery.Plan.NothingToSubmit ->
                notify(project, "Relay: nothing to submit", "No pending comments — add a review comment first.", NotificationType.INFORMATION)

            is ReviewDelivery.Plan.WriteReview ->
                writeReview(project, basePath, plan.content)
        }
    }

    /** Writes the artifact off the EDT; clears the batch on success, preserves it on failure. */
    private fun writeReview(project: Project, basePath: String, content: String) {
        object : Task.Backgroundable(project, "Writing ${ReviewDelivery.FILE_NAME}", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                Files.writeString(Path.of(basePath, ReviewDelivery.FILE_NAME), content)
            }

            // Task.Backgroundable runs onSuccess / onThrowable back on the EDT.
            override fun onSuccess() {
                notify(
                    project,
                    "Relay: ${ReviewDelivery.FILE_NAME} is ready",
                    "${ReviewDelivery.FILE_NAME} is ready at the project root — return to your idle session and ask the agent to read it.",
                    NotificationType.INFORMATION,
                )
                // Clear only after a successful write so a failure never loses pending comments.
                ReviewBatchService.getInstance(project).clear()
            }

            override fun onThrowable(error: Throwable) {
                notify(
                    project,
                    "Relay: failed to write ${ReviewDelivery.FILE_NAME}",
                    error.message ?: error.javaClass.simpleName,
                    NotificationType.ERROR,
                )
            }
        }.queue()
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    companion object {
        // Must match the <notificationGroup id="…"> in META-INF/plugin.xml (the canonical source).
        private const val NOTIFICATION_GROUP_ID = "Agent Session Relay"
    }
}
