package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wires the presentation layer on project open: touches [RelayHoverService] so it registers the
 * editor hover listeners, and [ReviewBatchNotifier] so it subscribes to batch changes. Both are
 * project services that dispose with the project.
 */
class RelayHoverInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<RelayHoverService>()
        project.service<ReviewBatchNotifier>()
    }
}
