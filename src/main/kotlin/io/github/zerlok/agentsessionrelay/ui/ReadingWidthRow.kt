package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.ex.EditorEx
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The row an inline review surface is laid out in: the platform stretches this to the editor's
 * viewport width (`ComponentInlayAlignment.FIT_VIEWPORT_WIDTH`), and it lays its single child out at
 * `min(row width, reading measure)`, pinned to the leading edge.
 *
 * This is the plugin's whole share of the width rule. Everything else — what the viewport width
 * *is*, and re-laying the row out when a split or a window resize changes it — is the platform's.
 * It mirrors what the platform's own review comments do (`CodeReviewComponentInlayRenderer` wraps
 * its content in a width-restricted panel and hands the result to `FIT_VIEWPORT_WIDTH`); the
 * wrapping panel is ours only because that one lives in `com.intellij.collaboration.*`, which
 * ARCHITECTURE.md — "Platform API policy" rules out as a dependency.
 *
 * **Width flows one way.** [contentWidthPx] reads this row's own width — imposed from above, from
 * the viewport — and never the child's, so the child's preferred size cannot feed back into the
 * width it is measured at.
 *
 * The child is set after construction so it can be built against [contentWidthPx] — the child
 * measures its wrapping body at the width this row will give it, and needs to ask before it exists.
 */
internal class ReadingWidthRow(private val editor: EditorEx) : JPanel(null) {

    private var content: JComponent? = null

    init {
        // The row spans the whole viewport while the surface occupies only its leading part, so an
        // opaque row paints a panel-coloured band across the rest of the line — a JPanel is opaque by
        // default, and that band is what the code underneath must show through.
        isOpaque = false
    }

    fun setContent(component: JComponent) {
        content?.let { remove(it) }
        content = component
        add(component)
    }

    /**
     * The width the child is measured and laid out at: the row's own width, less whatever the editor's
     * floating inspections widget covers, capped at the reading measure. Before the first layout pass
     * this row has no width of its own, and the reading measure — not zero — is the right answer: a
     * surface that has not been laid out yet must not collapse.
     */
    fun contentWidthPx(): Int {
        val cap = InlineWidth.readingMeasurePx()
        if (width <= 0) return cap
        // The row spans the viewport, but the inspections widget floats over the viewport's trailing
        // edge without being in its layout, so the usable width is narrower than the row.
        val usable = width - InlineWidth.overlayInsetPx(editor.scrollPane)
        return minOf(usable, cap).coerceAtLeast(1)
    }

    /**
     * Width is the capped width; height comes from the child, measured at that width. The platform
     * reads this height to size the inlay's row, so a body that wraps to more lines grows the row.
     */
    override fun getPreferredSize(): Dimension =
        Dimension(contentWidthPx(), content?.preferredSize?.height ?: 0)

    /**
     * Zero, so the row is always exactly the width the platform gives it.
     *
     * `FIT_VIEWPORT_WIDTH` lays the component out at `max(minimumSize.width, viewport − scrollbar)`,
     * so this value is a floor the viewport cannot pull the row below. It must not be derived from the
     * child: `Component.getMinimumSize` caches its result from the component's **current size** when
     * no explicit minimum was set, so a child that has been laid out once reports its laid-out width
     * as its minimum. The floor then ratchets up to whatever width the row last had, and the row can
     * never shrink again — the editor is resized and the comment keeps its old width.
     *
     * There is nothing to protect with a floor anyway: the row is a container, and the surface inside
     * it is sized by [contentWidthPx], which already applies both the reading-measure cap and the
     * widget reserve.
     */
    override fun getMinimumSize(): Dimension = Dimension(0, 0)

    override fun doLayout() {
        content?.setBounds(0, 0, contentWidthPx(), height)
    }
}
