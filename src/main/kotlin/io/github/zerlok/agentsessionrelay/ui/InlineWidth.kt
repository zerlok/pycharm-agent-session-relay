package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The width rule shared by the two inline review surfaces — the authoring box ([CommentDraft]) and the
 * read-only card ([StoredCommentCard]). Both are `fullWidth = true`
 * [com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager] block inlays that would otherwise
 * stretch edge-to-edge and become unreadable on a wide monitor, so both cap their *inner* panel here
 * and cap identically.
 *
 * The rule is `min(available, readingMeasure, rightMargin when configured)` (design D4), and the whole
 * point of `responsive-inline-comment-surfaces` is **where a surface reads it from**. Width is an input
 * that flows *down* from the editor; height stays an output of content, and no measure path may read
 * the surface's own width (design D1 — that read is what once pegged the CPU with a layout feedback
 * loop). So:
 *
 * - [currentWidthPx] is what a surface measures at. It answers from the editor's [InlineWidthWatcher]
 *   when one is installed, so a resize changes the number every surface on that editor sees at once.
 * - [baseWidthPx] computes the same rule directly from the editor. It is both what the watcher
 *   recomputes on each resize and the fallback for an editor that has no watcher (one
 *   [EditorReviewOverlayService] never saw, or a test fixture) — today's behaviour minus the staleness.
 */
object InlineWidth {

    // Floor for the right-margin cap (unscaled dp). A very narrow right-margin column (e.g. an
    // 8-column guide) must never shrink a box below its own chrome — the authoring box's "Cancel" +
    // "Comment" button row — so that cap is clamped up to this. The exact value is a visual taste-call:
    // comfortably wider than that two-button row so the buttons never clip. Deliberately NOT applied to
    // the final width: when the *editor* is genuinely this narrow, a surface wider than the viewport is
    // the reported defect (icons off screen), not a fix for it.
    private const val MIN_CAP_DP = 320

    // The reading measure (design D4), in UI-font-size units rather than editor columns: a body
    // rendered in the proportional UI font ([RelayStyle.bodyFont]) makes a monospace column count the
    // wrong ruler. These are the platform's own review-comment numbers — 42 characters at the default
    // system font size (`CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH`) plus its 52dp chrome allowance —
    // which land within a few dozen px of the 80 editor columns they replace.
    private const val READING_MEASURE_CHARS = 42
    private const val READING_MEASURE_CHROME_DP = 52

    /**
     * The width a surface on [editor] must measure and lay out at **right now**: the watcher's live
     * value when [editor] has one, else the rule computed directly ([baseWidthPx]).
     *
     * Surfaces *pull* this on every measure pass rather than caching a copy pushed at build time, so a
     * surface can never hold a width the editor no longer has. It is still an input — computed from the
     * viewport, which no surface influences — so D1's invariant holds: `getPreferredSize` and `doLayout`
     * read one number per pass, and that number is never the surface's own width.
     */
    fun currentWidthPx(editor: Editor): Int = InlineWidthWatcher.of(editor)?.width ?: baseWidthPx(editor)

    /**
     * The width rule computed straight from [editor]: `min(available, readingMeasure, rightMargin)`.
     * [InlineWidthWatcher] recomputes exactly this on every editor resize, and [currentWidthPx] falls
     * back to it for an editor with no watcher.
     */
    fun baseWidthPx(editor: Editor): Int =
        resolve(availableWidthPx(editor), rightMarginPx(editor), readingMeasurePx())

    /**
     * The rule itself, over plain numbers so it can be exercised without an editor. A `null` cap means
     * "not known / not configured" and simply does not apply — in particular an unknown [available]
     * (an editor that is not laid out yet) must leave the surface at its reading measure rather than
     * collapsing it to nothing.
     */
    internal fun resolve(available: Int?, rightMargin: Int?, readingMeasure: Int): Int {
        var width = readingMeasure
        if (rightMargin != null) width = minOf(width, rightMargin)
        if (available != null) width = minOf(width, available)
        return width.coerceAtLeast(1)
    }

    /** The comfortable reading measure both surfaces are capped at when nothing narrower applies. */
    fun readingMeasurePx(): Int =
        JBUI.scale(Math.round(JBUIScale.DEF_SYSTEM_FONT_SIZE * READING_MEASURE_CHARS) + READING_MEASURE_CHROME_DP)

    /**
     * The editor's currently available content width, or `null` when the editor is not laid out yet
     * (a zero-area viewport), in which case no such cap applies.
     *
     * Source: [com.intellij.openapi.editor.ScrollingModel.getVisibleArea], resolving design Open
     * Question 5. It is the scroll pane's viewport rect, so the gutter (the scroll pane's row header)
     * and the vertical scrollbar are already outside it — subtracting them again, as
     * `EditorTextWidthWatcher` does from the raw viewport component, would under-size the surface here.
     * It is also the same quantity the platform sizes a `fullWidth` block inlay's row from, so a
     * surface capped at it is capped at exactly the row it is laid out in.
     */
    fun availableWidthPx(editor: Editor): Int? = editor.scrollingModel.visibleArea.width.takeIf { it > 0 }

    /**
     * The editor's effective right margin in pixels — the vertical guide column resolved per-file via
     * [com.intellij.openapi.editor.EditorSettings.getRightMargin] for [Editor.getProject], converted
     * from columns through the editor's plain space width ([EditorUtil.getPlainSpaceWidth]) and clamped
     * up to [MIN_CAP_DP] so a narrow guide never shrinks a surface below its own buttons. `null` when no
     * margin is configured (guide disabled or a non-positive column), in which case it does not cap.
     */
    fun rightMarginPx(editor: Editor): Int? {
        val columns = editor.settings.getRightMargin(editor.project)
        return if (columns > 0) maxOf(columnsPx(editor, columns), JBUI.scale(MIN_CAP_DP)) else null
    }

    /**
     * Pixel width of [columns] editor columns, via the editor's plain space width
     * ([EditorUtil.getPlainSpaceWidth]) — the column→pixel conversion [rightMarginPx] caps with.
     */
    fun columnsPx(editor: Editor, columns: Int): Int = columns * EditorUtil.getPlainSpaceWidth(editor)

    /**
     * Wraps [content] so it renders pinned to the leading (left) edge of the full-width inlay row,
     * while its height stays fully content-driven (a typed body or a long comment still grows the box
     * vertically).
     *
     * We keep the inlay `fullWidth = true` and constrain the *inner* panel: a [BoxLayout] row with a
     * trailing horizontal glue absorbs every pixel beyond [content]'s own maximum width, so the block
     * still lays out as a full-width row but the visible surface occupies only the leftmost
     * [currentWidthPx] px. The cap itself lives in the surface's own `getMaximumSize` — that is what
     * makes it track the editor rather than being frozen here at build time.
     *
     * Whether this reliably caps the *visible* width across themes/zoom is still the design's open
     * question to confirm in a running IDE; the documented fallback is the platform's purpose-built
     * `Editor.addComponentInlay(offset, InlayProperties(), component, ComponentInlayAlignment
     * .FIT_VIEWPORT_WIDTH)`, which would replace most of this object.
     */
    fun pinLeading(content: JComponent): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(content)
        // The glue takes all width past the surface's maximum, keeping `content` clamped to it and
        // pinned to the left — the reading-width column, not an edge-to-edge stripe.
        add(Box.createHorizontalGlue())
    }
}
