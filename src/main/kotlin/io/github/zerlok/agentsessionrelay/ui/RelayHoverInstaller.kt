package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wires the presentation layer on project open by touching its project services so they initialize:
 * [RelayHoverService] (the editor hover "+"), [ReviewBatchNotifier] (batch-change notifications),
 * and [EditorReviewOverlayService] (the stored-comment markers and cards). All dispose with the
 * project.
 *
 * NOTE: `execute` runs off the EDT, so a service it touches here must marshal any editor UI work in
 * its constructor onto the EDT itself.
 */
class RelayHoverInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<RelayHoverService>()
        project.service<ReviewBatchNotifier>()
        project.service<EditorReviewOverlayService>()
    }
}
