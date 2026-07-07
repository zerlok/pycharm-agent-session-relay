package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import io.github.zerlok.agentsessionrelay.domain.CommentId
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService

/**
 * Project-scoped owner of the [EditorReviewOverlay] per editor (ARCHITECTURE §3.3). It listens on
 * the application-wide [EditorFactory] and creates an overlay on `editorCreated`, frees it on
 * `editorReleased`, and seeds from [EditorFactory.getAllEditors] at startup (that event fires only
 * for editors opened afterward). It handles only editors whose `project` matches, whose
 * `editorKind == MAIN_EDITOR`, and whose document has a file.
 *
 * Each overlay is a [Disposable] parented to **this service** (never to the editor/project directly),
 * so overlays also release on dynamic plugin unload; `editorReleased` disposes them eagerly.
 *
 * It is also the position-sync owner (ARCHITECTURE §3.2): besides the aggregate [currentPositions]
 * the export path flushes at submit time, it flushes live-marker positions into the store at the two
 * discrete in-IDE sync points — **editor close** ([release]) and **document save** ([syncPositions],
 * wired to [FileDocumentManagerListener.beforeDocumentSaving]) — so a persisted comment's line range
 * matches what the user sees without a per-keystroke write.
 */
@Service(Service.Level.PROJECT)
class EditorReviewOverlayService(private val project: Project) : Disposable {

    private val overlays = HashMap<Editor, EditorReviewOverlay>()

    init {
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = maybeCreate(event.editor)

            override fun editorReleased(event: EditorFactoryEvent) = release(event.editor)
        }, this)

        // Document save is a position-sync point (ARCHITECTURE §3.2): flush the saved document's
        // live-marker positions into the store just before the write. Subscribed on the application
        // bus (saves are an application-level event) and parented to this service, mirroring how the
        // EditorFactoryListener above is parented, so it disconnects on dispose / plugin unload.
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeDocumentSaving(document: Document) = syncPositions(document)
            })

        // Seed: editorCreated won't fire for editors already open when the project loads.
        for (editor in factory.allEditors) maybeCreate(editor)
    }

    /**
     * The aggregated position-sync seam (ARCHITECTURE §3.2): every open overlay's comments at their
     * CURRENT line ranges, read off the live markers. The delivery stage flushes these into the
     * store (via [io.github.zerlok.agentsessionrelay.logic.ReviewBatchService.updatePosition]) at
     * submit time, so the exported ranges are current without a per-keystroke write.
     */
    fun currentPositions(): Map<CommentId, Subject> {
        val result = HashMap<CommentId, Subject>()
        for (overlay in overlays.values) result.putAll(overlay.currentPositions())
        return result
    }

    /**
     * Document-save position-sync point (ARCHITECTURE §3.2): flush the live-marker positions of every
     * overlay anchored to [document] into the store, so the persisted line ranges match what the user
     * sees at save time — not their authoring-time lines. Scoped to the saved document (an unrelated
     * save leaves other files' markers untouched); across splits of the same document the duplicate
     * flushes collapse idempotently ([ReviewBatchService.updatePosition] no-ops when unchanged).
     */
    private fun syncPositions(document: Document) {
        for (overlay in overlays.values) if (overlay.document === document) flush(overlay)
    }

    private fun maybeCreate(editor: Editor) {
        if (editor.project != project) return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (FileDocumentManager.getInstance().getFile(editor.document) == null) return
        if (overlays.containsKey(editor)) return

        val overlay = EditorReviewOverlay(project, editor)
        Disposer.register(this, overlay)
        overlays[editor] = overlay
    }

    private fun release(editor: Editor) {
        val overlay = overlays.remove(editor) ?: return
        // Editor close is a position-sync point (ARCHITECTURE §3.2: "export, save, editor close"):
        // flush the last-known live-marker positions into the store before the markers go away, so a
        // comment edited then closed still exports its current line — not its authoring-time line.
        flush(overlay)
        Disposer.dispose(overlay)
    }

    /**
     * The shared position flush the editor-close ([release]) and document-save ([syncPositions]) sync
     * points both run, so they can never drift: push [overlay]'s CURRENT live-marker positions into the
     * store. Idempotent across splits and repeated calls ([ReviewBatchService.updatePosition] no-ops
     * when the subject is unchanged). Guarded by `!project.isDisposed`.
     */
    private fun flush(overlay: EditorReviewOverlay) {
        if (project.isDisposed) return
        val service = ReviewBatchService.getInstance(project)
        for ((id, subject) in overlay.currentPositions()) service.updatePosition(id, subject)
    }

    override fun dispose() {
        // Overlays were registered as children of this disposable; they release with it.
        overlays.clear()
    }

    companion object {
        fun getInstance(project: Project): EditorReviewOverlayService = project.service<EditorReviewOverlayService>()
    }
}
