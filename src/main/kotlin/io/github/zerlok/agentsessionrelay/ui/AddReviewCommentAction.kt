package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * The editor context-menu (right-click) entry point to author a review comment (design D2),
 * registered under the `EditorPopupMenu` group in `plugin.xml`. It mirrors the gutter "+" affordance:
 * the target range comes from the one shared rule, [CommentDraftController.rangeFor], and it opens
 * the same inline box through [CommentDraftController.open]. Passing the *caret* line as the
 * "clicked" line is what keeps the two entry points in parity (D2) — a caret always rests at one end
 * of its own selection, so it satisfies that rule's containment test whenever a selection exists.
 *
 * The action's text/description are declared in `plugin.xml` (the canonical place for a registered
 * action), so this class holds only the behavior.
 */
class AddReviewCommentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = editor.project ?: return
        val controller = CommentDraftController.getInstance(project)
        val (start, end) = controller.rangeFor(editor, editor.caretModel.logicalPosition.line)
        controller.open(editor, start, end)
    }

    /** Hide the action in non-editor popups (D2): it only makes sense with an editor in context. */
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
    }

    // update() only reads the editor from the context, which is available on the background thread.
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
