package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The "+" icon shown in the gutter of the hovered line; clicking it opens an inline comment draft. */
class AddCommentGutterIconRenderer(
    private val editor: Editor,
    private val line: Int,
) : GutterIconRenderer() {

    // Relay-branded "+" so the affordance is distinguishable at a glance from the plain platform "+"
    // that GitHub/GitLab review plugins place in the gutter; the tooltip names the owner too.
    override fun getIcon(): Icon = ICON

    override fun getTooltipText(): String = "Relay: add review comment"

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val project = editor.project ?: return
            val controller = CommentDraftController.getInstance(project)
            val (start, end) = controller.rangeFor(editor, line)
            controller.open(editor, start, end)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AddCommentGutterIconRenderer && other.editor === editor && other.line == line

    override fun hashCode(): Int = line

    private companion object {
        private val ICON: Icon =
            IconLoader.getIcon("/icons/relayAddComment.svg", AddCommentGutterIconRenderer::class.java)
    }
}
