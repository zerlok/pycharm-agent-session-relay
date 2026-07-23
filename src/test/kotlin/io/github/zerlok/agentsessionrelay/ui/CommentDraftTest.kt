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
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * Real-platform tests for the two `comment-box-editing-fidelity` fixes: the box's undo scoping (D1-R)
 * and its re-measure on body edits (D2-R). Both hang off the box that [CommentDraft] hands to
 * [com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager], so the tests open a real draft
 * over a live editor fixture and read the box back out of the editor's component tree and inlay
 * model — no mocking, and no test-only seam added to production code.
 *
 * What this fixture structurally cannot show, so no test here claims it: nothing is ever *shown*
 * without a display, so (a) `UndoRedoAction` cannot be driven end to end — that the `FILE_EDITOR` this
 * panel supplies really wins the `DataContext` walk, and that undo then reaches the body's document,
 * are running-IDE checks — and (b) the box's actual *growth* is unobservable: the production
 * `scheduleRemeasure` only schedules the platform's layout pass, and the renderer's `synchronizeBoundsWithInlay` (which reads
 * the panel's preferred height and calls `Inlay.update` itself) never runs headlessly. Both stay open
 * questions in design.md "## Open Questions". What is tested is what each fix actually adds: the
 * panel's own data snapshot, and the revalidation a body edit schedules on the *live* box panel.
 */
class CommentDraftTest : BasePlatformTestCase() {

    private lateinit var controller: CommentDraftController

    // Body fields whose inner editor this test forced into existence; released in tearDown, since
    // nothing here is ever shown and so nothing would otherwise call EditorTextField.removeNotify.
    private val shownBodies = linkedSetOf<EditorTextField>()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.py", "line0\nline1\nline2\nline3\nline4\nline5\n")
        controller = CommentDraftController.getInstance(project)
    }

    override fun tearDown() {
        try {
            shownBodies.forEach { it.removeNotify() }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            // The controller is a project service and the light fixture reuses its project across
            // methods, so a left-open draft would outlive this test's editor.
            controller.close()
        } finally {
            super.tearDown()
        }
    }

    // -- Undo scoping: the box names its own editor as the file editor (defect A / D1-R) --

    /**
     * The box content panel contributes exactly one thing to the action system: the box's **own**
     * [TextEditor] under [PlatformCoreDataKeys.FILE_EDITOR]. Being the nearest provider, that shadows
     * the host file's editor — which the box's Swing ancestors would otherwise supply and
     * `UndoRedoAction` would undo against, editing the user's *code* while they type a comment. Both
     * halves matter and both are asserted: the value is the wrapper around the *body's* editor, and
     * *nothing else* is in the snapshot — above all not `CommonDataKeys.EDITOR`, which
     * [EditorTextField] already supplies (Single Source of Truth).
     */
    fun `test the box names its own editor as the file editor and contributes nothing else`() {
        controller.open(myFixture.editor, 1, 1)
        val innerEditor = showBody()

        val sink = RecordingSink()
        (contentPanel() as UiDataProvider).uiDataSnapshot(sink)

        val fileEditor = sink.values[PlatformCoreDataKeys.FILE_EDITOR.name]
        assertInstanceOf(fileEditor, TextEditor::class.java)
        assertSame("the box's own editor, not the host file's", innerEditor, (fileEditor as TextEditor).editor)
        assertEquals(setOf(PlatformCoreDataKeys.FILE_EDITOR.name), sink.values.keys)
        assertEmpty(sink.explicitNulls)
    }

    /**
     * An edge-drag hides and rebuilds the box, so the data provider is a *new* panel instance
     * afterwards; the override has to come back with it (the provider rides on the panel rather than on
     * anything registered once). The rebuild is driven through the draft's own mouse seam, the same
     * one [RelayHoverListener] routes real events into.
     */
    fun `test the rebuilt box after an edge drag names it again`() {
        controller.open(myFixture.editor, 1, 1)
        val innerEditor = showBody()
        setBody("typed before the drag")
        val panelBeforeDrag = contentPanel()

        dragBottomEdgeTo(3)

        val panelAfterDrag = contentPanel()
        assertNotSame("the edge-drag rebuilt the box", panelBeforeDrag, panelAfterDrag)
        assertEquals("the body survived the rebuild", "typed before the drag", bodyField().text)

        val sink = RecordingSink()
        (panelAfterDrag as UiDataProvider).uiDataSnapshot(sink)
        assertSame(innerEditor, (sink.values[PlatformCoreDataKeys.FILE_EDITOR.name] as TextEditor).editor)
        assertEquals(setOf(PlatformCoreDataKeys.FILE_EDITOR.name), sink.values.keys)
        assertEmpty(sink.explicitNulls)
    }

    /**
     * The editor-less branch, which the shipped box never reaches (the snapshot is only taken while
     * focus is inside the box, which implies the inner editor exists) but which is reachable here
     * because nothing is shown. It masks with the platform's `EXPLICIT_NULL` rather than falling
     * through to the host file's editor — a tripwire, not a fallback: a null key is not safe either
     * (design D1-R / Risks). Asserted so the branch's *intent* is pinned, not as evidence about undo.
     */
    fun `test the box masks the file editor when it has no inner editor`() {
        controller.open(myFixture.editor, 1, 1)
        assertNull("the fixture never shows the box, so there is no inner editor", bodyField().editor)

        val sink = RecordingSink()
        (contentPanel() as UiDataProvider).uiDataSnapshot(sink)

        assertEquals(setOf(PlatformCoreDataKeys.FILE_EDITOR.name), sink.explicitNulls)
        assertEquals(emptyMap<String, Any?>(), sink.values)
    }

    // -- Live resize: a body edit revalidates the live box (defect B / D2-R) --

    /**
     * A body edit revalidates the box panel, which is the whole mechanism (D2-R): it schedules the
     * layout pass that reaches the embedded-component renderer's `synchronizeBoundsWithInlay`, and
     * *that* re-reads the panel's preferred height and calls `Inlay.update`. The observable half here
     * is the invalidation the draft's own *deferred* runnable causes; the scheduled pass itself needs
     * a display, so the box's real growth stays a running-IDE check.
     *
     * The edit also invalidates the box synchronously — the inner editor's own components revalidate
     * on a document change, and that propagates up — so the panel is re-validated in between to keep
     * this test on the draft's contribution alone: after that, the only queued work that touches the
     * box is the draft's deferred measure (deferred on purpose, so the inner editor finishes
     * recomputing soft wraps first).
     */
    fun `test a body edit revalidates the live box panel`() {
        controller.open(myFixture.editor, 1, 1)
        val panel = layOutBox()

        setBody("one\ntwo\nthree")
        panel.validate()
        assertTrue("the box is laid out again before the deferred measure runs", panel.isValid)

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse("the deferred measure revalidated the box", panel.isValid)
    }

    /**
     * The same, on the box rebuilt by an edge-drag: the once-per-draft listener is registered on the
     * *retained* body document, so it survives the rebuild, and it must revalidate the box's *current*
     * panel — the one hideBox/showBox just replaced, not the disposed one.
     */
    fun `test a body edit revalidates the box rebuilt by an edge drag`() {
        controller.open(myFixture.editor, 1, 1)
        val panelBeforeDrag = measuredPanel()

        dragBottomEdgeTo(3)

        val panel = layOutBox()
        assertNotSame("the edge-drag rebuilt the box", panelBeforeDrag, panel)

        setBody("one\ntwo\nthree")
        panel.validate()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertFalse("the current panel was revalidated, not the one the drag disposed", panel.isValid)
    }

    /**
     * Closing the box between the edit and the deferred measure is the ordinary case for
     * Ctrl+Enter/Esc, and the queued measure must simply drop: no exception, and no resurrected box.
     */
    fun `test closing the box before the deferred measure is harmless`() {
        controller.open(myFixture.editor, 1, 1)
        val inlay = boxInlay()

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
     * The panel the draft revalidates: the single component the renderer wraps, i.e. exactly what
     * `showBox` handed to `addComponent` and retained as its `boxPanel`.
     */
    private fun measuredPanel(): JComponent = (boxInlay().renderer as Container).components.single() as JComponent

    /**
     * The box's content panel — the first data provider below the inlay's renderer. The body field is
     * a provider too, but it sits under the content panel, so a top-down search finds the panel first.
     */
    private fun contentPanel(): JComponent =
        find(boxInlay().renderer as Component) { it is UiDataProvider && it !is EditorTextField } as JComponent

    private fun bodyField(): EditorTextField =
        find(boxInlay().renderer as Component) { it is EditorTextField } as EditorTextField

    /**
     * Forces the body's inner editor into existence and returns it. [EditorTextField] builds it when
     * it is really shown, which never happens headlessly, so the box's *normal* state — the one the
     * data snapshot is taken in — has to be established by hand. Nothing about the fix is faked: this
     * only makes the precondition hold.
     */
    private fun showBody(): Editor {
        val field = bodyField()
        field.addNotify()
        val inner = checkNotNull(field.getEditor(true)) { "the body's inner editor could not be created" }
        shownBodies += field
        return inner
    }

    /**
     * Gives the box a peer and marks it valid, so a later `revalidate()` shows up as an invalidation;
     * returns the panel the draft revalidates. Without this the box is born invalid and
     * `Container.validate()` is a no-op, so the mechanism under test would have nothing to change.
     * Validating also builds the body's inner editor (`EditorTextField.validate` calls
     * `getEditor(true)`), which has to be released — hence the [shownBodies] bookkeeping.
     */
    private fun layOutBox(): JComponent {
        // Drain what opening/rebuilding the box already queued (the deferred focus request), so the
        // only deferred work left to observe afterwards is the draft's own re-measure.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val panel = measuredPanel()
        (boxInlay().renderer as JComponent).addNotify()
        shownBodies += bodyField()
        panel.validate()
        return panel
    }

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
