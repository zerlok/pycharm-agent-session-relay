package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.editor.Inlay
import javax.swing.JComponent

/**
 * The Swing component an inline review surface's inlay carries.
 *
 * Under `EditorEmbeddedComponentManager` the renderer *was* the component, so tests read it with a
 * cast. `Editor.addComponentInlay` puts the component behind a [ComponentInlayRenderer] instead, and
 * that indirection is a platform detail no individual test should have to know.
 */
internal val Inlay<*>.surfaceComponent: JComponent
    get() = (renderer as ComponentInlayRenderer<*>).component as JComponent
