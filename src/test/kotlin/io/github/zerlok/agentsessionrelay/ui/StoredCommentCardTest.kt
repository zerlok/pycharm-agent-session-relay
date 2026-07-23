package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Covers the *shape* of the read-only card (review-batch "Render stored comments as an inline card"):
 * the message layout — an always-present header row above the body, inside an accent frame — and the
 * two invariants that shape exists to protect: the card's fill and frame are not the code's, and
 * revealing the hover actions cannot change the card's height (which would reflow the code below the
 * block inlay).
 *
 * Everything here is measured off a real card built over the live editor fixture: the panel tree, its
 * border insets, the painted border pixels and the laid-out child bounds. No mocking and no new seam —
 * [StoredCommentCard.build]'s returned component is the only entry point, exactly as
 * [EditorReviewOverlay] uses it. What a headless test cannot judge (whether the colors read as
 * "elevated" in a given theme) is left to design.md's Open Questions.
 */
class StoredCommentCardTest : BasePlatformTestCase() {

    private lateinit var editorEx: EditorEx
    private val hovers = mutableListOf<Boolean>()

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("a.py", "line0\nline1\nline2\n")
        editorEx = myFixture.editor as EditorEx
        hovers.clear()
    }

    // -- Visual identity: the card is a control over code, not more code --

    /**
     * The card is filled with the platform UI-surface background rather than the editor's own text
     * background — the "elevation" half of the distinguishable-card scenario. Asserted against both
     * sides so a regression to `editor.colorsScheme.defaultBackground` fails here.
     */
    fun `test the card is filled with the panel background, not the editor's`() {
        val card = buildCard()

        assertTrue("card must be opaque to show its own fill", card.isOpaque)
        assertEquals(UIUtil.getPanelBackground(), card.background)
        assertFalse(
            "card fill must differ from the editor's text background",
            card.background == editorEx.colorsScheme.defaultBackground,
        )
    }

    /**
     * The accent bar is carried by the card's *border*, which is what puts it in `insets` — the single
     * place both `getPreferredSize` and `doLayout` read a horizontal offset. So it shows up as a leading
     * inset wider than the trailing one, and as a painted run of the shared stored-comment accent inside
     * that leading inset (and nowhere near the trailing edge). An extra child component carrying the bar
     * would leave the insets symmetric and fail the first assertion.
     */
    fun `test the card carries a leading accent line in the shared stored-comment accent`() {
        val card = buildCard()
        val insets = card.insets
        val accentWidth = insets.left - insets.right

        assertTrue("leading inset must exceed the trailing one by the accent width", accentWidth > 0)

        val row = paintedBorderRow(card)
        val accent = RangeHighlight.STORED_COMMENT_ACCENT.rgb
        val accentPixels = row.indices.filter { row[it] == accent }

        assertEquals("accent must be exactly the border's extra leading width", accentWidth, accentPixels.size)
        assertTrue("accent must be a contiguous run", accentPixels.last() - accentPixels.first() == accentWidth - 1)
        assertTrue("accent must sit inside the leading inset", accentPixels.last() < insets.left)
    }

    // -- The always-present header row --

    /** At rest the header exists above the body, names the author, and shows no action icons. */
    fun `test the header row is present at rest with the author label and hidden actions`() {
        val card = buildCard()
        val header = headerOf(card)

        assertEquals("You", (findComponent(header, JBLabel::class.java) ?: error("no author label")).text)
        assertEquals(2, actionsOf(card).size)
        assertTrue("actions are hidden at rest", actionsOf(card).none { it.isVisible })
        assertTrue("header must paint the card's own fill", header.isOpaque)
        assertEquals(card.background, header.background)
    }

    /** Hovering reveals both actions, and they are revealed *inside* the header — not floating over it. */
    fun `test hovering reveals the actions inside the header row`() {
        val card = buildCard()
        val header = headerOf(card)

        enter(card)

        assertTrue("actions are revealed on hover", actionsOf(card).all { it.isVisible })
        assertTrue("actions live in the header row", actionsOf(card).all { it.parent === header })
        // The card also reports the hover to the overlay, which owns the range highlight (design D4).
        assertEquals(listOf(true), hovers)
    }

    // The matching hide-on-exit is NOT covered here: the exit branch is gated on
    // `card.getMousePosition(true)` (the "pointer truly left the card *and* its descendants" test), and
    // `Component.getMousePosition` throws `HeadlessException` under the headless test runtime. It is
    // exercised by EditorReviewOverlayTest's `onCardHover(id, false)` seam on the overlay side, and by
    // tasks.md 6.3 in a running IDE on the card side.

    /**
     * The invariant the reserved header exists for: the card's reported height is the same at rest and
     * with the actions revealed, so a hover never reflows the code under the block inlay. Measured
     * through the real reveal path, and additionally after a layout pass so a stale cached size cannot
     * mask a difference.
     */
    fun `test the card's height is identical at rest and with the actions revealed`() {
        val card = buildCard("a body long enough to need a second visual line in the card at base width")
        val restingHeight = card.preferredSize.height
        card.setSize(card.preferredSize)
        card.doLayout()
        val restingHeaderHeight = headerOf(card).height

        enter(card)
        card.setSize(card.preferredSize)
        card.doLayout()

        assertEquals(restingHeight, card.preferredSize.height)
        assertEquals(restingHeaderHeight, headerOf(card).height)
    }

    /**
     * Layout: header first, body below it, both at the SAME content width (the one value derived from
     * the base width and the insets). A second horizontal offset for the accent — the bug the
     * border-carried bar rules out — would show as a body/header width mismatch.
     */
    fun `test the header sits above the body at one content width`() {
        val card = buildCard()
        val header = headerOf(card)
        val body = bodyOf(card)
        val insets = card.insets

        card.setSize(card.preferredSize)
        card.doLayout()

        assertEquals(insets.left, header.x)
        assertEquals(insets.top, header.y)
        assertEquals(insets.left, body.x)
        assertEquals("header and body share one content width", header.width, body.width)
        assertEquals(InlineWidth.baseWidthPx(editorEx) - insets.left - insets.right, header.width)
        assertTrue("body starts below the header", body.y >= header.y + header.height)
        assertTrue("body fits inside the card's bottom inset", body.y + body.height <= card.height - insets.bottom)
    }

    /**
     * The header positions its children whether or not they are visible, so a reveal is a repaint rather
     * than a re-layout — that is what makes the row's height independent of hover state. Asserted at
     * rest, while both buttons are still invisible.
     */
    fun `test the header places the hidden actions at the trailing edge`() {
        val card = buildCard()
        val header = headerOf(card)

        card.setSize(card.preferredSize)
        card.doLayout()
        // The card's doLayout only sizes the header; the row positions its own children (offscreen there
        // is no peer, so `validate()` would not descend into it).
        header.doLayout()

        val (edit, delete) = actionsOf(card)
        assertFalse(edit.isVisible)
        assertTrue("hidden actions still get bounds", edit.width > 0 && delete.width > 0)
        assertTrue("edit precedes delete", edit.x + edit.width <= delete.x)
        assertTrue("actions stay inside the row", delete.x + delete.width <= header.width)
        assertTrue("actions stay inside the row's height", delete.y >= 0 && delete.y + delete.height <= header.height)
    }

    // -- Width (comment-box-sizing invariants this change must not regress) --

    /** The card still opens pinned to the shared base width and is still capped at the right margin. */
    fun `test the card opens at the shared base width under the right-margin cap`() {
        val card = buildCard()
        val base = InlineWidth.baseWidthPx(editorEx)

        assertEquals(base, card.preferredSize.width)
        assertEquals(base, card.maximumSize.width)
        InlineWidth.rightMarginPx(editorEx)?.let { cap ->
            assertTrue("base width must not exceed the right-margin cap", base <= cap)
        }
    }

    // -- Helpers --

    /** Builds a card over the fixture editor and returns the card panel itself (inside the width cap). */
    private fun buildCard(body: String = "look here"): JPanel {
        val root = StoredCommentCard.build(
            editorEx,
            body,
            onEdit = {},
            onDelete = {},
            onHover = { hovers += it },
        )
        // The returned component is InlineWidth.capWidth's wrapper (or the card itself when no cap
        // applies); the card is the body area's parent either way.
        return bodyOf(root).parent as JPanel
    }

    private fun bodyOf(root: Container): JBTextArea =
        findComponent(root, JBTextArea::class.java) ?: error("no body area")

    private fun headerOf(card: JPanel): JPanel =
        card.components.filterIsInstance<JPanel>().singleOrNull() ?: error("no header row")

    /** Edit first, then Delete — the order they are added to the header. */
    private fun actionsOf(card: JPanel): List<InplaceButton> = headerOf(card).components.filterIsInstance<InplaceButton>()

    private fun <T : JComponent> findComponent(root: Container, type: Class<T>): T? {
        for (child in root.components) {
            if (type.isInstance(child)) return type.cast(child)
            if (child is Container) findComponent(child, type)?.let { return it }
        }
        return null
    }

    /** Fires the enter the real pointer would, straight at the card's own listeners. */
    private fun enter(card: JPanel) {
        val event = MouseEvent(card, MouseEvent.MOUSE_ENTERED, 0L, 0, 1, 1, 0, false)
        card.mouseListeners.forEach { it.mouseEntered(event) }
    }

    /** The card's border painted onto an offscreen image; returns one horizontal row of pixels. */
    private fun paintedBorderRow(card: JPanel): IntArray {
        val size = card.preferredSize
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            card.border.paintBorder(card, g, 0, 0, size.width, size.height)
        } finally {
            g.dispose()
        }
        val y = size.height / 2
        return IntArray(size.width) { image.getRGB(it, y) }
    }
}
