package io.github.zerlok.agentsessionrelay.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener

/**
 * Presentation-layer subscriber on the [ReviewBatchListener] seam: turns a store change into a
 * user-facing notification. It holds no store handle — it reacts to the event, matching the layered
 * design (ARCHITECTURE §3.1). Until the gutter markers and tool window land, this is the only
 * feedback that a submitted comment reached the batch.
 */
@Service(Service.Level.PROJECT)
class ReviewBatchNotifier(private val project: Project) : ReviewBatchListener, Disposable {

    init {
        project.messageBus.connect(this).subscribe(ReviewBatchListener.TOPIC, this)
    }

    override fun commentAdded(comment: ReviewComment) {
        val snippet = comment.body.trim().take(BODY_SNIPPET_CHARS)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                "Relay: comment added to review batch",
                describe(comment.subject) + if (snippet.isBlank()) "" else " — $snippet",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    override fun dispose() {
        // MessageBus connection was parented to `this`; it disconnects with the service.
    }

    private fun describe(subject: Subject): String = when (subject) {
        // Line numbers are 0-based in the model; show them 1-based like the editor gutter.
        is Subject.Line -> "line ${subject.line + 1}"
        is Subject.LineRange -> "lines ${subject.startLine + 1}-${subject.endLine + 1}"
        is Subject.File -> "whole file"
        is Subject.Files -> "${subject.fileUrls.size} files"
        Subject.Project -> "whole review"
    }

    companion object {
        // NOTE: must match the <notificationGroup id="…"> registered in META-INF/plugin.xml,
        // which is the canonical source; a mismatch makes notifications silently no-op.
        private const val NOTIFICATION_GROUP_ID = "Agent Session Relay"
        private const val BODY_SNIPPET_CHARS = 120
    }
}
