package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wires the presentation layer on project open by touching its project services so they initialize:
 * [RelayHoverService] (editor hover "+" listeners), [ReviewBatchNotifier] (batch-change
 * notifications), and [EditorReviewOverlayService] (the per-editor stored-comment gutter markers).
 * All are project services that dispose with the project.
 */
class RelayHoverInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<RelayHoverService>()
        project.service<ReviewBatchNotifier>()
        project.service<EditorReviewOverlayService>()
    }
}
