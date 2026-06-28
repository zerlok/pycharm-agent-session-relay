package io.github.zerlok.agentsessionrelay.hover

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Touches [RelayHoverService] on project open so it registers the editor hover listeners.
 * All real work lives in the service, which disposes with the project.
 */
class RelayHoverInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<RelayHoverService>()
    }
}
