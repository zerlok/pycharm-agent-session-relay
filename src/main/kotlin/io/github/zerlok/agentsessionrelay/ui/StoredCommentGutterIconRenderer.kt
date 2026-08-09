package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import javax.swing.Icon

/**
 * A gutter icon for a stored comment: deliberately a *different* icon from the transient hover "+"
 * ([AddCommentGutterIconRenderer]), because the "+" means "add here" while this speech-balloon means
 * "a comment exists here". Right-clicking it offers Edit and Delete, both routed through
 * [ReviewBatchService] so every surface reconciles off the resulting store event.
 *
 * TODO: not wired to anything today — a commented range is marked by the gutter *bar* on
 *   [DocumentReviewMarkers]' highlighter instead. Kept for the deferred hide-comments change, which
 *   needs a gutter affordance to bring a hidden comment back.
 */
class StoredCommentGutterIconRenderer(
    private val project: Project,
    private val commentId: CommentId,
    private val tooltip: String,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Balloon

    override fun getTooltipText(): String = tooltip

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
        object : AnAction("Edit Comment", "Re-open this comment for editing", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                val editor = e.getData(CommonDataKeys.EDITOR) ?: return
                // Re-read the current record from the store so the box seeds with the latest body.
                val comment = ReviewBatchService.getInstance(project).comments()
                    .firstOrNull { it.id == commentId } ?: return
                CommentDraftController.getInstance(project).openForEdit(editor, comment)
            }
        },
        object : AnAction("Delete Comment", "Remove this comment from the review batch", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                ReviewBatchService.getInstance(project).removeComment(commentId)
            }
        },
    )

    // Identity is the comment: two renderers for the same id are equal so the platform coalesces them.
    override fun equals(other: Any?): Boolean =
        other is StoredCommentGutterIconRenderer && other.commentId == commentId

    override fun hashCode(): Int = commentId.hashCode()
}
