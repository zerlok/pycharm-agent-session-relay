package io.github.zerlok.agentsessionrelay.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers [AddReviewCommentAction]'s target-range resolution (design D2): it feeds the **caret** line
 * to the one shared rule, `controller.rangeFor(editor, editor.caretModel.logicalPosition.line)` —
 * reproduced here against a live editor fixture. Since a caret always rests at one end of its own
 * selection, that line is inside the selection's span whenever a selection exists, which is what keeps
 * this right-click entry point in parity with the gutter "+" (covered from the clicked-line side by
 * [CommentDraftControllerTest]). Every selection here therefore carries an explicit `<caret>` at one
 * of its ends — the state a real drag-select leaves behind. The draft-opening step itself needs a
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
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2<caret></selection>\nline3\n")

        assertEquals(1 to 2, resolvedRange())
    }

    /** A bottom-up drag leaves the caret at the selection's *start* — same range, same parity. */
    fun `test a selection with the caret at its start resolves to the selected line range`() {
        myFixture.configureByText("a.py", "line0\n<selection><caret>line1\nline2</selection>\nline3\n")

        assertEquals(1 to 2, resolvedRange())
    }

    /**
     * The parity boundary: a top-down drag to the start of line 2 leaves the caret on line 2 while the
     * trimmed range is 1..1. The trailing line is excluded from the range but still counts as inside
     * the span, so the right-click resolves to the same 1..1 the gutter "+" gives for this selection —
     * it does not collapse to the caret's own line.
     */
    fun `test a selection ending at a line start excludes the trailing line`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\n<caret></selection>line2\nline3\n")

        assertEquals(1 to 1, resolvedRange())
    }
}
