package io.github.zerlok.agentsessionrelay.hover

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project

/**
 * Project-scoped owner of the editor hover listeners. Registering on the editor-factory
 * event multicaster covers every editor in the IDE; the listener filters to this project.
 */
@Service(Service.Level.PROJECT)
class RelayHoverService(project: Project) : Disposable {

    init {
        val listener = RelayHoverListener(project)
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addEditorMouseMotionListener(listener, this)
        multicaster.addEditorMouseListener(listener, this)
    }

    override fun dispose() {
        // Listeners are unregistered automatically: they were added with `this` as parent disposable.
    }
}
