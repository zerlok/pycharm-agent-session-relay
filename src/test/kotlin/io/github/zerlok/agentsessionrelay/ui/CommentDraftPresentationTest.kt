package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import io.github.zerlok.agentsessionrelay.domain.Subject
import io.github.zerlok.agentsessionrelay.logic.ReviewBatchService
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Covers the authoring box's *presentation* (review-annotation "Present the authoring box as the stored
 * card's editing state", design R2/R3): the box is the same object as the read-only card in its editing
 * state, and its two actions are the plugin's own — one accent-filled "Comment", one plain "Cancel",
 * neither painting a background patch.
 *
 * The card is not described here from memory: where the requirement says "the same as the card", the
 * assertion compares a real box against a real [StoredCommentCard] built over the same editor, so the
 * two cannot drift apart without a failure here. Everything is read off a draft opened through
 * [CommentDraftController] over the live editor fixture — the same entry point the gutter "+" uses.
 *
 * What a headless fixture cannot judge, and nothing here claims: whether the accent fill and its white
 * label are legible in a given theme, and whether the card→box transition reads as one object on screen.
 * Those stay running-IDE checks in design.md "## Open Questions".
 */
class CommentDraftPresentationTest : BasePlatformTestCase() {

    private lateinit var controller: CommentDraftController

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.py", "line0\nline1\nline2\nline3\n")
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

    // -- The box is the card's editing state (R2) --

    /**
     * Fill: the box wears the shared surface, which is *not* the editor's text background. Asserted
     * against a real card as well as against the style source, so a change to one surface that misses
     * the other fails here rather than in a screenshot.
     */
    fun `test the box is filled with the same surface as the card, not the editor's`() {
        controller.open(myFixture.editor, 1, 1)
        val box = boxPanel()

        assertTrue("the box must be opaque to show its own fill", box.isOpaque)
        assertEquals(RelayStyle.surface(), box.background)
        assertEquals("box and card must share one fill", card().background, box.background)
        assertFalse(
            "box fill must differ from the editor's text background",
            box.background == (myFixture.editor as EditorEx).colorsScheme.defaultBackground,
        )
    }

    /**
     * Frame: identical insets to the card — same outline weight, same padding — so the box occupies the
     * card's footprint and its content starts on the card's left edge. And, like the card, no accent
     * edge of its own (R1/R2): the accent is reserved for the gutter bar and the body field.
     */
    fun `test the box carries the card's frame and no accent edge`() {
        controller.open(myFixture.editor, 1, 1)
        val box = boxPanel()
        val insets = box.insets

        assertEquals("box and card must be framed identically", card().insets, insets)
        assertEquals("leading and trailing insets must match — no accent line", insets.right, insets.left)
        assertTrue(
            "no pixel of the box's own border may be painted in the accent",
            paintedBorderRow(box).none { it == RelayStyle.ACCENT.rgb },
        )
    }

    /** The one accented edge in the box is the body field's frame — where the user actually types. */
    fun `test the body field is framed in the accent`() {
        controller.open(myFixture.editor, 1, 1)
        val field = bodyField()

        val row = paintedBorderRow(field)
        assertEquals("the field's leading border pixel must be the accent", RelayStyle.ACCENT.rgb, row.first())
        assertEquals("the field's trailing border pixel must be the accent", RelayStyle.ACCENT.rgb, row.last())
    }

    /**
     * ...and that frame hugs the input. [EditorTextField] extends `NonOpaquePanel`, so unless the field
     * is made to paint its own background the padding *inside* the accent line shows the box's surface —
     * a band of box gray between the frame and the text, which is what the screenshot review reported
     * (R6). Both halves are asserted: the field paints at all, and it paints the same color the inner
     * editor does, so the band cannot come back in a different color.
     */
    fun `test the body field paints its own background up to the frame`() {
        controller.open(myFixture.editor, 1, 1)
        val field = bodyField()

        assertTrue("the field must paint the padding inside its frame", field.isOpaque)
        assertEquals((myFixture.editor as EditorEx).colorsScheme.defaultBackground, field.background)
        assertFalse(
            "the padding must not be able to show the box's surface",
            field.background == boxPanel().background,
        )
    }

    /**
     * One font for one object (design D6): the box's body and the card's body render in the same
     * typeface, so clicking Edit does not make the comment look like a different comment. Neither may
     * take it from its Swing class's default — a `JTextArea` inherits the LaF's *Monospaced*
     * `TextArea.font` while an [EditorTextField] carries the UI font — so both are asserted against the
     * single source as well as against each other.
     */
    fun `test the box's body and the card's body render in one font`() {
        controller.open(myFixture.editor, 1, 1)

        val cardBody = find(card()) { it is javax.swing.JTextArea } as JComponent
        assertEquals("the card's body must carry the shared body font", RelayStyle.bodyFont(), cardBody.font)
        assertEquals("the box's body must carry the same one", cardBody.font, bodyField().font)
    }

    // -- The actions (R3) --

    /**
     * One label for one control: the primary action produces a review comment whether the comment is new
     * or revised, so it reads "Comment" in both cases. The edit case is the one the review reported
     * ("rename save to comment action"), so it is opened through the real `openForEdit` path.
     */
    fun `test the primary action reads Comment when authoring and when editing`() {
        controller.open(myFixture.editor, 1, 1)
        assertEquals(listOf("Cancel", "Comment"), buttonLabels())

        val stored = ReviewBatchService.getInstance(project)
            .addComment(Subject.LineRange(myFixture.file.virtualFile.url, 1, 1), "stored body")
        controller.openForEdit(myFixture.editor, stored)

        assertEquals("editing must not rename the action to Save", listOf("Cancel", "Comment"), buttonLabels())
    }

    /**
     * The primary action is filled through the two client properties `DarculaButtonUI` reads first
     * (`getBackground` / `getButtonTextColor`), which is how it wears Relay's accent rather than the
     * theme's default blue — a button inside an inlay has no root pane, so `isDefaultButton()` can never
     * be true for it. Cancel stays unfilled: a second filled button would leave no primary.
     */
    fun `test only the primary action is filled with the accent`() {
        controller.open(myFixture.editor, 1, 1)
        val comment = button("Comment")
        val cancel = button("Cancel")

        assertEquals(RelayStyle.ACCENT_FILL, comment.getClientProperty("JButton.backgroundColor"))
        assertEquals(RelayStyle.ACCENT_FILL_TEXT, comment.getClientProperty("JButton.textColor"))
        assertNull(cancel.getClientProperty("JButton.backgroundColor"))
        assertNull(cancel.getClientProperty("JButton.textColor"))
    }

    /**
     * One solid shape: the outline is the fill. Overriding only the fill left `DarculaButtonPainter`
     * drawing the *plain* button's gray border around the accent — the ring the screenshot review marked
     * on the button's corner (R7). Cancel keeps the theme's border, which is what makes it read as
     * secondary.
     */
    fun `test the primary action's outline is its own fill`() {
        controller.open(myFixture.editor, 1, 1)

        assertEquals(RelayStyle.ACCENT_FILL, button("Comment").getClientProperty("JButton.borderColor"))
        assertNull(button("Cancel").getClientProperty("JButton.borderColor"))
    }

    /**
     * The action row ends where the body field's frame ends (R8). `FlowLayout` reserves its `hgap` at
     * *both* ends of a row, so a non-zero hgap silently inset the whole row from the trailing edge; the
     * gap now rides a strut between the two buttons instead. Measured after a real layout pass at the
     * box's own preferred size, and asserted on the *component* edge — the few px of focus-ring inset
     * inside a button's bounds is the platform's, not ours.
     */
    fun `test the action row ends at the body field's trailing edge`() {
        controller.open(myFixture.editor, 1, 1)
        val box = boxPanel()
        box.setSize(box.preferredSize)
        layOutDeeply(box)

        val field = bodyField()
        val primary = button("Comment")
        val fieldTrailing = field.x + field.width
        val actionTrailing = primary.x + primary.width + (primary.parent as JComponent).x

        assertTrue("the box must have been laid out", fieldTrailing > 0 && primary.width > 0)
        assertEquals("the actions must line up with the field", fieldTrailing, actionTrailing)
    }

    /**
     * Neither action paints Swing's opaque `Button.background` rectangle behind the rounded shape the
     * Darcula-family UI draws — that patch, visible at the four corners of *both* buttons, is what the
     * review reported as "tiny gray background around them".
     */
    fun `test neither action paints a background patch`() {
        controller.open(myFixture.editor, 1, 1)

        assertFalse("the primary action must not be opaque", button("Comment").isOpaque)
        assertFalse("the secondary action must not be opaque", button("Cancel").isOpaque)
    }

    // -- Helpers --

    /** A read-only card over the same editor — the surface the box must match. */
    private fun card(): JPanel {
        val root = StoredCommentCard.build(
            myFixture.editor as EditorEx,
            "look here",
            onEdit = {},
            onDelete = {},
            onHover = {},
        )
        return find(root) { it is javax.swing.JTextArea }.parent as JPanel
    }

    /** The draft's box inlay: the only block inlay carrying a body field in this fixture. */
    private fun boxInlay(): Inlay<*> = myFixture.editor.inlayModel
        .getBlockElementsInRange(0, myFixture.editor.document.textLength)
        .single { inlay -> findOrNull(inlay.surfaceComponent) { c -> c is EditorTextField } != null }

    /** The box's content panel — the body field's parent, i.e. the panel `buildPanel` fills and frames. */
    private fun boxPanel(): JPanel = bodyField().parent as JPanel

    private fun bodyField(): EditorTextField =
        find(boxInlay().surfaceComponent) { it is EditorTextField } as EditorTextField

    private fun buttons(): List<JButton> = boxPanel().let { panel ->
        val found = mutableListOf<JButton>()
        fun walk(c: Component) {
            if (c is JButton) found += c
            if (c is Container) c.components.forEach(::walk)
        }
        walk(panel)
        found
    }

    private fun buttonLabels(): List<String> = buttons().map { it.text }

    private fun button(text: String): JButton = buttons().single { it.text == text }

    /**
     * Lays out [root] and every container under it. `validate()` is not usable here: the box has no peer
     * offscreen, so the platform's validation walk stops before it reaches the action row.
     */
    private fun layOutDeeply(root: Container) {
        root.doLayout()
        root.components.filterIsInstance<Container>().forEach(::layOutDeeply)
    }

    private fun find(root: Component, match: (Component) -> Boolean): Component =
        checkNotNull(findOrNull(root, match)) { "no matching component under $root" }

    private fun findOrNull(root: Component, match: (Component) -> Boolean): Component? {
        if (match(root)) return root
        if (root is Container) root.components.forEach { child -> findOrNull(child, match)?.let { return it } }
        return null
    }

    /** A component's border painted onto an offscreen image; returns one horizontal row of pixels. */
    private fun paintedBorderRow(component: JComponent): IntArray {
        val size = component.preferredSize
        val image = BufferedImage(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            component.border.paintBorder(component, g, 0, 0, image.width, image.height)
        } finally {
            g.dispose()
        }
        val y = image.height / 2
        return IntArray(image.width) { image.getRGB(it, y) }
    }
}
