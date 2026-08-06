package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.util.Key
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent

/**
 * The live width source for one editor's inline review surfaces (design D2). It recomputes
 * [InlineWidth.baseWidthPx] whenever the editor's geometry changes and asks every registered surface to
 * re-measure, which is what makes a card or a box **track** the editor instead of keeping the width it
 * was built with — the staleness that left a narrowed card's Edit/Delete icons laid out past the
 * viewport's right edge.
 *
 * One watcher per editor, created and disposed by [EditorReviewOverlayService] alongside the overlay and
 * published on the editor itself ([of]), because the two surfaces are built from different places — the
 * card by the per-editor [EditorReviewOverlay], the box by the per-project [CommentDraftController] —
 * and an editor-keyed lookup is what lets both read one width without a dependency edge between them.
 * A per-surface listener would instead attach N listeners to one editor and recompute the same number
 * once per card on every resize event.
 *
 * The width is **pulled**, not pushed: a notified surface re-reads [InlineWidth.currentWidthPx] during
 * its next measure pass rather than being handed a copy, so no surface can hold a width the editor no
 * longer has. What the watcher hands out is the *trigger* — for the card an immediate `revalidate()`,
 * for the box the same deferred re-measure a body edit uses (design D3: one re-measure path, not two).
 *
 * EDT-only, like everything that touches Swing or inlays (ARCHITECTURE §5.3): component and
 * visible-area callbacks arrive on the EDT, and [EditorReviewOverlayService] installs it there.
 */
internal class InlineWidthWatcher private constructor(private val editor: Editor) :
    ComponentAdapter(), VisibleAreaListener, Disposable {

    // Identity-keyed by the component handed to the inlay: a surface attaches on build and detaches when
    // its inlay is disposed, so a registration can never outlive the component it revalidates.
    private val surfaces = LinkedHashMap<JComponent, () -> Unit>()

    /**
     * The width every surface on this editor currently measures at — the rule computed for the editor's
     * present geometry. Stored so a recompute that yields the same number costs nothing beyond the
     * comparison (a window-edge drag fires a stream of resize events).
     */
    var width: Int = InlineWidth.baseWidthPx(editor)
        private set

    /** Registered surfaces — a test seam for "no registration outlives its surface". */
    internal val surfaceCount: Int get() = surfaces.size

    // The three component events the platform's own inlay width watcher reacts to.
    override fun componentResized(e: ComponentEvent) = refresh()

    override fun componentShown(e: ComponentEvent) = refresh()

    override fun componentHidden(e: ComponentEvent) = refresh()

    /**
     * Also on any visible-area change, which is what catches the two width inputs that move without the
     * editor's component being resized: the editor font size (Ctrl+scroll re-lays out the content and
     * shifts the viewport, and the font size is what converts the right-margin column to pixels) and a
     * viewport change from a scroll pane the component event never reaches. Scrolling fires this too and
     * costs one recompute plus a comparison, because [refresh] short-circuits on an unchanged width.
     */
    override fun visibleAreaChanged(e: VisibleAreaEvent) = refresh()

    /**
     * Registers [surface] — the component handed to the inlay — to be re-measured by [remeasure] on
     * every genuine width change. The surface reads the width itself; this only says *when*.
     */
    fun attach(surface: JComponent, remeasure: () -> Unit) {
        surfaces[surface] = remeasure
    }

    /** Unregisters [surface]; called when its inlay is disposed (a closed box, a removed card). */
    fun detach(surface: JComponent) {
        surfaces.remove(surface)
    }

    /**
     * Recomputes the width and, **only when it actually changed**, asks every registered surface to
     * re-measure. The short-circuit is what keeps a window-edge drag from fanning a revalidate per event
     * out to every card in the file.
     */
    fun refresh() {
        val next = InlineWidth.baseWidthPx(editor)
        if (next == width) return
        width = next
        // Copied: a re-measure can dispose a surface (and so detach it) while we iterate.
        for (remeasure in surfaces.values.toList()) remeasure()
    }

    override fun dispose() {
        editor.component.removeComponentListener(this)
        if (editor.getUserData(KEY) === this) editor.putUserData(KEY, null)
        surfaces.clear()
    }

    companion object {

        private val KEY = Key.create<InlineWidthWatcher>("io.github.zerlok.agentsessionrelay.inlineWidthWatcher")

        /** The watcher for [editor], or `null` when nothing installed one (see [InlineWidth.currentWidthPx]). */
        fun of(editor: Editor): InlineWidthWatcher? = editor.getUserData(KEY)

        /**
         * Creates the watcher for [editor], publishes it on the editor and starts listening. The caller
         * owns its disposal ([EditorReviewOverlayService], in `editorReleased`), which unpublishes it.
         */
        fun install(editor: Editor): InlineWidthWatcher {
            val watcher = InlineWidthWatcher(editor)
            editor.putUserData(KEY, watcher)
            // The editor's own component: it is what a split, a window resize or a tool-window toggle
            // resizes, and the viewport width follows it.
            editor.component.addComponentListener(watcher)
            // Parented to the watcher, so this listener goes with it; the component listener above has
            // no Disposable overload and is removed in dispose().
            editor.scrollingModel.addVisibleAreaListener(watcher, watcher)
            return watcher
        }
    }
}
