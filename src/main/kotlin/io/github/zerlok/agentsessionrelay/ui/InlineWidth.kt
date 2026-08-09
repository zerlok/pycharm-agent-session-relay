package io.github.zerlok.agentsessionrelay.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * The one width number the plugin owns: the comfortable reading measure both inline surfaces — the
 * authoring box ([CommentDraft]) and the read-only card ([StoredCommentCard]) — are capped at, so
 * neither becomes an unreadable edge-to-edge stripe on a wide monitor.
 *
 * Everything else about width belongs to the platform. The surfaces are placed with
 * `ComponentInlayAlignment.FIT_VIEWPORT_WIDTH`, which lays their row out at the editor's viewport
 * width less its vertical scrollbar and re-lays it out on every visible-area and content change;
 * the cap and the floating-widget reserve ([overlayInsetPx]) are applied inside that row by
 * [ReadingWidthRow]. See UI.md — "Sizing".
 *
 * The editor's right margin is deliberately **not** a second cap: the platform's rule has no such
 * term, and the reading measure is UI-font-relative where a margin is editor-column-relative.
 */
object InlineWidth {

    // The reading measure, in UI-font-size units rather than editor columns: a body rendered in the
    // proportional UI font ([RelayStyle.bodyFont]) makes a monospace column count the wrong ruler.
    // These are the platform's own review-comment numbers — 42 characters at the default system font
    // size (`CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH`) plus its 52dp chrome allowance.
    private const val READING_MEASURE_CHARS = 42
    private const val READING_MEASURE_CHROME_DP = 52

    /** The comfortable reading measure both surfaces are capped at. */
    fun readingMeasurePx(): Int =
        JBUI.scale(Math.round(JBUIScale.DEF_SYSTEM_FONT_SIZE * READING_MEASURE_CHARS) + READING_MEASURE_CHROME_DP)

    /**
     * How many pixels of the viewport's trailing edge are covered by the editor's **inspections
     * widget** — the floating "no problems found" toolbar at the top right.
     *
     * That widget is handed to the scroll pane via [JBScrollPane.setStatusComponent] and floats *over*
     * the content area: it is not in the viewport's layout, so `FIT_VIEWPORT_WIDTH` (which subtracts
     * only the vertical scrollbar) lays a surface out underneath it and the card's trailing Edit and
     * Delete icons end up unreachable. Reserving this much keeps a surface clear of it.
     *
     * Measured from the components' **actual laid-out bounds** rather than derived from the widget's
     * width: the widget partly overhangs the scrollbar, which is already outside the viewport, so its
     * width is not what it costs the content area. Taking the distance from the widget's leading edge
     * to the viewport's trailing edge answers the only question that matters — how much of the
     * viewport it covers — without depending on how the scroll pane positions it.
     *
     * Zero when the widget is absent, hidden, or entirely over the scrollbar. The reserve is
     * unconditional rather than applied only to rows the widget currently overlaps: the widget is
     * fixed to the top of the *viewport* while a surface sits in the *document*, so which rows it
     * covers changes on every scroll, and sizing on that would re-wrap comments while the user
     * scrolls past them.
     */
    fun overlayInsetPx(scrollPane: JScrollPane?): Int {
        val status = (scrollPane as? JBScrollPane)?.statusComponent?.takeIf { it.isVisible && it.width > 0 } ?: return 0
        val parent = status.parent ?: return 0
        val viewport = scrollPane.viewport ?: return 0
        val statusBounds = SwingUtilities.convertRectangle(parent, status.bounds, scrollPane)
        return (viewport.x + viewport.width - statusBounds.x).coerceAtLeast(0)
    }
}
