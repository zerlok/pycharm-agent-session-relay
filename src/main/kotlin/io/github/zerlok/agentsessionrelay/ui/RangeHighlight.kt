package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.util.ui.JBUI
import java.awt.Color

/**
 * The one shared "range + gutter" highlight visual used by the two surfaces that reveal a commented
 * line range (design D3): the draft's live wash ([CommentDraft]) and the transient stored-comment
 * hover highlight ([EditorReviewOverlay]). It pairs the familiar code-area wash (a `backgroundColor`
 * over [HighlighterTargetArea.LINES_IN_RANGE]) with a colored bar painted in the left gutter — the
 * same [LineMarkerRenderer] hook VCS change bars use — so the commented lines are identifiable from
 * the line-number gutter as well as the code, and the draft and stored-hover highlights read as one
 * highlight by construction (both use [CommentDraft.RANGE_BACKGROUND]).
 */
class RangeHighlight private constructor(
    private val editor: Editor,
    private val highlighter: RangeHighlighter,
) {

    /** Removes the wash + gutter bar. Idempotent and safe once the marker has been invalidated. */
    fun dispose() {
        if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter)
    }

    companion object {
        // Width (unscaled dp) of the gutter bar painted beside the line numbers — a VCS-change-bar-sized
        // stripe: wide enough to read at a glance, narrow enough not to crowd the numbers (D3).
        private const val BAR_WIDTH_DP = 3

        /**
         * A [LineMarkerRenderer] that fills a [color] bar in the left gutter for its marker's line
         * range. Shared so the draft ([CommentDraft.createHighlighter]) can attach the same bar to its
         * own wash highlighter and [create] can build a standalone range highlight — keeping the two
         * surfaces' gutter signal identical.
         */
        fun gutterBar(color: Color): LineMarkerRenderer = LineMarkerRenderer { _, g, r ->
            g.color = color
            g.fillRect(r.x, r.y, JBUI.scale(BAR_WIDTH_DP), r.height)
        }

        /**
         * Builds a standalone range highlight over [startLine]..[endLine] (inclusive, clamped to the
         * document): a [color] wash over the code area plus the shared [gutterBar]. Rides the editor's
         * own markup (like the draft wash), so it is scoped to this editor and view-only. Used for the
         * transient stored-comment hover highlight; call [dispose] to remove it.
         */
        fun create(editor: Editor, startLine: Int, endLine: Int, color: Color): RangeHighlight {
            val document = editor.document
            val lastLine = (document.lineCount - 1).coerceAtLeast(0)
            val start = startLine.coerceIn(0, lastLine)
            val end = endLine.coerceIn(start, lastLine)
            val highlighter = editor.markupModel.addRangeHighlighter(
                document.getLineStartOffset(start),
                document.getLineEndOffset(end),
                HighlighterLayer.SELECTION - 1,
                TextAttributes().apply { backgroundColor = color },
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.lineMarkerRenderer = gutterBar(color)
            return RangeHighlight(editor, highlighter)
        }
    }
}
