package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shared width cap for the two inline review surfaces (comment-box-sizing). Both the authoring box
 * ([CommentDraft]) and the read-only card ([StoredCommentCard]) are `fullWidth = true`
 * [com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager] block inlays that would otherwise
 * stretch edge-to-edge and become unreadable on a wide monitor. Capping their *inner* panel at the
 * editor's configured right margin (the vertical guide column) gives the GitHub/GitLab reading-width
 * look **without** touching inlay placement semantics — the inlay stays full width, only the visible
 * box is narrowed and left-aligned. Both surfaces consult this single helper so they cap identically.
 */
object InlineWidth {

    // Floor for the cap (unscaled dp). A very narrow right-margin column (e.g. an 8-column guide) must
    // never shrink a box below its own chrome — the authoring box's "Cancel" + "Comment" button row —
    // so the cap is clamped up to this. The exact value is a visual taste-call: comfortably wider than
    // that two-button row so the buttons never clip.
    private const val MIN_CAP_DP = 320

    // Base (opening) width of both inline surfaces, in editor columns (comment-box-sizing feedback):
    // the authoring box and the read-only card both open ~80 columns wide — a comfortable reading /
    // typing width — instead of collapsing to their content (a short body, or the button row). Clamped
    // down to the right-margin cap when that column is narrower.
    private const val BASE_COLUMNS = 80

    /**
     * The pixel width of the editor's effective right margin — the vertical guide column resolved
     * per-file via [com.intellij.openapi.editor.EditorSettings.getRightMargin] for [Editor.getProject]
     * — converted from columns to pixels through the editor's plain space width
     * ([EditorUtil.getPlainSpaceWidth]). Returns `null` when the editor has no right margin configured
     * (guide disabled / a non-positive column); callers then leave the surface full-width, exactly as
     * before this change. A positive result is clamped up to [MIN_CAP_DP] so a narrow guide never
     * shrinks a box below its own buttons.
     */
    fun rightMarginPx(editor: Editor): Int? {
        val columns = editor.settings.getRightMargin(editor.project)
        if (columns <= 0) return null
        return maxOf(columnsPx(editor, columns), JBUI.scale(MIN_CAP_DP))
    }

    /**
     * Pixel width of [columns] editor columns, via the editor's plain space width
     * ([EditorUtil.getPlainSpaceWidth]). Used for the authoring box's *base* width — it opens ~80
     * columns wide rather than shrinking to its button row (comment-box-sizing feedback) — the same
     * column→pixel conversion [rightMarginPx] uses for the cap.
     */
    fun columnsPx(editor: Editor, columns: Int): Int = columns * EditorUtil.getPlainSpaceWidth(editor)

    /**
     * The base (opening) width both inline surfaces are pinned to: [BASE_COLUMNS] columns, clamped down
     * to the right-margin cap ([rightMarginPx]) when that column is narrower. Callers force this as the
     * surface's preferred width (keeping height content-driven), so a short comment card / empty box no
     * longer shrinks to its content — it opens at a comfortable reading width, never past the cap.
     */
    fun baseWidthPx(editor: Editor): Int {
        val base = columnsPx(editor, BASE_COLUMNS)
        val cap = rightMarginPx(editor)
        return if (cap != null) minOf(base, cap) else base
    }

    /**
     * Wraps [content] so it renders at most [cap] px wide, pinned to the leading (left) edge, while its
     * height stays fully content-driven (a typed body or a long comment still grows the box vertically).
     * A `null` [cap] means "no right margin configured" and returns [content] unchanged, preserving
     * today's full-width look.
     *
     * We keep the inlay `fullWidth = true` and constrain the *inner* panel: [content]'s maximum width is
     * pinned to [cap], and a [BoxLayout] row with a trailing horizontal glue absorbs every pixel beyond
     * [cap], so the block still lays out as a full-width row but the visible box occupies only the
     * leftmost [cap] px. Whether this reliably caps the *visible* width across themes/zoom is the design's
     * open question to confirm in a running IDE (tasks.md 1.2); the documented fallback there is switching
     * the surface to `fullWidth = false` with an explicit width.
     */
    fun capWidth(content: JComponent, cap: Int?): JComponent {
        if (cap == null) return content
        // Height is left unbounded (Int.MAX) so vertical growth is never clipped; only width is pinned.
        content.maximumSize = Dimension(cap, Int.MAX_VALUE)
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(content)
            // The glue takes all width past `cap`, keeping `content` clamped to its maximum width and
            // pinned to the left — the reading-width column, not an edge-to-edge stripe.
            add(Box.createHorizontalGlue())
        }
    }
}
