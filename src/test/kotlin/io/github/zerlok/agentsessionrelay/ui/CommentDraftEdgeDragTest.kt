package io.github.zerlok.agentsessionrelay.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Covers the edge-drag range mapping (`review-annotation`: "Resize the comment range by dragging its
 * edges") against a live editor: a press on a boundary Y claims the gesture, and a drag maps the
 * pointer Y back to the line the user is pointing at — the direction-aware step, since the bottom
 * edge Y is the *exclusive* bottom of the range's last row (already the next line's first row) while
 * the top edge Y is the inclusive top of its first one. Without the asymmetry a bottom drag released
 * on a row's own bottom boundary lands one line too far.
 *
 * The Ys are stated with the same editor visual-row calls the draft uses, so this pins the *mapping*,
 * not the row geometry: soft-wrap layout needs a real editor viewport width and stays a manual check
 * (change tasks 5.3). The range is read back off the draft's live wash highlighter — the view's own
 * position marker (ARCHITECTURE §3.2) — rather than through a test-only accessor.
 */
class CommentDraftEdgeDragTest : BasePlatformTestCase() {

    private lateinit var controller: CommentDraftController

    override fun setUp() {
        super.setUp()
        controller = CommentDraftController.getInstance(project)
        myFixture.configureByText("a.py", (0..9).joinToString("\n") { "line$it" } + "\n")
    }

    override fun tearDown() {
        try {
            // The draft is a project-service-held disposable and the light fixture's project outlives
            // the method; close it so the next test starts with no wash in the editor's markup.
            controller.close()
        } finally {
            super.tearDown()
        }
    }

    private val draft: CommentDraft get() = controller.activeDraft!!

    /** Opens a draft over [start]..[end] and asserts it actually opened (a box the editor can host). */
    private fun openDraft(start: Int, end: Int) {
        controller.open(myFixture.editor, start, end)
        assertNotNull("draft opened", controller.activeDraft)
        assertEquals(start to end, washRange())
    }

    /** The wash's CURRENT line range, read off the live highlighter the draft (re)creates on resize. */
    private fun washRange(): Pair<Int, Int> {
        val editor = myFixture.editor
        val wash = editor.markupModel.allHighlighters.single { it.customRenderer != null }
        val document = editor.document
        return document.getLineNumber(wash.startOffset) to document.getLineNumber(wash.endOffset)
    }

    /** Top boundary Y of [line]'s first visual row — where the draft's top edge sits. */
    private fun topY(line: Int): Int =
        myFixture.editor.visualLineToY(myFixture.editor.offsetToVisualLine(myFixture.editor.document.getLineStartOffset(line), false))

    /** Bottom boundary Y of [line]'s last visual row — where the draft's bottom edge sits. */
    private fun bottomY(line: Int): Int =
        myFixture.editor.visualLineToYRange(myFixture.editor.offsetToVisualLine(myFixture.editor.document.getLineEndOffset(line), false))[1]

    /**
     * The bottom edge grabbed at its own Y and released on line 5's bottom boundary makes 5 the last
     * line — not line 6, whose first row starts at that very Y.
     */
    fun `test dragging the bottom edge to a line's bottom boundary makes that line the end`() {
        openDraft(2, 2)

        assertTrue("press claims the bottom edge", draft.onMousePressed(bottomY(2), editingArea = true))
        assertTrue(draft.onMouseDragged(bottomY(5)))
        assertEquals(2 to 5, washRange())

        assertTrue(draft.onMouseReleased())
        assertEquals(2 to 5, washRange())
    }

    /** Releasing anywhere inside a row resolves to that row's line, not to a neighbour. */
    fun `test dragging the bottom edge inside a line's row makes that line the end`() {
        openDraft(2, 2)

        draft.onMousePressed(bottomY(2), editingArea = true)
        draft.onMouseDragged((topY(5) + bottomY(5)) / 2)

        assertEquals(2 to 5, washRange())
    }

    /** Dragging the bottom edge back up shrinks the range live, symmetrically. */
    fun `test dragging the bottom edge up shrinks the range`() {
        openDraft(2, 6)

        draft.onMousePressed(bottomY(6), editingArea = true)
        draft.onMouseDragged(bottomY(4))

        assertEquals(2 to 4, washRange())
    }

    /** The top edge maps its Y inclusively: grabbed and released on line 3's top, 3 becomes the start. */
    fun `test dragging the top edge moves the start line`() {
        openDraft(5, 7)

        assertTrue("press claims the top edge", draft.onMousePressed(topY(5), editingArea = true))
        assertTrue(draft.onMouseDragged(topY(3)))

        assertEquals(3 to 7, washRange())
    }

    /** Dragging an edge past the opposite one clamps to a single line rather than inverting the range. */
    fun `test the edges cannot cross`() {
        openDraft(4, 4)

        draft.onMousePressed(bottomY(4), editingArea = true)
        draft.onMouseDragged(bottomY(0))
        assertEquals(4 to 4, washRange())
        draft.onMouseReleased()

        draft.onMousePressed(topY(4), editingArea = true)
        draft.onMouseDragged(topY(9))
        assertEquals(4 to 4, washRange())
    }

    /** A press in the middle of a tall range touches no grab zone, so the draft leaves the gesture alone. */
    fun `test a press away from both edges does not claim the gesture`() {
        openDraft(1, 8)

        val middle = (topY(4) + bottomY(4)) / 2
        assertFalse(draft.onMousePressed(middle, editingArea = true))
        assertFalse("no drag without a claimed press", draft.onMouseDragged(bottomY(6)))
        assertEquals(1 to 8, washRange())
    }

    /** A press outside the editing area (the gutter) never starts a resize — that is the "+" affordance's. */
    fun `test a press outside the editing area does not claim the gesture`() {
        openDraft(2, 4)

        assertFalse(draft.onMousePressed(bottomY(4), editingArea = false))
        assertFalse(draft.onMouseDragged(bottomY(6)))
        assertEquals(2 to 4, washRange())
    }
}
