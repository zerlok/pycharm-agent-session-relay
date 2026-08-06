package io.github.zerlok.agentsessionrelay.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The width rule both inline surfaces size themselves by (`review-batch` "Render stored comments as an
 * inline card", design D4): `min(available, readingMeasure, rightMargin when configured)`.
 *
 * The rule is exercised over plain numbers through [InlineWidth.resolve], because the one case that
 * matters most — the editor's available width being the smallest of the three, which is the reported
 * defect — cannot be produced from a headless fixture, whose editor is never laid out. What the fixture
 * *can* answer is the other half of this file: that an editor with no [InlineWidthWatcher] still gets a
 * width, which is the path every other card/box test runs on.
 */
class InlineWidthTest : BasePlatformTestCase() {

    /**
     * The reported defect: the editor is narrower than the reading measure (a split, a shrunken window),
     * so the surface must be the editor's width — laying it out at the wider measure is what put the
     * card's Edit/Delete icons past the viewport's right edge.
     */
    fun `test a narrow editor wins over the reading measure and the right margin`() {
        assertEquals(300, InlineWidth.resolve(available = 300, rightMargin = 900, readingMeasure = 600))
    }

    /** A wide editor with a wide guide: the reading measure is what keeps the surface a column. */
    fun `test the reading measure caps a wide editor`() {
        assertEquals(600, InlineWidth.resolve(available = 1200, rightMargin = 900, readingMeasure = 600))
    }

    /** A configured right margin narrower than the reading measure still caps the surface. */
    fun `test the right margin caps below the reading measure`() {
        assertEquals(400, InlineWidth.resolve(available = 1200, rightMargin = 400, readingMeasure = 600))
    }

    /**
     * An unknown cap does not apply. An editor that is not laid out yet reports a zero-area viewport,
     * and treating that as "available = 0" would collapse every surface to nothing rather than leaving
     * it at its reading measure until the first resize arrives.
     */
    fun `test an unknown available width and an unset right margin leave the reading measure`() {
        assertEquals(600, InlineWidth.resolve(available = null, rightMargin = null, readingMeasure = 600))
    }

    /**
     * The no-watcher fallback (design D2): an editor [EditorReviewOverlayService] never saw — or a test
     * fixture — still gets a width, computed directly from the editor at call time. Every card and box
     * test in this suite runs on this path, so a regression here reads as a wall of unrelated failures.
     */
    fun `test an editor with no watcher still gets a width`() {
        myFixture.configureByText("a.py", "line0\n")

        assertNull("this fixture must have no watcher, or the fallback is not what is measured", InlineWidthWatcher.of(myFixture.editor))
        assertTrue("the fallback must produce a usable width", InlineWidth.baseWidthPx(myFixture.editor) > 0)
        assertEquals(
            "with no watcher the current width IS the directly computed one",
            InlineWidth.baseWidthPx(myFixture.editor),
            InlineWidth.currentWidthPx(myFixture.editor),
        )
    }

    /**
     * A very narrow guide column must not shrink a surface below its own chrome (the box's two-button
     * row), so the *right-margin* cap alone is floored. The floor deliberately does not apply to the
     * final width — a genuinely narrow editor must win, which is the case above.
     */
    fun `test a tiny right-margin column is floored rather than collapsing the cap`() {
        myFixture.configureByText("a.py", "line0\n")
        val editor = myFixture.editor
        editor.settings.setRightMargin(1)

        val floored = InlineWidth.rightMarginPx(editor) ?: error("a configured margin must cap")
        assertTrue(
            "a 1-column guide must be floored well above its own pixel width",
            floored > InlineWidth.columnsPx(editor, 1),
        )
        assertEquals("...and the floor is what the surface is then sized to", floored, InlineWidth.baseWidthPx(editor))
    }

    /** No configured guide means no right-margin cap — the reading measure is then the surface's width. */
    fun `test a disabled right margin does not cap`() {
        myFixture.configureByText("a.py", "line0\n")
        val editor = myFixture.editor
        editor.settings.setRightMargin(0)

        assertNull(InlineWidth.rightMarginPx(editor))
        assertEquals(InlineWidth.readingMeasurePx(), InlineWidth.baseWidthPx(editor))
    }
}
