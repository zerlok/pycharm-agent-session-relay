package io.github.zerlok.agentsessionrelay.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers the one rule both comment entry points share, [CommentDraftController.rangeFor], from the
 * gutter "+" side — where the clicked line is wherever the pointer was, independent of the caret and
 * of any selection elsewhere in the file (`review-annotation`: "Clicking outside the selection
 * comments the clicked line"). [AddReviewCommentActionTest] covers the same rule from the right-click
 * side, where the clicked line is always the caret's.
 *
 * The containment test deliberately uses the selection's **untrimmed** line span while the
 * trailing-line trim applies to the returned range only, so both spellings of "the trailing line" are
 * asserted here: excluded from the range, still inside the span.
 */
class CommentDraftControllerTest : BasePlatformTestCase() {

    private fun rangeFor(clickedLine: Int): Pair<Int, Int> =
        CommentDraftController.getInstance(project).rangeFor(myFixture.editor, clickedLine)

    /** No selection at all: the clicked line becomes a single-line range. */
    fun `test no selection resolves to the clicked line`() {
        myFixture.configureByText("a.py", "line0\nline1\nline2\nline3\nline4\n")

        assertEquals(2 to 2, rangeFor(2))
    }

    /** A click inside the selection adopts it — including on either of its boundary lines. */
    fun `test a click inside the selection resolves to the selected range`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2\nline3</selection>\nline4\n")

        assertEquals(1 to 3, rangeFor(2))
        assertEquals(1 to 3, rangeFor(1))
        assertEquals(1 to 3, rangeFor(3))
    }

    /** A click below the selection ignores it: the box must open where the user clicked, not elsewhere. */
    fun `test a click below the selection resolves to the clicked line`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2</selection>\nline3\nline4\n")

        assertEquals(4 to 4, rangeFor(4))
    }

    /** Same for a click above the selection — the side the old "selection always wins" rule also broke. */
    fun `test a click above the selection resolves to the clicked line`() {
        myFixture.configureByText("a.py", "line0\nline1\n<selection>line2\nline3</selection>\nline4\n")

        assertEquals(0 to 0, rangeFor(0))
    }

    /** A selection ending exactly at a line start does not reach into that trailing line. */
    fun `test a selection ending at a line start excludes the trailing line`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2\n</selection>line3\nline4\n")

        assertEquals(1 to 2, rangeFor(1))
    }

    /**
     * ...but that same trailing line is still *inside* the span the containment test uses, so clicking
     * it adopts the trimmed selection instead of collapsing to it. This is the case a trimmed-span
     * containment test would break, and with it the right-click parity requirement.
     */
    fun `test the excluded trailing line still counts as inside the selection`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2\n</selection>line3\nline4\n")

        assertEquals(1 to 2, rangeFor(3))
    }

    /** The line after the excluded trailing one is genuinely outside: it collapses to the clicked line. */
    fun `test the line past the trailing line is outside the selection`() {
        myFixture.configureByText("a.py", "line0\n<selection>line1\nline2\n</selection>line3\nline4\n")

        assertEquals(4 to 4, rangeFor(4))
    }

    /** A within-line selection is still a selection: a click on its line adopts that single line. */
    fun `test a partial single-line selection resolves to that line`() {
        myFixture.configureByText("a.py", "line0\nli<selection>ne</selection>1\nline2\n")

        assertEquals(1 to 1, rangeFor(1))
        assertEquals(2 to 2, rangeFor(2))
    }
}
