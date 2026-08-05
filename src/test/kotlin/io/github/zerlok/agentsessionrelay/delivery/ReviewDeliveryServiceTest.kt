package io.github.zerlok.agentsessionrelay.delivery

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.CommentStatus
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import io.github.zerlok.agentsessionrelay.ui.EditorReviewOverlayService
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real-platform tests for the submit pipeline (`verified-delivery`): the two invariants that shipped
 * with no test at all — a failed write preserves the batch, a successful one clears it — and the
 * verification that closes the maintainer QA defect, namely that a comment is checked against its
 * file's **content** and is therefore checked whether or not the file is open.
 *
 * Everything here drives [ReviewDeliveryService] directly. Nothing constructs an `AnActionEvent`; that
 * the pipeline is reachable this way *is* the point of moving it out of the action.
 *
 * A "closed file" here is a file with no editor at all — the exact state the old marker-based check
 * skipped. The fixture's own `myFixture.editor` is deliberately unused: it is an `UNTYPED` editor the
 * overlay service ignores, so an "open" file is opened as a real `MAIN_EDITOR` and released again.
 */
class ReviewDeliveryServiceTest : BasePlatformTestCase() {

    private lateinit var service: ReviewBatchService
    private lateinit var delivery: ReviewDeliveryService
    private val openedEditors = mutableListOf<Editor>()

    override fun setUp() {
        super.setUp()
        service = ReviewBatchService.getInstance(project)
        // The light project (and its services) is reused across methods; start from a clean batch and a
        // clean artifact path.
        service.clear()
        // Brought to life before any editor exists, so its EditorFactoryListener sees every editor
        // opened below — the flush stage reads through it.
        EditorReviewOverlayService.getInstance(project)
        delivery = ReviewDeliveryService.getInstance(project)
        clearArtifact()
    }

    override fun tearDown() {
        try {
            openedEditors.toList().forEach { close(it) }
            clearArtifact()
        } finally {
            super.tearDown()
        }
    }

    // -- The clear-versus-preserve decision, reachable without an action event --

    /** A successful write empties the batch, and the artifact carries what was exported. */
    fun `test a successful submit writes the artifact and clears the batch`() {
        val file = file("ok.py", "line0\nline1\nline2\n")
        service.addComment(Subject.Line(file.url, 1), "fix this", anchorText = "line1")

        val outcome = submit()

        assertTrue("outcome was $outcome", outcome is ReviewDeliveryService.Outcome.Written)
        assertEmpty(service.comments())
        assertTrue(artifactText().contains("> fix this"))
    }

    /**
     * The invariant that has never had a test: the comments are the user's only copy and there is no
     * undo, so a failed write must leave the batch exactly as it was. The failure is a real one — the
     * artifact path is occupied by a directory, so the write throws — not an injected stub.
     */
    fun `test a failed write preserves the batch`() {
        val file = file("preserved.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "keep me", anchorText = "line1")
        Files.createDirectory(artifactPath())

        val outcome = submit()

        assertTrue("outcome was $outcome", outcome is ReviewDeliveryService.Outcome.Failed)
        assertEquals(1, service.comments().size)
        assertEquals("keep me", service.comments().single().body)
        assertEquals(Subject.Line(file.url, 1), subjectOf(comment.id))
    }

    /** An empty batch is a no-op: nothing written, and the outcome says so. */
    fun `test an empty batch submits nothing and writes no file`() {
        val outcome = submit()

        assertEquals(ReviewDeliveryService.Outcome.NothingToSubmit, outcome)
        assertFalse("no artifact was written", Files.exists(artifactPath()))
    }

    // -- Verification against file content: a closed file is not an unverifiable case --

    /**
     * The maintainer QA repro, as a test. A comment is authored, its file is never opened, the file is
     * then changed so that different text occupies the comment's recorded lines. The old marker-based
     * check skipped this comment entirely and exported it unflagged; it is `STALE`.
     */
    fun `test a comment in a closed file whose lines now hold different text is stale`() {
        val file = file("closed-drift.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "check", anchorText = "line1")
        assertNoEditorFor(file)

        rewrite(file, "line0\nsomething else\nline2\n")

        assertEquals(CommentStatus.STALE, verdictFor(comment.id))
    }

    /**
     * The other closed-file verdict: the recorded range does not exist in the file's content at all.
     * `ORPHANED` used to be reachable only by opening the file; it is now reached at export from
     * content, so a comment orphaned in a file never opened this session is still flagged. The recorded
     * range is left exactly where the user put it — verification flags, it never moves anything.
     */
    fun `test a comment in a closed file whose recorded range no longer exists is orphaned`() {
        val file = file("closed-orphan.py", (0 until 50).joinToString("\n") { "line$it" })
        val comment = service.addComment(Subject.LineRange(file.url, 299, 300), "way past the end", anchorText = "gone")
        assertNoEditorFor(file)

        assertEquals(CommentStatus.ORPHANED, verdictFor(comment.id))
        assertEquals(Subject.LineRange(file.url, 299, 300), subjectOf(comment.id))
    }

    /**
     * Open/closed parity — the property that replaces the old two-path arrangement. Both verdicts are
     * produced by the same code reading the same content, so opening the file cannot change the answer;
     * a divergence here would mean the closed path is being shadowed by an open one again.
     */
    fun `test verification gives the same verdict whether the file is open or closed`() {
        val drifted = file("parity-drift.py", "line0\nline1\nline2\n")
        val orphaned = file("parity-orphan.py", "line0\nline1\n")
        val drift = service.addComment(Subject.Line(drifted.url, 1), "check", anchorText = "line1")
        val orphan = service.addComment(Subject.LineRange(orphaned.url, 299, 300), "past the end", anchorText = "gone")
        rewrite(drifted, "line0\nsomething else\nline2\n")

        val closed = verdicts()

        openEditor(drifted)
        openEditor(orphaned)
        val open = verdicts()

        assertEquals(CommentStatus.STALE, closed[drift.id])
        assertEquals(CommentStatus.ORPHANED, closed[orphan.id])
        assertEquals("the file being open must not change the verdict", closed, open)
    }

    /**
     * A moved line number is not drift. Content changed only *above* the recorded range leaves the
     * commented text itself untouched, and the position flush has already moved the recorded range —
     * so the comment stays `ACTIVE`. Flagging this would make the flag noise.
     */
    fun `test a change above the recorded range does not make a comment stale`() {
        val file = file("shift-above.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "check", anchorText = "line1")

        // The file is closed, so nothing flushes its position: the range the user recorded is the range
        // that gets checked, and after prepending two lines "line1" now sits at line 3.
        rewrite(file, "top0\ntop1\nline0\nline1\nline2\n")
        service.updatePosition(comment.id, Subject.Line(file.url, 3))

        assertEquals(CommentStatus.ACTIVE, verdictFor(comment.id))
    }

    /**
     * The same rule through the whole pipeline with the file open: an in-IDE insertion above the
     * comment shifts its live marker, stage 1 flushes the shifted range into the store, and stage 2
     * then finds the same text there — so the export carries the *new* line numbers, unflagged. This is
     * the interplay the flush exists for: verify after flushing, never before.
     */
    fun `test an in-IDE edit above the comment is flushed and stays active`() {
        val file = file("inide-shift.py", "line0\nline1\nline2\n")
        service.addComment(Subject.Line(file.url, 1), "check", anchorText = "line1")
        val editor = openEditor(file)

        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "top0\ntop1\n") }

        submit()

        val artifact = artifactText()
        assertTrue(artifact, artifact.contains("#L4\n"))
        assertFalse("an unchanged anchor is never flagged", artifact.contains("⚠️"))
    }

    /** Unchanged text: the comment is verified and stays `ACTIVE`. */
    fun `test an unchanged comment stays active`() {
        val file = file("stable.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "fine", anchorText = "line1")

        assertEquals(CommentStatus.ACTIVE, verdictFor(comment.id))
    }

    /** A verdict that flips back: fixing the text again re-verifies the comment. */
    fun `test verification re-activates a comment once its text matches again`() {
        val file = file("restored.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "check", anchorText = "line1")

        rewrite(file, "line0\nsomething else\nline2\n")
        assertEquals(CommentStatus.STALE, verdictFor(comment.id))

        rewrite(file, "line0\nline1\nline2\n")
        assertEquals(CommentStatus.ACTIVE, verdictFor(comment.id))
    }

    // -- Unverifiable is not stale --

    /** Nothing to compare against, so nothing is claimed: the comment is not verified at all. */
    fun `test a comment with no recorded anchor text is not verified`() {
        val file = file("noanchor.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "pre-anchoring record")
        assertNull(service.comments().single { it.id == comment.id }.anchorText)
        service.updateStatus(comment.id, CommentStatus.STALE)

        assertFalse("no verdict was produced", verdicts().containsKey(comment.id))
        assertEquals(CommentStatus.STALE, statusOf(comment.id))
    }

    /**
     * The remaining unverifiable case now that a closed file is not one: the file cannot be resolved at
     * all (deleted, or on a filesystem the IDE cannot reach). "We could not check" must not reach the
     * agent as "we checked and it moved".
     */
    fun `test a comment whose file cannot be resolved is not verified`() {
        val comment = service.addComment(
            Subject.Line("file:///nowhere/at/all.py", 1), "unreachable", anchorText = "def f()",
        )
        service.updateStatus(comment.id, CommentStatus.STALE)

        assertFalse("no verdict was produced", verdicts().containsKey(comment.id))
        assertEquals(CommentStatus.STALE, statusOf(comment.id))
    }

    /** Verification yields statuses and nothing else — "flag it" must never quietly become "move it". */
    fun `test verification never changes a stored subject`() {
        val file = file("untouched.py", "line0\nline1\nline2\n")
        val stale = service.addComment(Subject.Line(file.url, 1), "drifts", anchorText = "line1")
        val active = service.addComment(Subject.Line(file.url, 2), "stays", anchorText = "line2")

        rewrite(file, "line0\nchanged\nline2\n")
        val verdicts = verdicts()

        assertEquals(CommentStatus.STALE, verdicts[stale.id])
        assertEquals(Subject.Line(file.url, 1), subjectOf(stale.id))
        assertEquals(CommentStatus.ACTIVE, verdicts[active.id])
        assertEquals(Subject.Line(file.url, 2), subjectOf(active.id))
    }

    // -- The whole pipeline: what actually reaches the agent --

    /**
     * The QA repro end to end: comment on line 9 of a file that is never opened, lines removed from the
     * top of it, submit. Before this change `REVIEW.md` said `@file#L9` with no flag, pointing at what
     * used to be line 13. It must now carry the unverified-anchor marker.
     */
    fun `test the written artifact flags a drifted closed-file comment`() {
        val file = file("qa.py", (0 until 20).joinToString("\n") { "line$it" })
        service.addComment(Subject.Line(file.url, 8), "the retry loop needs a backoff", anchorText = "line8")

        // The external edit the agent would make: lines 2-5 (0-based 1..4) removed.
        rewrite(file, (0 until 20).filterNot { it in 1..4 }.joinToString("\n") { "line$it" })

        assertTrue("outcome was ${submit()}", Files.exists(artifactPath()))
        val artifact = artifactText()
        assertTrue(artifact, artifact.contains("#L9  ⚠️ unverified anchor"))
        assertTrue(artifact, artifact.contains("> the retry loop needs a backoff"))
    }

    /** The orphan variant: the recorded lines are gone, and the marker says so distinguishably. */
    fun `test the written artifact flags an orphaned comment`() {
        val file = file("truncated.py", (0 until 50).joinToString("\n") { "line$it" })
        service.addComment(Subject.LineRange(file.url, 299, 300), "past the end", anchorText = "gone")

        submit()

        val artifact = artifactText()
        assertTrue(artifact, artifact.contains("#L300-301  ⚠️ anchor deleted"))
    }

    /**
     * The verdicts are store writes of the final (EDT) stage, so a preserved batch keeps the flags the
     * submit discovered — the user retrying after a failure sees the same truth the export did.
     */
    fun `test a failed submit still records the verification verdicts`() {
        val file = file("failed-verdicts.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "check", anchorText = "line1")
        rewrite(file, "line0\nsomething else\nline2\n")
        Files.createDirectory(artifactPath())

        submit()

        assertEquals(CommentStatus.STALE, statusOf(comment.id))
    }

    // -- Threading (ARCHITECTURE §5.3, design D2) --

    /**
     * The read stage must not need the EDT — that requirement is the whole reason the pipeline moved
     * out of the action. Driven from a pooled thread with no EDT involvement: if verification ever
     * grows an EDT-only dependency (a marker read, an editor lookup), this fails rather than working by
     * accident because production happens to call it from a background task.
     */
    fun `test verification runs off the EDT`() {
        val file = file("offedt.py", "line0\nline1\nline2\n")
        val comment = service.addComment(Subject.Line(file.url, 1), "check", anchorText = "line1")
        rewrite(file, "line0\nsomething else\nline2\n")

        val batch = service.comments()
        val offEdt = ApplicationManager.getApplication()
            .executeOnPooledThread<Map<CommentId, CommentStatus>> { delivery.verifyAnchors(batch) }
            .get()

        assertEquals(CommentStatus.STALE, offEdt[comment.id])
    }

    /**
     * The other half of the split: the store mutations land on the EDT, and the outcome is delivered
     * only once they have — a caller rendering the outcome must never see a batch mid-clear.
     */
    fun `test the outcome is delivered on the EDT with the store already settled`() {
        val file = file("settled.py", "line0\nline1\nline2\n")
        service.addComment(Subject.Line(file.url, 1), "fix this", anchorText = "line1")

        var onEdt = false
        var commentsAtOutcome = -1
        delivery.submit { _ ->
            onEdt = ApplicationManager.getApplication().isDispatchThread
            commentsAtOutcome = service.comments().size
        }

        assertTrue("the outcome is delivered on the EDT", onEdt)
        assertEquals("the batch was already cleared when the outcome arrived", 0, commentsAtOutcome)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun submit(): ReviewDeliveryService.Outcome {
        var outcome: ReviewDeliveryService.Outcome? = null
        delivery.submit { outcome = it }
        return requireNotNull(outcome) { "the pipeline did not report an outcome" }
    }

    private fun verdicts(): Map<CommentId, CommentStatus> = delivery.verifyAnchors(service.comments())

    private fun verdictFor(id: CommentId): CommentStatus? = verdicts()[id]

    private fun file(name: String, text: String): VirtualFile =
        myFixture.addFileToProject(name, text).virtualFile

    /** The out-of-IDE edit: the file's content is replaced, exactly as an agent rewriting it would. */
    private fun rewrite(file: VirtualFile, text: String) {
        WriteAction.run<RuntimeException> { VfsUtil.saveText(file, text) }
    }

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

    private fun assertNoEditorFor(file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertEmpty(EditorFactory.getInstance().getEditors(document, project).toList())
    }

    private fun artifactPath(): Path = Path.of(project.basePath!!, ReviewDelivery.FILE_NAME)

    private fun artifactText(): String = Files.readString(artifactPath())

    /**
     * The light fixture names a project base path but never materializes it, and it is shared across
     * methods — so the directory has to exist for a write to be able to succeed, and the artifact must
     * not leak from one method into the next.
     */
    private fun clearArtifact() {
        Files.createDirectories(Path.of(project.basePath!!))
        val path = artifactPath()
        if (Files.isDirectory(path)) Files.delete(path) else Files.deleteIfExists(path)
    }

    private fun statusOf(id: CommentId): CommentStatus = service.comments().single { it.id == id }.status

    private fun subjectOf(id: CommentId): Subject = service.comments().single { it.id == id }.subject
}
