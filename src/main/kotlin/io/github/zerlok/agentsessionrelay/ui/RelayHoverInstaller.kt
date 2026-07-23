package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wires the presentation layer on project open by touching its project services so they initialize:
 * [RelayHoverService] (editor hover "+" listeners), [ReviewBatchNotifier] (batch-change
 * notifications), [EditorReviewOverlayService] (the per-editor stored-comment gutter markers), and
 * [AgentSessionNotifier] (agent session turn-complete / needs-input notifications, task 5.1/5.2).
 * All are project services that dispose with the project. The notifier must be touched here so it
 * subscribes to the app-level registry topic on project open even before the Agent Sessions tool
 * window is first opened (a bare `@Service` is otherwise instantiated only on first use).
 */
class RelayHoverInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<RelayHoverService>()
        project.service<ReviewBatchNotifier>()
        project.service<EditorReviewOverlayService>()
        project.service<AgentSessionNotifier>()
    }
}
