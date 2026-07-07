package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * "Refresh & review" (ARCHITECTURE §5.1): forces an asynchronous VFS refresh so edits written to
 * disk — by a local agent, or synced in from a remote sandbox — become visible before the user
 * reviews them. The refresh is async so it never blocks the EDT; it is a plain state action (no
 * `WriteCommandAction`, which is only for Document/PSI/VFS *edits*, ARCHITECTURE §5.3).
 */
class RefreshAndReviewAction : AnAction("Refresh & Review", "Refresh files from disk before review", AllIcons.Actions.Refresh) {

    override fun actionPerformed(e: AnActionEvent) {
        VirtualFileManager.getInstance().asyncRefresh(null)
    }
}
