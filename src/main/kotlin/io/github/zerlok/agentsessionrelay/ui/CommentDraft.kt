package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import io.github.zerlok.agentsessionrelay.domain.Anchoring
import io.github.zerlok.agentsessionrelay.domain.ReviewComment
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.math.abs

/**
 * One in-progress review comment: a blue rectangle over the commented line range plus an
 * inline comment box rendered as a block inlay *below* the range (it pushes the following code down
 * rather than floating over it — GitHub/GitLab style). The box inlay is full-width, but its visible
 * content is capped at the editor's right margin via [InlineWidth] (comment-box-sizing) so it reads
 * as a column rather than an edge-to-edge stripe.
 *
 * The commented range is **adjustable after the box opens** (`adjustable-comment-range`): the top
 * and bottom borders of the wash are draggable resize grips. Per D1, an edge-drag *hides* the box
 * (disposing the inlay so it reserves no vertical space) for an unobstructed code view and rebuilds
 * it once on release under the range's new bottom line, with the typed body preserved and focus
 * returned via the same deferred [IdeFocusManager] path the box already uses. The wash keeps
 * rendering and resizes live throughout the drag. Edge hit-testing / cursor / consume() are driven
 * by [RelayHoverListener] on the shared editor mouse channel and routed here via the internal
 * `onMouse*` handlers while this is the project's active draft.
 *
 * [submit] hands the captured comment to [ReviewBatchService] (the logic layer); user feedback is
 * driven off the store event by `ReviewBatchNotifier`, not raised here.
 *
 * The same box does double duty as the **edit** surface (design D1): when [editing] is a stored
 * comment, the body field is seeded with its body, the box opens over its current range, and
 * [submit] routes to an in-place `updateBody` + `updatePosition` (same id) instead of `addComment`.
 * Everything else — the wash, edge-drag resize, key capture, deferred focus — is identical.
 */
class CommentDraft private constructor(
    private val editor: EditorEx,
    private var start: Int,
    private var end: Int,
    private val editing: ReviewComment?,
    private val onClose: () -> Unit,
) : Disposable {

    /** Which border of the wash is being hovered / dragged. */
    private enum class Edge { TOP, BOTTOM }

    // The wash background attributes, reused every time the highlighter is (re)created on resize.
    private val attributes = TextAttributes().apply { backgroundColor = RelayStyle.RANGE_WASH }

    // Paints the brighter/thicker top and bottom edge lines that signal draggability (D4). It reads
    // the current start/end and the hovered/dragged edge off the draft, so a bare repaint reflects
    // both a live resize and a hover change without touching the highlighter.
    private val edgeRenderer = CustomHighlighterRenderer { _, _, g -> paintEdges(g) }

    // Live wash over the commented lines; recreated on each range change (positions can't be moved
    // on an existing RangeHighlighter). This is the VIEW's live position marker (ARCHITECTURE §3.2).
    private var highlighter: RangeHighlighter = createHighlighter()

    // The comment body lives in an inner IntelliJ editor (an EditorTextField), so while it is focused
    // `CommonDataKeys.EDITOR` resolves to *this* editor and every editing keystroke — selection,
    // word-nav, word-delete, backspace, clipboard, undo, and newline-on-Enter — acts on the body
    // natively rather than leaking to the host editor (D1). The field (and its document) is persistent
    // across hide/rebuild, so its text is preserved for free; each rebuild only re-wraps it in a fresh
    // panel/inlay (D4). A subclassed preferred height floors the box at a compact [BODY_ROWS]-row
    // footprint (comment-box-sizing) and lets the field grow past that as the body is typed.
    private val bodyField: EditorTextField = object : EditorTextField(
        // In edit mode the stored body is the document's *initial content*, not a change applied to an
        // empty one (D4). Seeding afterwards — `bodyField.text = editing.body` — is a real document
        // change, recorded by the platform's undo machinery into whatever command is open (the edit
        // action's own), so the first Ctrl+Z in a reopened box rolled the body back to empty and wiped
        // the saved text. Text handed to `createDocument` fires no change event, so there is nothing
        // before the user's own first edit for undo to reach.
        EditorFactory.getInstance().createDocument(editing?.body ?: ""),
        editor.project,
        FileTypes.PLAIN_TEXT,
        /* isViewer = */ false,
        /* oneLineMode = */ false,
    ) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val lineHeight = this.editor?.lineHeight ?: this@CommentDraft.editor.lineHeight
            size.height = maxOf(size.height, lineHeight * BODY_ROWS)
            return size
        }
    }.apply {
        // Soft-wrap the plain-text body so long lines fold like the old word-wrapping text area
        // instead of scrolling horizontally.
        addSettingsProvider { innerEditor -> innerEditor.settings.isUseSoftWraps = true }
        // A multiline EditorTextField draws no border of its own, so on its own it blends into the
        // panel. Restore the framed "white input inside the gray box" look the old JBScrollPane gave:
        // a 1px field line plus a little inner padding around the text. The line is Relay's accent
        // rather than the theme's frame color (design R2), so the one place the user types is the one
        // place the box is accented — matching the primary action it feeds.
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(RelayStyle.ACCENT, 1),
            JBUI.Borders.empty(3, 5),
        )
        // ...and the field must paint that padding itself (design R6). EditorTextField extends
        // NonOpaquePanel, so without this the 3x5 ring inside the accent line is never painted and the
        // BOX's surface shows through it — the frame then reads as a rectangle floating around the input
        // instead of as the input's own frame. Enforcing the editor's own background (rather than
        // leaving the field's default, which falls back to UIUtil.getTextFieldBackground() — a
        // different color from the editor background in dark themes) also pushes the same color into
        // the inner editor when it is created, so the ring and the text area match by construction.
        isOpaque = true
        // Qualified: inside this apply block, a bare `editor` is EditorTextField's own (still-null) one.
        background = this@CommentDraft.editor.colorsScheme.defaultBackground
    }
    private val resizeCursor: Cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)

    private var inlay: Inlay<*>? = null

    // The live box panel handed to the inlay. Held only so the document listener can revalidate the
    // *current* one: unlike [bodyField], the panel is a new instance after every edge-drag rebuild.
    private var boxPanel: JComponent? = null
    private var shortcutsRegistered = false
    private var hoveredEdge: Edge? = null
    private var draggingEdge: Edge? = null

    init {
        // Edit mode pre-fills the body with the comment's current text so the user revises in place —
        // done at document construction above, not here, so undo cannot reach past it (D4).
        // Re-measure the box on every body edit (D2-R). Registered here — once per draft, on the
        // *retained* field's document, parented to the draft — for the same reason [registerShortcuts]
        // registers on the wrapper: showBox/hideBox tear down and rebuild the panel and the inner
        // editor on each edge-drag, so anything hung off those would need re-registering per rebuild.
        // Parenting to the draft makes the draft a Disposer parent — every teardown must therefore go
        // through `Disposer.dispose(draft)` (see [create]), never a bare `dispose()`.
        bodyField.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleRemeasure()
            },
            this,
        )
    }

    /**
     * Applies the body's new height to the box on the edit that caused it. `revalidate()` is the whole
     * mechanism (D2-R): it schedules the layout pass that reaches
     * `EditorEmbeddedComponentManager$MyRenderer.doLayout`/`validate` →
     * `synchronizeBoundsWithInlay`, which reads the panel's *preferred* height, `setBounds`es the
     * renderer and calls [Inlay.update] itself. Calling [Inlay.update] from here instead would be
     * inert — `MyRenderer.calcHeightInPixels` reports the renderer's *current* Swing height, so before
     * that layout pass there is nothing new to report. [EditorTextField] does not revalidate on
     * `documentChanged`, which is why the pass otherwise waits for an unrelated layout and the box
     * reads as "resizing after I stop typing". Deferred to the EDT queue so the inner editor has
     * finished recomputing soft wraps first; that is what makes a long line that *wraps* (no newline
     * typed) grow the box too. The validity guard mirrors [showBox]'s deferred focus request — the
     * draft can be submitted, cancelled or drag-hidden in between.
     */
    private fun scheduleRemeasure() {
        ApplicationManager.getApplication().invokeLater {
            if (inlay?.isValid != true) return@invokeLater
            boxPanel?.revalidate()
            boxPanel?.repaint()
        }
    }

    private fun doSubmit() {
        submit(bodyField.text)
        onClose()
    }

    private fun submit(body: String) {
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val document = editor.document

        val (startLine, endLine) = liveRange()
        val subject =
            if (startLine == endLine) Subject.Line(file.url, startLine)
            else Subject.LineRange(file.url, startLine, endLine)
        val service = ReviewBatchService.getInstance(project)

        val editing = editing
        if (editing == null) {
            // New comment: capture the anchor seeds and add it (baseline behavior). Both seeds are read
            // from the SAME live range as the subject above, so a comment's anchoring data always
            // describes the lines it was actually stored against.
            val anchorText = document.getText(TextRange(rangeStartOffset(startLine), rangeEndOffset(endLine)))
            val contextHash = Anchoring.contextHash(contextWindow(startLine, endLine))
            service.addComment(subject, body.trim(), anchorText, contextHash)
        } else {
            // Edit: an in-place update of the same comment (design D1/D4). Both commands publish
            // `commentUpdated`, and `updatePosition` is a no-op when the range didn't move.
            service.updateBody(editing.id, body.trim())
            service.updatePosition(editing.id, subject)
        }
    }

    /**
     * The line range this draft is sitting on **right now** (design D8) — the range everything a submit
     * stores is derived from. [highlighter] is the draft's declared position source and absorbs every
     * document change the box lives through (an edit elsewhere in the file, a refresh from disk), so
     * reading it here is what makes the stored comment describe the lines the user is actually looking
     * at rather than the ones that were under the box when it opened.
     *
     * The fallback for an invalidated highlighter clamps [start]/[end] into the current document. It is
     * the one clamp left in Relay, and it is a different animal from the one design D4 removed: it
     * bounds a comment being *created now* — the alternative being an [IndexOutOfBoundsException] out
     * of `getLineStartOffset` when the document shrank under an open box — rather than overwriting a
     * position already recorded in the store.
     */
    private fun liveRange(): Pair<Int, Int> {
        val document = editor.document
        val lastLine = (document.lineCount - 1).coerceAtLeast(0)
        if (highlighter.isValid) {
            val first = document.getLineNumber(highlighter.startOffset)
            return first to maxOf(first, document.getLineNumber(highlighter.endOffset))
        }
        val first = start.coerceIn(0, lastLine)
        return first to end.coerceIn(first, lastLine)
    }

    private fun rangeStartOffset(startLine: Int): Int = editor.document.getLineStartOffset(startLine)

    private fun rangeEndOffset(endLine: Int): Int = editor.document.getLineEndOffset(endLine)

    /** The comment's lines plus [CONTEXT_LINES] of surrounding code — the re-anchoring seed. */
    private fun contextWindow(startLine: Int, endLine: Int): String {
        val document = editor.document
        val first = (startLine - CONTEXT_LINES).coerceAtLeast(0)
        val last = (endLine + CONTEXT_LINES).coerceAtMost(document.lineCount - 1)
        return document.getText(TextRange(document.getLineStartOffset(first), document.getLineEndOffset(last)))
    }

    // ---- range + wash -----------------------------------------------------------------------

    private fun createHighlighter(): RangeHighlighter {
        val document = editor.document
        val highlighter = editor.markupModel.addRangeHighlighter(
            document.getLineStartOffset(start),
            document.getLineEndOffset(end),
            HighlighterLayer.SELECTION - 1,
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        highlighter.customRenderer = edgeRenderer
        // Extend the range highlight into the line-number gutter (D3): the shared colored bar, painted
        // in the reused wash color so the wash and the bar read as one highlight. Recreated with the
        // wash on each resize (this whole method runs again), so the bar tracks the range live.
        highlighter.lineMarkerRenderer = RangeHighlight.gutterBar(RelayStyle.RANGE_WASH)
        return highlighter
    }

    /**
     * Moves the wash to [newStart]..[newEnd], clamped to the document bounds and to a minimum of one
     * line (the two edges cannot cross). A RangeHighlighter's offsets are immutable, so the wash is
     * recreated in place; the highlighter keeps rendering throughout, so it never blinks during a
     * drag (task 4.3).
     */
    private fun resize(newStart: Int, newEnd: Int) {
        val lastLine = editor.document.lineCount - 1
        val s = newStart.coerceIn(0, lastLine)
        val e = newEnd.coerceIn(s, lastLine)
        if (s == start && e == end) return
        start = s
        end = e
        if (highlighter.isValid) editor.markupModel.removeHighlighter(highlighter)
        highlighter = createHighlighter()
        repaintEdges()
    }

    // ---- edge affordance + drag (routed from RelayHoverListener) ---------------------------

    internal fun handles(editor: Editor): Boolean = this.editor === editor

    /**
     * Editor-Y of the top border of the range: the top of [start]'s **first** visual row.
     *
     * Every Y here is derived from visual rows, not logical lines, because a soft-wrapped logical
     * line is one line occupying several rows — logical math anchors the range to its first row only
     * and leaves painting, hit-testing and the drag mapping disagreeing about where the line ends. A
     * line-start offset is never a soft-wrap position, so its visual line *is* the range's first row.
     */
    private fun topEdgeY(): Int =
        editor.visualLineToY(editor.offsetToVisualLine(editor.document.getLineStartOffset(start), false))

    /**
     * Editor-Y of the bottom border of the range: the bottom of [end]'s **last** visual row (see
     * [topEdgeY] for why visual rows). The line-*end* offset taken with `beforeSoftWrap = false`
     * resolves to that last row, and `visualLineToYRange` reports the row's own extent — block inlays
     * hanging below it (this draft's own comment box) are excluded, which is exactly the boundary the
     * edge belongs on.
     */
    private fun bottomEdgeY(): Int =
        editor.visualLineToYRange(editor.offsetToVisualLine(editor.document.getLineEndOffset(end), false))[1]

    /** The edge whose grab zone contains editor-Y [y], preferring the nearer one; null if neither. */
    private fun edgeAt(y: Int): Edge? {
        val grab = JBUI.scale(GRAB_ZONE_DP)
        val dTop = abs(y - topEdgeY())
        val dBottom = abs(y - bottomEdgeY())
        return when {
            dTop <= grab && dTop <= dBottom -> Edge.TOP
            dBottom <= grab -> Edge.BOTTOM
            else -> null
        }
    }

    /**
     * Maps an editor-Y to a document line, clamped to the document bounds. Stated through the visual
     * row at [y] (rather than `xyToLogicalPosition`, which would also resolve a column off a made-up
     * x) so that pointing at *any* row of a soft-wrapped line yields that one logical line.
     */
    private fun lineAtY(y: Int): Int =
        editor.visualToLogicalPosition(VisualPosition(editor.yToVisualLine(y), 0))
            .line.coerceIn(0, editor.document.lineCount - 1)

    private fun setHover(edge: Edge?) {
        editor.setCustomCursor(this, if (edge != null) resizeCursor else null)
        if (edge != hoveredEdge) {
            hoveredEdge = edge
            repaintEdges()
        }
    }

    /**
     * On edge-press: enter edge-drag mode, capture the body implicitly (the text area is retained),
     * and dispose the inlay so the box reserves no space while the code is sized (D1). The wash stays.
     */
    private fun beginDrag(edge: Edge) {
        draggingEdge = edge
        hideBox()
        editor.setCustomCursor(this, resizeCursor)
        repaintEdges()
    }

    /** On release: leave edge-drag mode and rebuild the box under the range's new bottom line (D1). */
    private fun endDrag() {
        draggingEdge = null
        showBox()
        editor.setCustomCursor(this, null)
        repaintEdges()
    }

    /**
     * Mouse moved (no button): show the resize cursor + brighten the edge when the pointer is within
     * an edge grab zone. Returns true when an edge is hoverable, so the caller suppresses the hover
     * "+" so the two affordances don't compete (task 2.3).
     */
    internal fun onMouseMoved(y: Int, editingArea: Boolean): Boolean {
        if (draggingEdge != null) return true
        val edge = if (editingArea) edgeAt(y) else null
        setHover(edge)
        return edge != null
    }

    /**
     * Mouse pressed: if it lands in an edge grab zone, claim the gesture (start an edge-drag) so the
     * caller can consume() the event and the editor never begins a text selection (D2). Returns true
     * when the gesture was claimed.
     */
    internal fun onMousePressed(y: Int, editingArea: Boolean): Boolean {
        if (draggingEdge != null || !editingArea) return false
        val edge = edgeAt(y) ?: return false
        beginDrag(edge)
        return true
    }

    /**
     * Mouse dragged in edge-drag mode: map Y to a line and resize live. Returns true while dragging.
     *
     * The mapping is direction-aware because the two edge Ys use opposite boundary conventions:
     * [topEdgeY] is the *inclusive* top of the first row, while [bottomEdgeY] is the *exclusive*
     * bottom of the last one — i.e. already the next line's first row. Reading the bottom drag at
     * `y - 1` keeps "release on the row you want to be last" resolving to that row.
     */
    internal fun onMouseDragged(y: Int): Boolean {
        val edge = draggingEdge ?: return false
        when (edge) {
            Edge.TOP -> resize(lineAtY(y).coerceAtMost(end), end)
            Edge.BOTTOM -> resize(start, lineAtY(y - 1).coerceAtLeast(start))
        }
        return true
    }

    /** Mouse released: end an in-progress edge-drag (rebuild the box). Returns true if one was active. */
    internal fun onMouseReleased(): Boolean {
        if (draggingEdge == null) return false
        endDrag()
        return true
    }

    /** Pointer left the editor: drop the hover affordance (but never abort an in-progress drag). */
    internal fun onMouseExited() {
        if (draggingEdge == null) setHover(null)
    }

    private fun repaintEdges() {
        editor.contentComponent.repaint()
    }

    // Painting goes through the same [topEdgeY] / [bottomEdgeY] the hit-testing uses; restating the
    // geometry here is what let the two drift apart in the first place.
    private fun paintEdges(g: Graphics) {
        val width = editor.contentComponent.width
        paintEdge(g, width, Edge.TOP, topEdgeY())
        paintEdge(g, width, Edge.BOTTOM, bottomEdgeY())
    }

    /**
     * Draws one edge stroke *inside* the range — `y .. y + thickness` for the top, `y - thickness ..
     * y` for the bottom — rather than centred on the boundary. The box inlay's component begins at
     * exactly [bottomEdgeY], so a centred bottom stroke is half-painted over by it and the wash reads
     * as open-ended; drawn inward, the stroke is the last thing before the box and closes the region.
     * Hit-testing keeps using the boundary Ys, so the grab zones and the drag feel are unchanged.
     */
    private fun paintEdge(g: Graphics, width: Int, edge: Edge, y: Int) {
        val active = hoveredEdge == edge || draggingEdge == edge
        val thickness = if (active) JBUI.scale(2) else 1
        g.color = if (active) RelayStyle.ACCENT else RelayStyle.EDGE_IDLE
        g.fillRect(0, if (edge == Edge.TOP) y else y - thickness, width, thickness)
    }

    // ---- inline box (block inlay) ----------------------------------------------------------

    /**
     * (Re)builds the comment box as a block inlay under the range's current bottom line and returns
     * whether it succeeded. Called once on open and again on each edge-drag release; the retained
     * [textArea] carries the body across, so the rebuilt box shows the previously typed text. Focus
     * is re-requested through the deferred [IdeFocusManager] path so the box — not the editor —
     * takes the keyboard.
     */
    private fun showBox(): Boolean {
        // One short label in BOTH modes (design R3): "Comment" — GitHub's primary review-comment verb —
        // names what the button produces, which is true whether the comment is new or revised. The old
        // "Save" when editing named the storage operation instead and made one control look like two.
        val addButton = primaryButton("Comment")
        val cancelButton = secondaryButton("Cancel")
        val panel = buildPanel(editor, bodyField, addButton, cancelButton)

        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(),
            null,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* showWhenFolded = */ true,
            /* fullWidth = */ true,
            /* priority = */ 0,
            /* offset = */ editor.document.getLineEndOffset(end),
        )
        val newInlay = EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, properties)
            ?: return false
        inlay = newInlay
        boxPanel = panel

        addButton.addActionListener { doSubmit() }
        cancelButton.addActionListener { onClose() }
        // Register the box's key handling once — parented to the draft, so it survives box rebuilds
        // (the field is retained) and is cleaned up when the draft is disposed.
        if (!shortcutsRegistered) {
            registerShortcuts(bodyField, this, submit = ::doSubmit, cancel = onClose)
            shortcutsRegistered = true
        }

        // The inlay isn't laid out yet, so requesting focus now (or via a bare
        // requestFocusInWindow) no-ops and the editor keeps the keyboard — every keystroke
        // then edits the code, not the box. Defer to after layout and route through
        // IdeFocusManager, which owns the async focus queue and wins over the editor
        // re-grabbing focus after the gutter click (or the edge-drag release).
        val focusManager = editor.project?.let { IdeFocusManager.getInstance(it) }
        ApplicationManager.getApplication().invokeLater {
            if (!newInlay.isValid) return@invokeLater
            if (focusManager != null) focusManager.requestFocus(bodyField, true)
            else bodyField.requestFocusInWindow()
        }
        return true
    }

    /** "Hide" the box for a drag = dispose its inlay; a hidden-but-present inlay would still reserve space. */
    private fun hideBox() {
        inlay?.let { if (it.isValid) Disposer.dispose(it) }
        inlay = null
        boxPanel = null
    }

    override fun dispose() {
        editor.setCustomCursor(this, null)
        if (highlighter.isValid) {
            editor.markupModel.removeHighlighter(highlighter)
        }
        hideBox()
    }

    companion object {
        // Lines of surrounding code hashed into the anchor seed on each side of the range.
        private const val CONTEXT_LINES = 3

        // Minimum visible rows for the body field (comment-box-sizing). Lowered from the old 4-row
        // floor to a compact 2 so an empty/short box is short — leaving more code visible — and the
        // field's own preferred-size growth takes over once the body wraps past two rows. The exact
        // floor (1 vs 2) is a visual taste-call to settle in a running IDE; 2 keeps a hint of room to
        // type without the old bulk.
        private const val BODY_ROWS = 2

        // Half-thickness (unscaled dp) of the grab band on each side of an edge's Y for hit-testing.
        private const val GRAB_ZONE_DP = 4

        /**
         * The two client properties [com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI] consults
         * *first*, ahead of the default-button gradient and the plain-button colors: `getBackground`
         * reads the fill and `getButtonTextColor` the label color, each returning immediately when the
         * property is a `Color`. Referenced by name rather than through the UI class, which is an
         * internal platform LaF type.
         *
         * This is the route to a Relay-colored primary action inside an inlay. The platform's usual
         * one — `JButton.isDefaultButton()` — is unavailable here: an inlay's panel has no root pane,
         * so no button in it can ever be the default.
         */
        private const val BUTTON_FILL_PROPERTY = "JButton.backgroundColor"
        private const val BUTTON_TEXT_PROPERTY = "JButton.textColor"

        /**
         * The border counterpart of the two above, read by `DarculaButtonPainter.getBorderPaint` as a
         * `Color` and returned for an enabled button ahead of its default/plain-button branches. Without
         * it the painter frames the accent fill in the *plain* button's gray outline (design R7).
         */
        private const val BUTTON_BORDER_PROPERTY = "JButton.borderColor"

        /** Unscaled dp gap between the two actions. Carried by a strut, not by the row's layout — see [buildPanel]. */
        private const val ACTION_GAP_DP = 8

        /**
         * The box's primary action: one solid accent shape — fill, outline and a label color legible on
         * it (R3/R7). The outline is set to the fill rather than left to the painter, which would
         * otherwise ring the accent in the theme's plain-button gray.
         */
        private fun primaryButton(text: String): JButton = plainButton(text).apply {
            putClientProperty(BUTTON_FILL_PROPERTY, RelayStyle.ACCENT_FILL)
            putClientProperty(BUTTON_BORDER_PROPERTY, RelayStyle.ACCENT_FILL)
            putClientProperty(BUTTON_TEXT_PROPERTY, RelayStyle.ACCENT_FILL_TEXT)
        }

        /** The box's secondary action: the theme's ordinary button, unfilled beside the primary one. */
        private fun secondaryButton(text: String): JButton = plainButton(text)

        /**
         * A button that paints *only* itself. A `JButton` is opaque by default while the Darcula-family
         * UI paints a **rounded** shape inside its bounds, so `UIManager`'s flat `Button.background`
         * shows through at the four corners — the stray gray patch the review reported around both
         * actions. Non-opaque, the box's own fill shows through there instead (R3).
         */
        private fun plainButton(text: String): JButton = JButton(text).apply { isOpaque = false }

        /**
         * Opens a draft to author a new comment over [startLine]..[endLine]. [onClose] is invoked
         * when the user submits or cancels, so the owner can dispose this draft. Returns null if the
         * editor can't host an inline component.
         */
        fun open(editor: Editor, startLine: Int, endLine: Int, onClose: () -> Unit): CommentDraft? =
            create(editor, startLine, endLine, editing = null, onClose)

        /**
         * Opens a draft to **edit** [comment] (design D1): the same box, seeded with the comment's
         * body and opened over its current line range, resubmitting as an in-place update. Returns
         * null if the editor can't host an inline component or the comment has no line anchor.
         */
        fun openForEdit(editor: Editor, comment: ReviewComment, onClose: () -> Unit): CommentDraft? {
            val (start, end) = when (val subject = comment.subject) {
                is Subject.Line -> subject.line to subject.line
                is Subject.LineRange -> subject.startLine to subject.endLine
                else -> return null
            }
            return create(editor, start, end, editing = comment, onClose)
        }

        private fun create(
            editor: Editor,
            startLine: Int,
            endLine: Int,
            editing: ReviewComment?,
            onClose: () -> Unit,
        ): CommentDraft? {
            if (editor !is EditorEx) return null

            val document = editor.document
            if (document.lineCount == 0) return null
            val start = startLine.coerceIn(0, document.lineCount - 1)
            val end = endLine.coerceIn(start, document.lineCount - 1)

            val draft = CommentDraft(editor, start, end, editing, onClose)
            if (!draft.showBox()) {
                // Through the Disposer, never `draft.dispose()`: the draft's `init` parents a document
                // listener to it, which `DocumentImpl` registers as a Disposer *child*, so a direct
                // call would run the draft's own cleanup and leave both nodes in the tree forever.
                Disposer.dispose(draft)
                return null
            }
            return draft
        }

        private fun buildPanel(
            editor: EditorEx,
            bodyField: EditorTextField,
            addButton: JButton,
            cancelButton: JButton,
        ): JComponent {
            // hgap 0, with the gap carried by an explicit strut between the two actions (design R8):
            // FlowLayout reserves its hgap at BOTH ends of the row, so a non-zero hgap inset the whole
            // row from the panel's trailing edge and the actions no longer lined up with the body
            // field's frame above them. The ~4px that still separates a button's painted shape from
            // that edge is the platform's own focus-ring inset, and is left alone.
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(cancelButton)
                add(Box.createHorizontalStrut(JBUI.scale(ACTION_GAP_DP)))
                add(addButton)
            }
            // Base horizontal size (comment-box-sizing feedback): open the box at the shared base width
            // (~80 columns, clamped to the right-margin cap) instead of shrinking to the button row. The
            // box still grows *taller* with the body (height stays super-driven); only width is pinned.
            val cap = InlineWidth.rightMarginPx(editor)
            val baseWidth = InlineWidth.baseWidthPx(editor)
            val content = object : JPanel(BorderLayout(0, JBUI.scale(6))), UiDataProvider {
                override fun getPreferredSize(): Dimension {
                    val size = super.getPreferredSize()
                    size.width = baseWidth
                    return size
                }

                /**
                 * Scopes file-editor actions — undo/redo above all — to the box (D1-R). The box is a
                 * block inlay *inside* the host editor's content component, so the action system's
                 * walk up the Swing hierarchy reaches the host file's `EditorCompositePanel` and
                 * resolves [PlatformCoreDataKeys.FILE_EDITOR] to the *source file's* editor — which is
                 * exactly what `UndoRedoAction` undoes against, so Ctrl+Z while typing a comment
                 * silently edited the user's code. This panel is the nearer provider, so naming the
                 * box's *own* [TextEditor] here shadows the host file's — and that wrapper is what the
                 * platform itself derives for an [EditorTextField] in a dialog, where in-box undo
                 * already works. `EDITOR` is deliberately not set ([EditorTextField] already supplies
                 * it), and the body field is never marked supplementary: that flag claims the body is
                 * not a real editing surface, which is false here.
                 *
                 * The editor-less branch masks rather than falling through to the host file, but it is
                 * a tripwire on an invariant, not a fallback — a null key is *not* safe either (design
                 * D1-R / Risks: it routes undo to the project's global stack). The action system
                 * snapshots from the focus owner upward, so in practice focus is inside the box and
                 * the inner editor exists — but any caller may hand this panel to `DataManager`
                 * directly, so the branch is kept.
                 */
                override fun uiDataSnapshot(sink: DataSink) {
                    val inner = bodyField.editor
                    if (inner == null) sink.setNull(PlatformCoreDataKeys.FILE_EDITOR)
                    else sink.set(PlatformCoreDataKeys.FILE_EDITOR, TextEditorProvider.getInstance().getTextEditor(inner))
                }
            }.apply {
                isOpaque = true
                // The box is the card's *editing state*, not a different kind of panel (design R2):
                // same UI-surface fill, same 1px outline, same 8x12 padding, and — like the card — no
                // accent edge of its own. The two occupy the same screen position for the same comment
                // (the card is suppressed while its box is open), so any difference between them would
                // read as the object changing identity when the user clicks Edit.
                background = RelayStyle.surface()
                border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(8, 12))
                // Show a normal arrow (not the editor's text I-beam) while hovering the box chrome.
                cursor = Cursor.getDefaultCursor()
                // The EditorTextField wraps its own inner editor (with its own scrollbar/soft-wrap),
                // so it is embedded directly rather than in a JBScrollPane.
                add(bodyField, BorderLayout.CENTER)
                add(buttons, BorderLayout.SOUTH)
                // Without a mouse listener the panel's padding/background isn't an event target, so
                // Swing retargets clicks and drags over it to the editor underneath — which then
                // selects code. Listening here makes the panel swallow those events, and a click on
                // the box chrome moves focus into the body field (D3), which forwards to its inner
                // editor and returns editing to the box.
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        bodyField.requestFocusInWindow()
                    }
                })
            }
            // Cap the box at the editor's right margin (comment-box-sizing): a full-width inlay whose
            // visible content is pinned to the leftmost reading-width column, or unchanged full width
            // when no right margin is configured.
            return InlineWidth.capWidth(content, cap)
        }

        private fun registerShortcuts(
            bodyField: EditorTextField,
            parent: Disposable,
            submit: () -> Unit,
            cancel: () -> Unit,
        ) {
            // The inner editor now owns plain-text editing natively, including newline-on-Enter, so
            // no Enter/Shift+Enter shim is needed. Ctrl+Enter/Cmd+Enter (submit) and Esc (cancel) are
            // not plain-text edits, so they stay as component-scoped actions (D2). They are registered
            // on the EditorTextField wrapper — which is retained across rebuilds and is the Swing
            // ancestor of the focused inner-editor component — so IdeKeyEventDispatcher finds them
            // ahead of any keymap/editor action while the box is focused. Registering on the wrapper
            // (rather than the inner editor's contentComponent, which is torn down and recreated on
            // every add/remove) keeps this a single once-per-draft registration that survives the
            // edge-drag rebuild (resolves design.md's open question).
            fun anAction(run: () -> Unit) = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) = run()
            }
            fun shortcuts(vararg keyStrokes: KeyStroke) =
                CustomShortcutSet(*keyStrokes.map { KeyboardShortcut(it, null) }.toTypedArray())
            // Ctrl/Cmd+Enter submits (like a PR review box).
            anAction(submit).registerCustomShortcutSet(
                shortcuts(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK),
                ),
                bodyField,
                parent,
            )
            // Esc cancels.
            anAction(cancel).registerCustomShortcutSet(
                shortcuts(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                bodyField,
                parent,
            )
        }
    }
}
