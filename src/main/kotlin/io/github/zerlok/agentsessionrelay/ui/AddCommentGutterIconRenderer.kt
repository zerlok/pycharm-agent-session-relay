package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon

/** The "+" icon shown in the gutter of the hovered line; clicking it opens an inline comment draft. */
class AddCommentGutterIconRenderer(
    private val editor: Editor,
    private val line: Int,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Add

    override fun getTooltipText(): String = "Add review comment"

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
}
