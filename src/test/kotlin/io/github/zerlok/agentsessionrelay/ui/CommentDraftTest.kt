package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * Real-platform tests for the two `comment-box-editing-fidelity` fixes: the box's undo scoping (D1)
 * and its re-measure on body edits (D2). Both hang off the box that [CommentDraft] hands to
 * [com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager], so the tests open a real draft
 * over a live editor fixture and read the box back out of the editor's component tree and inlay
 * model — no mocking, and no test-only seam added to production code.
 *
 * Two things this fixture structurally cannot show, so no test here claims them: an [EditorTextField]
 * only builds its inner editor when it is really shown (`addNotify`), and nothing lays components out
 * without a display. So the *end-to-end* `DataContext` resolution of `FILE_EDITOR` (the box's own
 * editor is absent, so `CommonDataKeys.EDITOR` falls back to the host editor and the platform's
 * `BasicUiDataRule` re-derives the host file's editor — a headless artifact, not the shipped
 * behavior) and the *real* body-text-driven growth of the box both need a running IDE; they stay open
 * questions in design.md. What is tested is what each fix actually adds: the panel's own data
 * snapshot, and the re-measure a body edit triggers on the live inlay.
 */
class CommentDraftTest : BasePlatformTestCase() {

    private lateinit var controller: CommentDraftController

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.py", "line0\nline1\nline2\nline3\nline4\nline5\n")
        controller = CommentDraftController.getInstance(project)
    }

    override fun tearDown() {
        try {
            // The controller is a project service and the light fixture reuses its project across
            // methods, so a left-open draft would outlive this test's editor.
            controller.close()
        } finally {
            super.tearDown()
        }
    }

    // -- Undo scoping: the box masks the host file's FileEditor (defect A / D1) --

    /**
     * The box content panel contributes exactly one thing to the action system: an **explicit null**
     * for [PlatformCoreDataKeys.FILE_EDITOR], which is what stops `UndoRedoAction` resolving the host
     * file's editor through the box's Swing ancestors and undoing the user's *code* while they type a
     * comment. Both halves matter and both are asserted: `setNull` (the platform's `EXPLICIT_NULL`,
     * the only form that masks an ancestor — a provider merely returning null reads as "not provided"
     * and the walk continues), and *nothing else* in the snapshot — above all not `CommonDataKeys.EDITOR`,
     * which [EditorTextField] already supplies and which the re-derivation of `FILE_EDITOR` depends on.
     */
    fun `test the box masks the host file editor and contributes nothing else`() {
        controller.open(myFixture.editor, 1, 1)

        val sink = RecordingSink()
        (boxPanel() as UiDataProvider).uiDataSnapshot(sink)

        assertEquals(setOf(PlatformCoreDataKeys.FILE_EDITOR.name), sink.explicitNulls)
        assertEquals(emptyMap<String, Any?>(), sink.values)
    }

    /**
     * An edge-drag hides and rebuilds the box, so the data provider is a *new* panel instance
     * afterwards; the mask has to come back with it (the provider rides on the panel rather than on
     * anything registered once). The rebuild is driven through the draft's own mouse seam, the same
     * one [RelayHoverListener] routes real events into.
     */
    fun `test the rebuilt box after an edge drag masks it again`() {
        controller.open(myFixture.editor, 1, 1)
        setBody("typed before the drag")
        val panelBeforeDrag = boxPanel()

        dragBottomEdgeTo(3)

        val panelAfterDrag = boxPanel()
        assertNotSame("the edge-drag rebuilt the box", panelBeforeDrag, panelAfterDrag)
        assertEquals("the body survived the rebuild", "typed before the drag", bodyField().text)

        val sink = RecordingSink()
        (panelAfterDrag as UiDataProvider).uiDataSnapshot(sink)
        assertEquals(setOf(PlatformCoreDataKeys.FILE_EDITOR.name), sink.explicitNulls)
        assertEquals(emptyMap<String, Any?>(), sink.values)
    }

    // -- Live resize: a body edit re-measures the box (defect B / D2) --

    /**
     * A body edit re-measures the box inlay, so the code below it reflows as part of that edit rather
     * than at the next unrelated layout pass. The measured quantity is real: an embedded-component
     * inlay's height is its renderer component's *current* size, and it is only re-read on
     * [Inlay.update]. Since nothing lays anything out headlessly, [growRenderer] plays the part of the
     * layout pass a typed line would cause on screen — the assertion is then that the edit alone
     * propagates that new size to the inlay. (Checked against the un-fixed code: without the body
     * document listener the inlay keeps the stale height.) The measure is deferred to the EDT queue on
     * purpose (D2 — the inner editor must finish recomputing soft wraps first), which is why the
     * height is still stale before the queue is pumped.
     */
    fun `test a body edit re-measures the box inlay`() {
        controller.open(myFixture.editor, 1, 1)
        val inlay = boxInlay()
        val staleHeight = inlay.heightInPixels
        val newHeight = growRenderer(inlay)
        assertEquals("nothing has re-measured yet", staleHeight, inlay.heightInPixels)

        setBody("one\ntwo\nthree")

        assertEquals("the re-measure is deferred (D2)", staleHeight, inlay.heightInPixels)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(newHeight, inlay.heightInPixels)
    }

    /**
     * The same, on the box rebuilt by an edge-drag: the once-per-draft listener is registered on the
     * *retained* body document, so it survives the rebuild, and it must re-measure the box's *current*
     * inlay — the one hideBox/showBox just replaced, not the disposed one.
     */
    fun `test a body edit re-measures the rebuilt box after an edge drag`() {
        controller.open(myFixture.editor, 1, 1)
        val inlayBeforeDrag = boxInlay()

        dragBottomEdgeTo(3)

        val inlay = boxInlay()
        assertFalse("the drag disposed the original inlay", inlayBeforeDrag.isValid)
        val newHeight = growRenderer(inlay)

        setBody("one\ntwo\nthree")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(newHeight, inlay.heightInPixels)
    }

    /**
     * Closing the box between the edit and the deferred measure is the ordinary case for
     * Ctrl+Enter/Esc, and the queued measure must simply drop: no exception, and no resurrected box.
     */
    fun `test closing the box before the deferred measure is harmless`() {
        controller.open(myFixture.editor, 1, 1)
        val inlay = boxInlay()
        growRenderer(inlay)

        setBody("typed, then closed")
        controller.close()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse(inlay.isValid)
        assertEmpty(blockInlays())
    }

    // -- helpers ------------------------------------------------------------------------------

    /**
     * Records what a [UiDataProvider] contributes, keeping `setNull` — the platform's masking
     * `EXPLICIT_NULL` — distinct from a plain `set(key, null)`, because only the former stops the
     * walk up the Swing hierarchy.
     */
    private class RecordingSink : DataSink {
        val values = mutableMapOf<String, Any?>()
        val explicitNulls = mutableSetOf<String>()

        override fun <T : Any> set(key: DataKey<T>, data: T?) {
            values[key.name] = data
        }

        override fun <T : Any> lazy(key: DataKey<T>, data: () -> T?) {
            values[key.name] = data()
        }

        override fun <T : Any> setNull(key: DataKey<T>) {
            explicitNulls += key.name
        }

        override fun <T : Any> lazyNull(key: DataKey<T>) {
            explicitNulls += key.name
        }

        override fun uiDataSnapshot(provider: UiDataProvider) = provider.uiDataSnapshot(this)

        override fun uiDataSnapshot(provider: DataProvider) = Unit

        override fun dataSnapshot(provider: DataSnapshotProvider) = Unit
    }

    private val editor: Editor get() = myFixture.editor

    private fun blockInlays(): List<Inlay<*>> =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)

    /** The draft's box; it is the only block inlay in this fixture (no overlay is running). */
    private fun boxInlay(): Inlay<*> = blockInlays().single()

    /**
     * The box's content panel — the first data provider below the inlay's renderer. The body field is
     * a provider too, but it sits under the content panel, so a top-down search finds the panel first.
     */
    private fun boxPanel(): JComponent =
        find(boxInlay().renderer as Component) { it is UiDataProvider && it !is EditorTextField } as JComponent

    private fun bodyField(): EditorTextField =
        find(boxInlay().renderer as Component) { it is EditorTextField } as EditorTextField

    private fun find(root: Component, match: (Component) -> Boolean): Component {
        fun search(c: Component): Component? {
            if (match(c)) return c
            if (c is Container) c.components.forEach { child -> search(child)?.let { return it } }
            return null
        }
        return checkNotNull(search(root)) { "no matching component under $root" }
    }

    /** Types into the retained body document — the edit whose propagation is under test. */
    private fun setBody(text: String) {
        val document = bodyField().document
        ApplicationManager.getApplication().runWriteAction { document.setText(text) }
    }

    /**
     * Stands in for the layout pass a taller body would trigger on screen: the embedded-component
     * inlay reports its renderer's current height, which only a laid-out (i.e. displayed) hierarchy
     * updates on its own. Returns the height the inlay is expected to pick up on the next re-measure.
     */
    private fun growRenderer(inlay: Inlay<*>): Int {
        val renderer = inlay.renderer as JComponent
        val grown = renderer.height + editor.lineHeight
        renderer.setSize(renderer.width, grown)
        return grown
    }

    /**
     * Drags the wash's bottom edge down to [line] through the draft's mouse seam, which hides the box
     * and rebuilds it on release (`adjustable-comment-range` D1).
     */
    private fun dragBottomEdgeTo(line: Int) {
        val draft = checkNotNull(controller.activeDraft)
        val bottomEdgeY = editor.logicalPositionToXY(LogicalPosition(1, 0)).y + editor.lineHeight
        assertTrue("the press landed on the bottom edge", draft.onMousePressed(bottomEdgeY, editingArea = true))
        draft.onMouseDragged(editor.logicalPositionToXY(LogicalPosition(line, 0)).y)
        assertTrue(draft.onMouseReleased())
    }
}
