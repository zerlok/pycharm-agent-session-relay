package io.github.zerlok.agentsessionrelay.hover

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * GitHub/GitLab-style inline comment popup. For now the "Add" button only logs the comment
 * and shows a notification — persisting it into a review batch is the next change.
 */
object AddCommentPopup {

    private val LOG = logger<AddCommentPopup>()

    fun show(editor: Editor, line: Int) {
        val textArea = JBTextArea(6, 60).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        val addButton = JButton("Add")
        val cancelButton = JButton("Cancel")
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            add(cancelButton)
            add(addButton)
        }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = JBUI.size(440, 200)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textArea)
            .setTitle("Add review comment — line ${line + 1}")
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(false)
            .createPopup()

        addButton.addActionListener {
            val body = textArea.text?.trim().orEmpty()
            val file = FileDocumentManager.getInstance().getFile(editor.document)
            LOG.info("Relay add-comment: file=${file?.path} line=${line + 1} body=\"$body\"")
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Agent Session Relay")
                .createNotification(
                    "Relay: comment captured (not yet stored)",
                    "line ${line + 1}" + if (body.isBlank()) "" else " — " + body.take(120),
                    NotificationType.INFORMATION,
                )
                .notify(editor.project)
            popup.cancel()
        }
        cancelButton.addActionListener { popup.cancel() }

        val anchor = editor.logicalPositionToXY(LogicalPosition(line, 0))
        popup.show(RelativePoint(editor.contentComponent, anchor))
    }
}
