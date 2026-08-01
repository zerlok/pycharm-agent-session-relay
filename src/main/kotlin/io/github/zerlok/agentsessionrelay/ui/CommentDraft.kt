package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val attributes = TextAttributes().apply { backgroundColor = RANGE_BACKGROUND }

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
        EditorFactory.getInstance().createDocument(""),
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
        // a 1px field-border line plus a little inner padding around the text.
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(3, 5),
        )
    }
    private val resizeCursor: Cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)

    private var inlay: Inlay<*>? = null
    private var shortcutsRegistered = false
    private var hoveredEdge: Edge? = null
    private var draggingEdge: Edge? = null

    init {
        // Edit mode: pre-fill the body with the comment's current text so the user revises in place.
        if (editing != null) bodyField.text = editing.body
    }

    private fun doSubmit() {
        submit(bodyField.text)
        onClose()
    }

    private fun submit(body: String) {
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val document = editor.document

        val subject =
            if (start == end) Subject.Line(file.url, start)
            else Subject.LineRange(file.url, start, end)
        val service = ReviewBatchService.getInstance(project)

        val editing = editing
        if (editing == null) {
            // New comment: capture the anchor seeds and add it (baseline behavior).
            val anchorText = document.getText(TextRange(rangeStartOffset(document), rangeEndOffset(document)))
            val contextHash = Anchoring.contextHash(contextWindow(document))
            service.addComment(subject, body.trim(), anchorText, contextHash)
        } else {
            // Edit: an in-place update of the same comment (design D1/D4). Both commands publish
            // `commentUpdated`, and `updatePosition` is a no-op when the range didn't move.
            service.updateBody(editing.id, body.trim())
            service.updatePosition(editing.id, subject)
        }
    }

    private fun rangeStartOffset(document: Document): Int = document.getLineStartOffset(start)

    private fun rangeEndOffset(document: Document): Int = document.getLineEndOffset(end)

    /** The comment's lines plus [CONTEXT_LINES] of surrounding code — the re-anchoring seed. */
    private fun contextWindow(document: Document): String {
        val first = (start - CONTEXT_LINES).coerceAtLeast(0)
        val last = (end + CONTEXT_LINES).coerceAtMost(document.lineCount - 1)
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
        highlighter.lineMarkerRenderer = RangeHighlight.gutterBar(RANGE_BACKGROUND)
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
        g.color = if (active) EDGE_ACTIVE else EDGE_IDLE
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
        // Short labels (comment-box-sizing feedback): the primary button is a single word — "Comment"
        // to add (GitHub's primary review-comment verb), "Save" when editing — so it doesn't blow up
        // the button row's width.
        val addButton = JButton(if (editing != null) "Save" else "Comment")
        val cancelButton = JButton("Cancel")
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
         * Light blue wash over the commented lines, à la a pull-request review selection. Internal so
         * the shared [RangeHighlight] (the draft's gutter bar and the stored-comment hover highlight)
         * reuses the one color, keeping the two surfaces visually identical (D3).
         */
        internal val RANGE_BACKGROUND = JBColor(Color(0xDD, 0xE7, 0xFF), Color(0x2A, 0x3A, 0x5A))

        /** Idle edge line — a slightly stronger blue than the wash, hinting the border is grabbable. */
        private val EDGE_IDLE = JBColor(Color(0x88, 0xA8, 0xE0), Color(0x3E, 0x54, 0x82))

        /** Hovered/dragged edge line — brighter + thicker to signal the active resize grip (D4). */
        private val EDGE_ACTIVE = JBColor(Color(0x3B, 0x74, 0xE8), Color(0x6E, 0x9B, 0xF0))

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
                draft.dispose()
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
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(cancelButton)
                add(addButton)
            }
            // Base horizontal size (comment-box-sizing feedback): open the box at the shared base width
            // (~80 columns, clamped to the right-margin cap) instead of shrinking to the button row. The
            // box still grows *taller* with the body (height stays super-driven); only width is pinned.
            val cap = InlineWidth.rightMarginPx(editor)
            val baseWidth = InlineWidth.baseWidthPx(editor)
            val content = object : JPanel(BorderLayout(0, JBUI.scale(6))) {
                override fun getPreferredSize(): Dimension {
                    val size = super.getPreferredSize()
                    size.width = baseWidth
                    return size
                }
            }.apply {
                isOpaque = true
                background = editor.colorsScheme.defaultBackground
                // A bordered outer box (1px theme line + 8x12 padding) matching StoredCommentCard's
                // outer frame, so the draft and the read-only card frame identically.
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
