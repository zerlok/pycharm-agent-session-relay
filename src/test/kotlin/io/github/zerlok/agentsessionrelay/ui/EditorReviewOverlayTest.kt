package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

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

    /** A comment on another file is not projected onto this editor's overlay. */
    fun `test currentPositions ignores comments for other files`() {
        service.addComment(Subject.Line("file:///elsewhere.py", 0), "other")

        assertTrue(overlay.currentPositions().isEmpty())
    }
}
