package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchListener
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

/**
 * Real-platform tests for the marker ownership and orphaning rules, driven through
 * the **real** [EditorReviewOverlayService] lifecycle (its `editorCreated` / `editorReleased` path) so
 * that marker ownership, ref-counting, and the sync points are exercised as they ship:
 *
 * - **One marker per document, not per editor**: a file open in two splits carries a
 *   single position marker, which survives closing one of them and goes away with the last.
 * - **Orphaned, not clamped**: a comment whose recorded range does not exist in the
 *   document renders nothing, keeps its recorded range through every sync point, and the store
 *   mutation it performs from inside a reconcile settles in one pass instead of cascading.
 *
 * Anchor verification moved out of this surface entirely (`verified-delivery` D1): it reads file
 * content rather than live markers, so it covers closed files too, and it is tested in
 * [io.github.zerlok.agentsessionrelay.delivery.ReviewDeliveryServiceTest]. What stays here is the
 * *editor-time* `ORPHANED`/`ACTIVE` transition, which the export-time verdict must agree with rather
 * than fight.
 *
 * The fixture's own `myFixture.editor` is deliberately unused: it is an `UNTYPED` editor the service
 * ignores, so every editor here is created as a real `MAIN_EDITOR` and released explicitly.
 */
class DocumentReviewMarkersTest : BasePlatformTestCase() {

    /** Records the real event stream, so reentrancy is pinned on published events, not on internals. */
    private class Probe : ReviewBatchListener {
        val updated = mutableListOf<ReviewComment>()

        override fun commentUpdated(comment: ReviewComment) {
            updated += comment
        }
    }

    private lateinit var service: ReviewBatchService
    private lateinit var overlayService: EditorReviewOverlayService
    private lateinit var probe: Probe
    private val openedEditors = mutableListOf<Editor>()

    override fun setUp() {
        super.setUp()
        service = ReviewBatchService.getInstance(project)
        // The light project (and its services) is reused across methods; start from a clean batch.
        service.clear()
        // Bring the service to life BEFORE any editor exists, so its EditorFactoryListener sees every
        // editorCreated below and builds the markers itself.
        overlayService = EditorReviewOverlayService.getInstance(project)
        probe = Probe()
        project.messageBus.connect(testRootDisposable).subscribe(ReviewBatchListener.TOPIC, probe)
    }

    override fun tearDown() {
        try {
            // Release through the factory so `editorReleased` runs the real close path (flush, then
            // dispose the document's markers when the last editor goes).
            openedEditors.toList().forEach { close(it) }
        } finally {
            super.tearDown()
        }
    }

    // -- D2: one marker per document, ref-counted against live editors --

    /**
     * The case the pre-change suite could not express at all: a second split of the same file used to
     * add a second highlighter over the same lines, so a split file wore doubled gutter bars. The
     * marker now belongs to the document, so there is exactly one — and its lifetime is the *set* of
     * editors on that document, not any single one of them.
     */
    fun `test two splits of one document share a single marker that outlives closing one of them`() {
        val file = file("split.py", "line0\nline1\nline2\n")
        val first = openEditor(file)
        val second = openEditor(file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!

        service.addComment(Subject.LineRange(file.url, 1, 2), "one bar, please")

        assertEquals("two splits, one marker", 1, gutterBars(document))

        // Closing one split leaves the file open in the other: the marker must stay.
        close(first)
        assertEquals("the marker belongs to the document, not the closed editor", 1, gutterBars(document))

        // Closing the last one takes it away.
        close(second)
        assertEquals(0, gutterBars(document))
    }

    /** Reopening the file rebuilds the marker from the store — the comment itself never went away. */
    fun `test reopening the last editor rebuilds the marker`() {
        val file = file("reopen.py", "line0\nline1\nline2\n")
        val editor = openEditor(file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        service.addComment(Subject.Line(file.url, 1), "still here")

        close(editor)
        assertEquals(0, gutterBars(document))

        openEditor(file)
        assertEquals(1, gutterBars(document))
    }

    // -- D4: orphaned, not clamped --

    /**
     * A comment recorded past the end of its file is retained where the user put it: no marker, no
     * card, status `ORPHANED`, and — the point of the change — the recorded range untouched. It used to
     * be clamped to the last line for display, and that clamped position was then written back over
     * the recorded one at the next sync point, silently moving the user's comment.
     */
    fun `test a comment beyond the end of the document is orphaned and keeps its recorded range`() {
        val file = file("short.py", (0 until 50).joinToString("\n") { "line$it" })
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val orphan = service.addComment(Subject.LineRange(file.url, 300, 301), "way past the end")
        // A comment that DOES fit, so the "renders nothing" assertions below are not vacuous.
        service.addComment(Subject.Line(file.url, 1), "in range")

        val editor = openEditor(file)

        assertEquals(CommentStatus.ORPHANED, statusOf(orphan.id))
        assertEquals("only the in-range comment gets a gutter bar", 1, gutterBars(document))
        assertEquals("only the in-range comment gets a card", 1, cards(editor))
        assertEquals(Subject.LineRange(file.url, 300, 301), subjectOf(orphan.id))
    }

    /**
     * The invariant the whole "never write a clamp back" requirement reduces to: run BOTH in-IDE sync
     * points over an orphaned comment and its recorded range is still what the user recorded. It holds
     * structurally — with no marker there is no live position for a flush to read — rather than by a
     * guard inside the flush.
     */
    fun `test save and close flushes never overwrite an orphaned comment's recorded range`() {
        val file = file("flushed.py", (0 until 50).joinToString("\n") { "line$it" })
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val comment = service.addComment(Subject.LineRange(file.url, 300, 301), "way past the end")
        val editor = openEditor(file)

        // Sync point 1: document save.
        ApplicationManager.getApplication().messageBus
            .syncPublisher(FileDocumentManagerListener.TOPIC)
            .beforeDocumentSaving(document)
        assertEquals(Subject.LineRange(file.url, 300, 301), subjectOf(comment.id))

        // Sync point 2: editor close.
        close(editor)
        assertEquals(Subject.LineRange(file.url, 300, 301), subjectOf(comment.id))
    }

    /**
     * Reentrancy: marking `ORPHANED` happens *inside* the reconcile that discovered it, so
     * the resulting `commentUpdated` re-enters that same reconcile. This pins that it settles in one
     * extra pass instead of cascading — a non-idempotent status write would publish again on every
     * re-entry. Asserted on the published event stream, which is the observable the loop turns on:
     * `updateStatus` publishing nothing for an unchanged status is exactly what terminates it.
     */
    fun `test orphaning from inside a reconcile settles in a single pass`() {
        val file = file("settle.py", "line0\nline1\n")
        val comment = service.addComment(Subject.LineRange(file.url, 300, 301), "orphan me")
        assertEmpty(probe.updated)

        openEditor(file)

        assertEquals("exactly one status write, no cascade", 1, probe.updated.size)
        assertEquals(CommentStatus.ORPHANED, probe.updated.single().status)

        // Any later event re-runs the reconcile, which re-decides "orphaned" and must stay silent.
        service.updateBody(comment.id, "orphan me, again")
        assertEquals("the body edit alone; the re-decided status published nothing", 2, probe.updated.size)
        assertEquals(CommentStatus.ORPHANED, statusOf(comment.id))
    }

    /**
     * The way back: the file grows past the recorded range again, so the comment gets its marker, its
     * card, and its `ACTIVE` status back — orphaning is a verdict about the current document, not a
     * one-way door.
     *
     * The verdict is re-decided at the next reconcile, not on the document change itself: reconcile is
     * driven by store events and by the file's editor opening, and deliberately not by a per-keystroke
     * document listener. Reopening the file is both the realistic trigger (the file was rewritten
     * outside the IDE) and the one the `review-batch` spec names.
     */
    fun `test a comment that fits again becomes active and regains its marker`() {
        val file = file("grow.py", "line0\nline1\n")
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val comment = service.addComment(Subject.Line(file.url, 5), "below the end for now")
        val editor = openEditor(file)
        assertEquals(CommentStatus.ORPHANED, statusOf(comment.id))

        WriteCommandAction.runWriteCommandAction(project) {
            document.setText((0 until 10).joinToString("\n") { "line$it" })
        }
        close(editor)
        val reopened = openEditor(file)

        assertEquals(CommentStatus.ACTIVE, statusOf(comment.id))
        assertEquals(1, gutterBars(document))
        assertEquals(1, cards(reopened))
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun file(name: String, text: String): VirtualFile =
        myFixture.addFileToProject(name, text).virtualFile

    private fun openEditor(file: VirtualFile): Editor {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val editor = EditorFactory.getInstance()
            .createEditor(document, project, file, /* isViewer = */ false, EditorKind.MAIN_EDITOR)
        openedEditors += editor
        return editor
    }

    private fun close(editor: Editor) {
        openedEditors -= editor
        EditorFactory.getInstance().releaseEditor(editor)
    }

    /** The resting gutter bars in [document]'s markup — one per live position marker. */
    private fun gutterBars(document: Document): Int =
        DocumentMarkupModel.forDocument(document, project, true)
            .allHighlighters.count { it.lineMarkerRenderer != null }

    /** The read-only comment cards in [editor] — block inlays, the only ones this fixture creates. */
    private fun cards(editor: Editor): Int =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size

    private fun statusOf(id: CommentId): CommentStatus = service.comments().single { it.id == id }.status

    private fun subjectOf(id: CommentId): Subject = service.comments().single { it.id == id }.subject
}
