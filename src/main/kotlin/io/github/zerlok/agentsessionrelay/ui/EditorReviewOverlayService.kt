package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
 * Project-scoped owner of the per-editor [EditorReviewOverlay] and [InlineWidthWatcher] **and** of the
 * per-document [DocumentReviewMarkers] (ARCHITECTURE §3.3). It listens on the application-wide
 * [EditorFactory] and creates them on `editorCreated`, frees them on `editorReleased`, and seeds from
 * [EditorFactory.getAllEditors] at startup (that event fires only for editors opened afterward). It
 * handles only editors whose `project` matches, whose `editorKind == MAIN_EDITOR`, and whose document
 * has a file.
 *
 * They have different scopes and therefore different lifetimes: an overlay and its width watcher are
 * created and disposed with their editor, while a document's markers exist while **any** qualifying
 * editor shows that document — created with the first, disposed after the last one's close flush
 * (design D2). The ref-count is the set of live overlays itself, so there is no second counter to fall
 * out of step, and all are driven entirely from this one `editorCreated`/`editorReleased` path — no
 * extra platform listener to leak.
 *
 * All are [Disposable]s parented to **this service** (never to the editor/project directly), so they
 * also release on dynamic plugin unload; `editorReleased` disposes them eagerly.
 *
 * It is also the position-sync-point owner (ARCHITECTURE §3.2): besides the aggregate
 * [currentPositions] the delivery stage flushes at submit time, it flushes live-marker positions into
 * the store at the two discrete in-IDE sync points — **editor close** ([release]) and **document save**
 * ([syncPositions], wired to [FileDocumentManagerListener.beforeDocumentSaving]) — so a persisted
 * comment's line range matches what the user sees without a per-keystroke write.
 *
 * Anchor *verification* is deliberately not here. It reads file content rather than markers, so it
 * covers a comment whose file is closed, and it lives in
 * [io.github.zerlok.agentsessionrelay.delivery.ReviewDeliveryService] where I/O is legal.
 */
@Service(Service.Level.PROJECT)
class EditorReviewOverlayService(private val project: Project) : Disposable {

    private val overlays = HashMap<Editor, EditorReviewOverlay>()

    // Keyed by Document identity (a Document has no value equality), one per document with at least one
    // live overlay above.
    private val markers = HashMap<Document, DocumentReviewMarkers>()

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

        // Seed editors already open before this service existed (editorCreated fires only for later
        // ones). Seeding builds inlays and reads the editor viewport — EDT-only — but the service can
        // be instantiated off the EDT (the RelayHoverInstaller ProjectActivity touches it on a
        // background dispatcher at startup), so marshal the seed onto the EDT. editorCreated/Released
        // already arrive on the EDT.
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            seedExistingEditors()
        } else {
            app.invokeLater({ if (!project.isDisposed) seedExistingEditors() }, ModalityState.nonModal())
        }
    }

    private fun seedExistingEditors() {
        for (editor in EditorFactory.getInstance().allEditors) maybeCreate(editor)
    }

    /**
     * The aggregated position-sync seam (ARCHITECTURE §3.2): every open document's comments at their
     * CURRENT line ranges, read off the live markers. The delivery stage flushes these into the
     * store (via [io.github.zerlok.agentsessionrelay.logic.ReviewBatchService.updatePosition]) at
     * submit time, so the exported ranges are current without a per-keystroke write.
     */
    fun currentPositions(): Map<CommentId, Subject> {
        val result = HashMap<CommentId, Subject>()
        for (owner in markers.values) result.putAll(owner.currentPositions())
        return result
    }

    /**
     * Document-save position-sync point (ARCHITECTURE §3.2): flush the live-marker positions of
     * [document] into the store, so the persisted line ranges match what the user sees at save time —
     * not their authoring-time lines. Scoped to the saved document (an unrelated save leaves other
     * files' markers untouched).
     */
    private fun syncPositions(document: Document) {
        markers[document]?.let { flush(it) }
    }

    private fun maybeCreate(editor: Editor) {
        if (editor.project != project) return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (FileDocumentManager.getInstance().getFile(editor.document) == null) return
        if (overlays.containsKey(editor)) return

        // First qualifying editor for this document brings its markers to life; a second split reuses
        // them, which is what makes one comment paint one gutter bar rather than one per editor.
        val owner = markers.getOrPut(editor.document) {
            DocumentReviewMarkers(project, editor.document).also { Disposer.register(this, it) }
        }
        // The inline surfaces' live width source (responsive-inline-comment-surfaces D2). Per *editor*,
        // like the overlay and for the same reason — the viewport it watches is the editor's — but
        // published on the editor rather than held here, because the box is built per project by
        // [CommentDraftController] and must find the same one the card uses.
        Disposer.register(this, InlineWidthWatcher.install(editor))

        val overlay = EditorReviewOverlay(project, editor, owner)
        Disposer.register(this, overlay)
        overlays[editor] = overlay
    }

    private fun release(editor: Editor) {
        val overlay = overlays.remove(editor) ?: return
        val document = overlay.document
        // Editor close is a position-sync point (ARCHITECTURE §3.2: "export, save, editor close"):
        // flush the last-known live-marker positions into the store before the markers go away, so a
        // comment edited then closed still exports its current line — not its authoring-time line.
        // Deliberately BEFORE the markers may be disposed below, and unconditional: closing one split
        // of two leaves the markers alive, and the flush is idempotent anyway.
        markers[document]?.let { flush(it) }
        Disposer.dispose(overlay)
        // After the overlay, so its cards have already detached from the watcher (a disposed card's
        // inlay detaches it) rather than being notified by a watcher that outlives them.
        InlineWidthWatcher.of(editor)?.let { Disposer.dispose(it) }
        // Last editor on this document: its markers have nothing left to paint into.
        if (overlays.values.none { it.document === document }) {
            markers.remove(document)?.let { Disposer.dispose(it) }
        }
    }

    /**
     * The shared position flush the editor-close ([release]) and document-save ([syncPositions]) sync
     * points both run, so they can never drift: push [owner]'s CURRENT live-marker positions into the
     * store. Idempotent across repeated calls ([ReviewBatchService.updatePosition] no-ops when the
     * subject is unchanged). Guarded by `!project.isDisposed`.
     */
    private fun flush(owner: DocumentReviewMarkers) {
        if (project.isDisposed) return
        val service = ReviewBatchService.getInstance(project)
        for ((id, subject) in owner.currentPositions()) service.updatePosition(id, subject)
    }

    override fun dispose() {
        // Overlays and marker owners were registered as children of this disposable; they release with it.
        overlays.clear()
        markers.clear()
    }

    companion object {
        fun getInstance(project: Project): EditorReviewOverlayService = project.service<EditorReviewOverlayService>()
    }
}
