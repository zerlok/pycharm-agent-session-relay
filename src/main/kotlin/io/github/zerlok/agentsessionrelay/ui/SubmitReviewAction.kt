package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.delivery.ReviewDelivery
import io.github.zerlok.agentsessionrelay.delivery.ReviewDeliveryService

/**
 * "Submit review" — the presentation-layer trigger for the Delivery stage (spec `review-delivery`).
 * Instantiated on the tool-window toolbar rather than registered in `plugin.xml`, like
 * [RefreshAndReviewAction].
 *
 * It owns **no** stage of the pipeline: the flush, verification, export, write, VFS refresh, and the
 * clear-or-preserve decision all belong to [ReviewDeliveryService], which is therefore drivable —
 * and assertable — without an [AnActionEvent]. All that is left here is invoking it and turning the
 * [ReviewDeliveryService.Outcome] into a balloon.
 */
class SubmitReviewAction :
    AnAction("Submit Review", "Write REVIEW.md at the project root and clear the batch", AllIcons.Actions.Upload),
    DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewDeliveryService.getInstance(project).submit { outcome -> notify(project, outcome) }
    }

    private fun notify(project: Project, outcome: ReviewDeliveryService.Outcome) = when (outcome) {
        ReviewDeliveryService.Outcome.NothingToSubmit -> notify(
            project,
            "Relay: nothing to submit",
            "No pending comments — add a review comment first.",
            NotificationType.INFORMATION,
        )

        is ReviewDeliveryService.Outcome.Written -> notify(
            project,
            "Relay: ${ReviewDelivery.FILE_NAME} is ready",
            "${ReviewDelivery.FILE_NAME} is ready at the project root — return to your idle session and ask the agent to read it.",
            NotificationType.INFORMATION,
        )

        is ReviewDeliveryService.Outcome.Failed -> notify(
            project,
            "Relay: failed to write ${ReviewDelivery.FILE_NAME}",
            outcome.reason,
            NotificationType.ERROR,
        )
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
