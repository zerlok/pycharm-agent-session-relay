package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Real-platform test of the one view-side piece the whole "export reflects in-IDE edits" story rests
 * on (review-export D3 / ARCHITECTURE §3.2): [EditorReviewOverlay.currentPositions], which reads each
 * comment's CURRENT line range off its live [com.intellij.openapi.editor.markup.RangeHighlighter] and
 * projects it back to a fresh [Subject]. Uses a live editor fixture and a real document edit — no
 * mocking — so a regression in the marker→Subject read (e.g. an off-by-one or a broken single-line
 * collapse) fails here rather than silently shipping a mis-pointed reference.
 */
class EditorReviewOverlayTest : BasePlatformTestCase() {

    private lateinit var service: ReviewBatchService
    private lateinit var overlay: EditorReviewOverlay
    private lateinit var url: String

    override fun setUp() {
        super.setUp()
        service = ReviewBatchService.getInstance(project)
        // The light-project fixture and its project-level service are reused across methods; start clean.
        service.clear()

        // A live editor over a real file; its markup is what the overlay projects onto.
        myFixture.configureByText("a.py", "line0\nline1\nline2\nline3\nline4\n")
        url = FileDocumentManager.getInstance().getFile(myFixture.editor.document)!!.url

        overlay = EditorReviewOverlay(project, myFixture.editor)
        Disposer.register(testRootDisposable, overlay)
    }

    /** Inserting whole lines strictly above a range comment shifts its live marker; the read tracks it. */
    fun `test currentPositions reports the shifted range after an in-IDE edit above the comment`() {
        val comment = service.addComment(Subject.LineRange(url, 1, 2), "extract this")

        assertEquals(Subject.LineRange(url, 1, 2), overlay.currentPositions()[comment.id])

        // Two full lines inserted at the very top push the commented lines down by two.
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "top0\ntop1\n")
        }

        assertEquals(Subject.LineRange(url, 3, 4), overlay.currentPositions()[comment.id])
    }

    /** A single-line comment stays a [Subject.Line] (start == end collapse), shifted with the edit. */
    fun `test currentPositions keeps a single-line comment as Line after a shift`() {
        val comment = service.addComment(Subject.Line(url, 1), "note")

        assertEquals(Subject.Line(url, 1), overlay.currentPositions()[comment.id])

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "top0\ntop1\n")
        }

        assertEquals(Subject.Line(url, 3), overlay.currentPositions()[comment.id])
    }

    /**
     * A store subject change (the user re-editing a comment's range) repositions the live marker, so
     * the marker read via [EditorReviewOverlay.currentPositions] reports the NEW range — not the stale
     * built-from range. Guards against reconcileMarkers leaving an existing marker at its old range.
     */
    fun `test updating a comment's position repositions its marker`() {
        val comment = service.addComment(Subject.Line(url, 1), "here")
        assertEquals(Subject.Line(url, 1), overlay.currentPositions()[comment.id])

        service.updatePosition(comment.id, Subject.LineRange(url, 3, 4))

        assertEquals(Subject.LineRange(url, 3, 4), overlay.currentPositions()[comment.id])
    }

    /** A comment on another file is not projected onto this editor's overlay. */
    fun `test currentPositions ignores comments for other files`() {
        service.addComment(Subject.Line("file:///elsewhere.py", 0), "other")

        assertTrue(overlay.currentPositions().isEmpty())
    }

    // -- Read-only inline cards (editable-inline-comments) --

    /** A stored comment on this file gets a read-only card; one on another file does not. */
    fun `test a stored comment on this file yields a card`() {
        val comment = service.addComment(Subject.LineRange(url, 1, 2), "look here")
        assertEquals(setOf(comment.id), overlay.cardCommentIds)

        service.addComment(Subject.Line("file:///elsewhere.py", 0), "other")
        assertEquals(setOf(comment.id), overlay.cardCommentIds)
    }

    /** `commentUpdated` (a body edit) re-renders the card in place — still one card for the comment. */
    fun `test updating a comment body keeps a single refreshed card`() {
        val comment = service.addComment(Subject.Line(url, 1), "old")
        assertEquals(setOf(comment.id), overlay.cardCommentIds)

        service.updateBody(comment.id, "new")

        assertEquals(setOf(comment.id), overlay.cardCommentIds)
    }

    /** Deleting a comment removes its card; clearing the batch removes them all. */
    fun `test deleting and clearing remove cards`() {
        val a = service.addComment(Subject.Line(url, 1), "a")
        val b = service.addComment(Subject.Line(url, 2), "b")
        assertEquals(setOf(a.id, b.id), overlay.cardCommentIds)

        service.removeComment(a.id)
        assertEquals(setOf(b.id), overlay.cardCommentIds)

        service.clear()
        assertTrue(overlay.cardCommentIds.isEmpty())
    }

    /** The comment currently open in an edit box has no card; on close its card reappears (design D3). */
    fun `test the currently edited comment has no card`() {
        val comment = service.addComment(Subject.Line(url, 1), "edit me")
        assertEquals(setOf(comment.id), overlay.cardCommentIds)

        val controller = CommentDraftController.getInstance(project)
        controller.openForEdit(myFixture.editor, comment)
        assertTrue("edited comment's card is suppressed", overlay.cardCommentIds.isEmpty())

        controller.close()
        assertEquals(setOf(comment.id), overlay.cardCommentIds)
    }

    // -- No stored-comment gutter icon; card-hover range highlight (editor-review-visibility) --

    /**
     * D5: a stored comment's position marker carries no gutter icon — it is the invisible live position
     * source only. Asserted directly against the document markup the overlay writes to, so a regression
     * that re-attaches a [StoredCommentGutterIconRenderer] fails here.
     */
    fun `test a stored comment marker carries no gutter icon`() {
        service.addComment(Subject.LineRange(url, 1, 2), "no icon")

        val markup = DocumentMarkupModel.forDocument(myFixture.editor.document, project, true)
        val storedIcons = markup.allHighlighters.count { it.gutterIconRenderer is StoredCommentGutterIconRenderer }
        assertEquals(0, storedIcons)
    }

    /**
     * The other half of D5: the same iconless marker now carries the RESTING gutter bar, painted in the
     * accent the card's leading edge wears — so "this card" and "these lines" are literally one color.
     * The renderer is painted onto an offscreen image rather than merely asserted non-null, so a
     * regression that attaches a bar in some other color (e.g. the pale draft wash, invisible as a
     * stripe) fails here too.
     */
    fun `test a stored comment marker carries a resting gutter bar in the accent color`() {
        service.addComment(Subject.LineRange(url, 1, 2), "bar me")

        val markup = DocumentMarkupModel.forDocument(myFixture.editor.document, project, true)
        val bar = markup.allHighlighters.single { it.lineMarkerRenderer != null }
        assertEquals(myFixture.editor.document.getLineStartOffset(1), bar.startOffset)
        assertEquals(myFixture.editor.document.getLineEndOffset(2), bar.endOffset)
        // Still no resting wash over the code area: text attributes stay null (that stays hover-only).
        assertNull(bar.getTextAttributes(myFixture.editor.colorsScheme))

        val image = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            bar.lineMarkerRenderer!!.paint(myFixture.editor, g, Rectangle(0, 0, 8, 4))
        } finally {
            g.dispose()
        }
        assertEquals(RangeHighlight.STORED_COMMENT_ACCENT.rgb, image.getRGB(0, 0))
    }

    /**
     * The resting bar rides the live position marker, so it tracks in-IDE edits for free — it marks the
     * same lines the card's hover highlight would. Asserted against the marker the bar is attached to,
     * so a future "reconcile the bar separately" regression (a second, stale highlighter) fails here.
     */
    fun `test the resting gutter bar follows an in-IDE edit`() {
        val comment = service.addComment(Subject.LineRange(url, 1, 2), "shift me")
        val markup = DocumentMarkupModel.forDocument(myFixture.editor.document, project, true)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "top0\ntop1\n")
        }

        val bar = markup.allHighlighters.single { it.lineMarkerRenderer != null }
        val document = myFixture.editor.document
        assertEquals(Subject.LineRange(url, 3, 4), overlay.currentPositions()[comment.id])
        assertEquals(3, document.getLineNumber(bar.startOffset))
        assertEquals(4, document.getLineNumber(bar.endOffset))
    }

    /**
     * D4: the transient card-hover range highlight is created on hover-in and disposed on hover-out, at
     * most one at a time. Drives the overlay's card-hover seam directly (the Swing enter/exit path that
     * calls it needs a display); a stale hover-out for a different comment must not drop a newer hover.
     */
    fun `test card hover shows and clears a single transient range highlight`() {
        val a = service.addComment(Subject.LineRange(url, 1, 2), "a")
        val b = service.addComment(Subject.Line(url, 3), "b")
        assertNull(overlay.hoverHighlightCommentId)

        overlay.onCardHover(a.id, true)
        assertEquals(a.id, overlay.hoverHighlightCommentId)

        // Moving to b's card replaces the highlight (still only one at a time).
        overlay.onCardHover(b.id, true)
        assertEquals(b.id, overlay.hoverHighlightCommentId)

        // A late hover-out for a (already superseded) must not clear b's highlight.
        overlay.onCardHover(a.id, false)
        assertEquals(b.id, overlay.hoverHighlightCommentId)

        overlay.onCardHover(b.id, false)
        assertNull(overlay.hoverHighlightCommentId)
    }

    // -- Document-save position sync (persist-live-comment-lines) --

    /**
     * Document save is the third position-sync point (ARCHITECTURE §3.2): a save flushes the saved
     * file's comments' CURRENT live-marker ranges into the store via [ReviewBatchService.updatePosition],
     * and the flush is scoped to the saved document — a sibling open file's comment keeps its stored
     * lines because its own save never fired. Drives the real [EditorReviewOverlayService] and fires the
     * platform's own save signal, [FileDocumentManagerListener.beforeDocumentSaving], through the
     * application bus exactly as the write path does. (The light fixture's `temp://` files are already
     * in-memory, so `FileDocumentManager.saveDocument` skips them and never emits the event — publishing
     * the topic is the faithful stand-in for a real save.) A regression in the wiring (missing hook, or
     * an over-broad flush that touches other files) fails here.
     */
    fun `test saving a document flushes only that document's shifted comments`() {
        // Bring the service (and its FileDocumentManagerListener) to life BEFORE the editors below, so
        // its EditorFactoryListener sees their editorCreated and builds an overlay for each.
        EditorReviewOverlayService.getInstance(project)

        val fm = FileDocumentManager.getInstance()
        val fileA = fm.getFile(myFixture.editor.document)!!
        val docA = myFixture.editor.document
        val fileB = myFixture.addFileToProject("b.py", "b0\nb1\nb2\nb3\n").virtualFile
        val docB = fm.getDocument(fileB)!!
        val urlB = fileB.url

        // The service only adopts MAIN_EDITOR editors; the fixture's own editors are UNTYPED in a light
        // test, so create a real MAIN_EDITOR over each document (released on teardown). editorCreated
        // then drives the service to build an overlay per document.
        val factory = EditorFactory.getInstance()
        val edA = factory.createEditor(docA, project, fileA, false, EditorKind.MAIN_EDITOR)
        val edB = factory.createEditor(docB, project, fileB, false, EditorKind.MAIN_EDITOR)
        Disposer.register(testRootDisposable, Disposable {
            factory.releaseEditor(edA)
            factory.releaseEditor(edB)
        })

        val commentA = service.addComment(Subject.LineRange(url, 1, 2), "in a")
        val commentB = service.addComment(Subject.Line(urlB, 1), "in b")

        // Insert two whole lines at the top of each document, shifting both live markers down by two.
        WriteCommandAction.runWriteCommandAction(project) {
            docA.insertString(0, "top0\ntop1\n")
            docB.insertString(0, "top0\ntop1\n")
        }

        // No sync point has fired yet: the store still holds the authoring-time lines for both.
        assertEquals(Subject.LineRange(url, 1, 2), subjectOf(commentA.id))
        assertEquals(Subject.Line(urlB, 1), subjectOf(commentB.id))

        // Save ONLY a.py: beforeDocumentSaving(docA) -> service.syncPositions(docA) -> updatePosition.
        ApplicationManager.getApplication().messageBus
            .syncPublisher(FileDocumentManagerListener.TOPIC)
            .beforeDocumentSaving(docA)

        // a.py's comment is flushed to its shifted range; b.py's stays put (its save never fired).
        assertEquals(Subject.LineRange(url, 3, 4), subjectOf(commentA.id))
        assertEquals(Subject.Line(urlB, 1), subjectOf(commentB.id))
    }

    private fun subjectOf(id: CommentId): Subject = service.comments().single { it.id == id }.subject
}
