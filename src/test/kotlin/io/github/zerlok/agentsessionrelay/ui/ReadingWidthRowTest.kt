package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.addComponentInlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The whole of the width rule Relay still owns after the surfaces moved onto the platform's
 * viewport-fitting inlay placement: `min(row width − floating-widget reserve, reading measure)`,
 * applied to the row's single child (`review-annotation` / `review-batch` "… is capped at a reading
 * measure" and "… stays clear of the editor's inspections widget").
 *
 * The row's *own* width is the platform's business — it is set from the editor's viewport by
 * `ComponentInlayAlignment.FIT_VIEWPORT_WIDTH` — so it is imposed here rather than derived, which is
 * also the one thing a headless fixture could never produce from a real editor.
 */
class ReadingWidthRowTest : BasePlatformTestCase() {

    private lateinit var editorEx: EditorEx

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.py", "line0\nline1\n")
        editorEx = myFixture.editor as EditorEx
    }

    /**
     * The regression the maintainer saw as "a gray area by the right side of the comment": the row
     * spans the whole viewport while the surface fills only its leading part, so an opaque row paints
     * a panel-coloured band across the rest of the line. `JPanel` is opaque by default, which is
     * exactly how this shipped.
     */
    fun `test the row paints nothing of its own`() {
        val (row, _) = rowWithChild()

        assertFalse("the code under the row must show through", row.isOpaque)
    }

    /** A viewport wider than the measure: the measure is the cap, so the surface stays a column. */
    fun `test a wide row caps its child at the reading measure`() {
        val (row, child) = rowWithChild()

        row.setSize(InlineWidth.readingMeasurePx() * 3, 100)
        row.doLayout()

        assertEquals(InlineWidth.readingMeasurePx(), child.width)
        assertEquals("...and that is what the row reports upward", InlineWidth.readingMeasurePx(), row.preferredSize.width)
    }

    /**
     * The narrow case — a split, a shrunken window — where the viewport, not the measure, decides. This
     * is the defect the previous two width fixes were about: a surface laid out wider than the viewport
     * puts its trailing controls off screen.
     */
    fun `test a narrow row gives its child the row's own width`() {
        val (row, child) = rowWithChild()
        val narrow = InlineWidth.readingMeasurePx() / 2

        row.setSize(narrow, 100)
        row.doLayout()

        assertEquals(narrow, child.width)
        assertEquals(0, child.x)
    }

    /**
     * The maintainer's original report: at a narrow editor the card's trailing Edit/Delete icons ended
     * up *under* the floating inspections widget. The widget is not in the viewport's layout, so the
     * row must reserve what it covers — `FIT_VIEWPORT_WIDTH` subtracts only the vertical scrollbar.
     */
    fun `test the row reserves the space the inspections widget covers`() {
        val (row, child) = rowWithChild()
        val narrow = InlineWidth.readingMeasurePx() / 2
        val covered = 60
        coverViewportTrailingEdge(covered)

        row.setSize(narrow, 100)
        row.doLayout()

        assertEquals("the surface must stop where the widget starts", narrow - covered, child.width)
    }

    /**
     * Before the first layout pass the row has no width, and the reading measure — never zero — is the
     * right answer: a surface that has not been laid out yet must not collapse to nothing, because its
     * height is measured at whatever width it is told it has.
     */
    fun `test a row that has not been laid out yet still measures at the reading measure`() {
        val (row, _) = rowWithChild()

        assertEquals(0, row.width)
        assertEquals(InlineWidth.readingMeasurePx(), row.contentWidthPx())
    }

    /**
     * The maintainer's second report — "resized the editor, the comment still draws with default
     * width". The platform lays the row out at `max(minimumSize.width, viewport)`, and
     * `Component.getMinimumSize` caches its answer from the component's **current size** when no
     * explicit minimum was set. So any minimum derived from the child ratchets up to the width the row
     * was last laid out at, and the row can never shrink again.
     */
    fun `test the row's minimum never ratchets with the width it was laid out at`() {
        val (row, _) = rowWithChild()

        row.setSize(InlineWidth.readingMeasurePx(), 100)
        row.doLayout()

        assertEquals("a floor of any kind freezes the row at its current width", 0, row.minimumSize.width)
    }

    /**
     * The same defect end to end, through the platform's own placement and layout — which is where it
     * happened, and where a hand-made child cannot reproduce it: a stub that returns a fixed
     * `getMinimumSize` never exhibits the ratchet, which is exactly why the first cut of these tests
     * passed while the IDE did not.
     */
    fun `test the surface follows the editor through the platform's own layout`() {
        val panel = StoredCommentCard.build(editorEx, "look here", {}, {}, {})
        editorEx.addComponentInlay(
            editorEx.document.getLineEndOffset(1),
            InlayProperties().relatesToPrecedingText(true),
            panel,
            ComponentInlayAlignment.FIT_VIEWPORT_WIDTH,
        )

        val wide = layOutEditorAt(1200, panel)
        val narrow = layOutEditorAt(400, panel)

        assertEquals("a wide editor is capped at the reading measure", InlineWidth.readingMeasurePx(), wide)
        assertTrue("a narrowed editor must narrow the surface (wide=$wide, narrow=$narrow)", narrow < wide)
        assertTrue("...down to the viewport rather than to a floor", narrow <= editorEx.scrollPane.viewport.width)
    }

    /** Sizes the editor as a window resize would, and returns the width the surface then measures at. */
    private fun layOutEditorAt(width: Int, row: JComponent): Int {
        for (component in listOf(editorEx.component, editorEx.scrollPane, editorEx.contentComponent)) {
            component.setSize(width, 600)
        }
        editorEx.component.doLayout()
        editorEx.scrollPane.doLayout()
        (row.parent as JComponent).doLayout()
        editorEx.contentComponent.validate()
        return (row as ReadingWidthRow).contentWidthPx()
    }

    /** Puts a status component over the trailing [covered] px of the fixture editor's viewport. */
    private fun coverViewportTrailingEdge(covered: Int) {
        val scrollPane = editorEx.scrollPane as JBScrollPane
        scrollPane.setSize(600, 400)
        scrollPane.doLayout()
        val viewport = scrollPane.viewport
        val status = JPanel()
        scrollPane.statusComponent = status
        // As the platform places it: floating over the viewport's trailing edge, overhanging the
        // scrollbar — which is why its own width is not what it costs the content area.
        status.setBounds(viewport.x + viewport.width - covered, 0, covered * 2, 24)
    }

    private fun rowWithChild(): Pair<ReadingWidthRow, JPanel> {
        val child = object : JPanel() {
            override fun getPreferredSize(): Dimension = Dimension(10_000, 40)

            override fun getMinimumSize(): Dimension = Dimension(24, 40)
        }
        val row = ReadingWidthRow(editorEx)
        row.setContent(child)
        return row to child
    }
}
