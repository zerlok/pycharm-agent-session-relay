package io.github.zerlok.agentsessionrelay.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The tool-window presentation of the review batch (spec `review-batch`). Lists pending comments
 * grouped by file — each entry showing its 1-based line range and a body snippet — with
 * double-click navigation, a per-entry delete, and a toolbar (Refresh & review, Clear). It is a
 * pure view: it never holds a store copy, it subscribes to [ReviewBatchListener] and rebuilds from
 * [ReviewBatchService.comments] on every event (single source of truth, ARCHITECTURE §3.1).
 */
class ReviewBatchToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewBatchPanel(project, toolWindow.disposable)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        // Must match the <toolWindow id="…"> in META-INF/plugin.xml.
        const val TOOL_WINDOW_ID = "Relay Review"
    }
}

/** The tool-window content: a tree over the batch plus a toolbar, kept in sync with the store. */
private class ReviewBatchPanel(
    private val project: Project,
    parent: Disposable,
) : SimpleToolWindowPanel(true, true), ReviewBatchListener {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = BatchCellRenderer()
    }

    init {
        toolbar = buildToolbar().component
        setContent(JBScrollPane(tree))

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedComment()?.let(::navigateTo)
            }
        })

        project.messageBus.connect(parent).subscribe(ReviewBatchListener.TOPIC, this)
        rebuild()
    }

    // -- Store events: rebuild from the store (single source of truth). --

    override fun commentAdded(comment: ReviewComment) = rebuild()

    override fun commentRemoved(comment: ReviewComment) = rebuild()

    override fun commentUpdated(comment: ReviewComment) = rebuild()

    override fun batchCleared() = rebuild()

    private fun buildToolbar() = ActionManager.getInstance().createActionToolbar(
        "RelayReviewBatch",
        DefaultActionGroup(
            SubmitReviewAction(),
            RefreshAndReviewAction(),
            DeleteSelectedAction(),
            ClearBatchAction(),
        ),
        true,
    ).also { it.targetComponent = tree }

    private fun rebuild() {
        root.removeAllChildren()
        // Group by file in first-seen order; sort each group by start line (spec: "in line order").
        val groups = LinkedHashMap<String, MutableList<ReviewComment>>()
        for (comment in ReviewBatchService.getInstance(project).comments()) {
            groups.getOrPut(groupKey(comment.subject)) { mutableListOf() }.add(comment)
        }
        for ((key, comments) in groups) {
            val fileNode = DefaultMutableTreeNode(FileGroup(key, fileLabel(key)))
            for (comment in comments.sortedBy { startLineOf(it.subject) ?: Int.MAX_VALUE }) {
                fileNode.add(DefaultMutableTreeNode(comment))
            }
            root.add(fileNode)
        }
        treeModel.reload()
        expandAll()
    }

    private fun expandAll() {
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }

    private fun selectedComment(): ReviewComment? =
        ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewComment)

    private fun navigateTo(comment: ReviewComment) {
        val url = fileUrlOf(comment.subject) ?: return
        val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: return
        val line = startLineOf(comment.subject) ?: 0
        OpenFileDescriptor(project, file, line, 0).navigate(true)
    }

    private inner class DeleteSelectedAction :
        AnAction("Delete Comment", "Remove the selected comment from the batch", AllIcons.General.Remove), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            selectedComment()?.let { ReviewBatchService.getInstance(project).removeComment(it.id) }
        }
    }

    private inner class ClearBatchAction :
        AnAction("Clear Batch", "Remove all pending comments", AllIcons.Actions.GC), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            ReviewBatchService.getInstance(project).clear()
        }
    }

    companion object {
        private const val SNIPPET_CHARS = 80

        /** A group key per file url; the non-line subjects (unauthored in the MVP) fall into buckets. */
        private fun groupKey(subject: Subject): String = when (subject) {
            is Subject.Line -> subject.fileUrl
            is Subject.LineRange -> subject.fileUrl
            is Subject.File -> subject.fileUrl
            is Subject.Files -> "(multiple files)"
            Subject.Project -> "(whole review)"
        }

        private fun fileUrlOf(subject: Subject): String? = when (subject) {
            is Subject.Line -> subject.fileUrl
            is Subject.LineRange -> subject.fileUrl
            is Subject.File -> subject.fileUrl
            else -> null
        }

        private fun startLineOf(subject: Subject): Int? = when (subject) {
            is Subject.Line -> subject.line
            is Subject.LineRange -> subject.startLine
            else -> null
        }

        private fun fileLabel(key: String): String =
            VirtualFileManager.getInstance().findFileByUrl(key)?.presentableName
                ?: key.substringAfterLast('/').ifBlank { key }

        // 1-based line range for display (the store is 0-based, editor convention — ARCHITECTURE §3.2).
        private fun rangeLabel(subject: Subject): String = when (subject) {
            is Subject.Line -> "L${subject.line + 1}"
            is Subject.LineRange -> "L${subject.startLine + 1}-${subject.endLine + 1}"
            is Subject.File -> "whole file"
            is Subject.Files -> "${subject.fileUrls.size} files"
            Subject.Project -> "whole review"
        }

        private fun snippet(comment: ReviewComment): String =
            comment.body.trim().replace('\n', ' ').take(SNIPPET_CHARS)
    }

    /** A file grouping node's user object. */
    private data class FileGroup(val url: String, val label: String)

    private class BatchCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val userObject = (value as? DefaultMutableTreeNode)?.userObject
            when (userObject) {
                is FileGroup -> {
                    icon = AllIcons.FileTypes.Any_type
                    append(userObject.label)
                }

                is ReviewComment -> {
                    icon = AllIcons.General.Balloon
                    append(rangeLabel(userObject.subject) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val body = snippet(userObject)
                    append(body.ifBlank { "(no body)" })
                }
            }
        }
    }
}
