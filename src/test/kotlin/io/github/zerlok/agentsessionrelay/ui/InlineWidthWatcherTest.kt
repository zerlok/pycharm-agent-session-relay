package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.ComponentEvent
import javax.swing.JPanel

/**
 * The live width source (design D2/D3): one watcher per editor, recomputing the width rule on the
 * editor's own component events and asking every registered surface to re-measure — the plumbing that
 * makes a card or a box **track** the editor instead of keeping the width it was built with.
 *
 * A headless fixture's editor is never laid out, so a real viewport resize is not reproducible here.
 * What is reproducible is a genuine change to one of the rule's other inputs — the editor's right-margin
 * column — which is what these tests drive the recompute with; the *reaction* under test (recompute,
 * short-circuit, fan-out, detach) is identical either way. Whether a real resize actually reaches the
 * inlay stays a running-IDE check in design.md "## Open Questions".
 */
class InlineWidthWatcherTest : BasePlatformTestCase() {

    private lateinit var watcher: InlineWidthWatcher
    private var remeasures = 0
    private val surface = JPanel()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.py", "line0\nline1\nline2\n")
        watcher = InlineWidthWatcher.install(myFixture.editor)
        Disposer.register(testRootDisposable, watcher)
        remeasures = 0
    }

    override fun tearDown() {
        try {
            // The controller is a project service on a reused light project; a left-open box would
            // outlive this test's editor (and stay registered with a disposed watcher).
            CommentDraftController.getInstance(project).close()
        } finally {
            super.tearDown()
        }
    }

    /** The watcher answers with the rule computed for the editor it watches. */
    fun `test the watcher starts at the editor's current width`() {
        assertEquals(InlineWidth.baseWidthPx(myFixture.editor), watcher.width)
        assertEquals("...and that is what surfaces measure at", watcher.width, InlineWidth.currentWidthPx(myFixture.editor))
    }

    /** A geometry change recomputes the width and asks every registered surface to re-measure. */
    fun `test a resize pushes a new width to the registered surfaces`() {
        watcher.attach(surface) { remeasures++ }
        val before = watcher.width

        narrowTheEditor()
        watcher.componentResized(resized())

        assertTrue("the width must have followed the editor", watcher.width < before)
        assertEquals("...to the rule's new value", InlineWidth.baseWidthPx(myFixture.editor), watcher.width)
        assertEquals("...and the surface must have been asked to re-measure exactly once", 1, remeasures)
    }

    /**
     * ...and a recompute that lands on the same number does nothing. Dragging a window edge fires a
     * stream of resize events, and without this every one of them would fan a revalidate out to every
     * card in the file.
     */
    fun `test an unchanged width asks nobody to re-measure`() {
        watcher.attach(surface) { remeasures++ }

        watcher.componentResized(resized())
        watcher.componentResized(resized())

        assertEquals(InlineWidth.baseWidthPx(myFixture.editor), watcher.width)
        assertEquals(0, remeasures)
    }

    /** A detached surface is a surface the watcher no longer touches. */
    fun `test a detached surface is not asked to re-measure`() {
        watcher.attach(surface) { remeasures++ }
        watcher.detach(surface)

        narrowTheEditor()
        watcher.componentResized(resized())

        assertEquals(0, remeasures)
        assertEquals(0, watcher.surfaceCount)
    }

    /**
     * No registration outlives its surface (task 2.4), box side: the open box attaches its panel, and
     * closing it — which disposes the inlay the panel lived in — detaches it again. A registration left
     * behind would revalidate a component with no inlay on every later resize.
     */
    fun `test the open box attaches to the watcher and a closed box detaches`() {
        val controller = CommentDraftController.getInstance(project)

        controller.open(myFixture.editor, 1, 1)
        assertEquals("an open box must follow the editor's width", 1, watcher.surfaceCount)

        controller.close()
        assertEquals("a closed box must leave nothing registered", 0, watcher.surfaceCount)
    }

    /** Disposal unpublishes the watcher, so a surface built afterwards falls back to computing its own width. */
    fun `test disposing the watcher unpublishes it from the editor`() {
        Disposer.dispose(watcher)

        assertNull(InlineWidthWatcher.of(myFixture.editor))
        assertEquals(InlineWidth.baseWidthPx(myFixture.editor), InlineWidth.currentWidthPx(myFixture.editor))
    }

    // -- Helpers --

    /**
     * Changes a real input of the width rule: a 1-column right margin, whose floored cap is far below
     * the reading measure the fixture's un-laid-out editor otherwise sits at.
     */
    private fun narrowTheEditor() {
        myFixture.editor.settings.setRightMargin(1)
    }

    private fun resized(): ComponentEvent =
        ComponentEvent(myFixture.editor.component, ComponentEvent.COMPONENT_RESIZED)
}
