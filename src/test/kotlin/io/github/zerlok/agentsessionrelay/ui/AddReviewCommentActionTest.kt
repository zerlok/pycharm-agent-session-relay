package io.github.zerlok.agentsessionrelay.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers [AddReviewCommentAction]'s target-range resolution (design D2): the range is the current
 * selection when one exists, otherwise the single **caret** line. The action computes it exactly as
 * `controller.rangeFor(editor, editor.caretModel.logicalPosition.line)` — reproduced here against a
 * live editor fixture — so both this right-click entry point and the gutter "+" (which funnels through
 * the same [CommentDraftController.rangeFor]) stay in parity. The draft-opening step itself needs a
 * display, so it is out of scope for a headless unit test; the range decision is what has logic.
 */
class AddReviewCommentActionTest : BasePlatformTestCase() {

    private fun resolvedRange(): Pair<Int, Int> {
        val controller = CommentDraftController.getInstance(project)
        val editor = myFixture.editor
        return controller.rangeFor(editor, editor.caretModel.logicalPosition.line)
    }

    /** No selection: the caret's line becomes a single-line range (start == end), like a gutter click. */
    fun `test no selection resolves to the caret line`() {
        myFixture.configureByText("a.py", "line0\nline1\nlin<caret>e2\nline3\n")

        assertEquals(2 to 2, resolvedRange())
    }

    /** A multi-line selection wins over the caret: its full line span is the target range. */
    fun `test a selection resolves to the selected line range`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2</selection>\nline3\n")

        assertEquals(1 to 2, resolvedRange())
    }

    /** A selection ending exactly at a line start excludes that trailing line (same rule as the "+"). */
    fun `test a selection ending at a line start excludes the trailing line`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\n</selection>line2\nline3\n")

        assertEquals(1 to 1, resolvedRange())
    }
}
