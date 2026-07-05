package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import javax.swing.Icon

/**
 * The persistent gutter marker for a stored comment (ARCHITECTURE §3.3). It is deliberately a
 * *different* icon from the transient hover "+" ([AddCommentGutterIconRenderer]): the "+" means
 * "add here", this speech-balloon means "a comment exists here" (design decision D-hover-vs-stored).
 *
 * Right-clicking the marker offers "Delete comment", which routes through [ReviewBatchService];
 * every surface (this marker included) then reconciles off the resulting store event — the renderer
 * never mutates a view directly.
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
