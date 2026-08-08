package io.github.zerlok.agentsessionrelay.ui

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Covers the *shape* of the read-only card (review-batch "Render stored comments as an inline card"):
 * the message layout — an always-present header row above the body, inside a plain 1px outline — and the
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
        assertEquals(RelayStyle.surface(), card.background)
        assertEquals("the shared surface must be the platform panel background", UIUtil.getPanelBackground(), card.background)
        assertFalse(
            "card fill must differ from the editor's text background",
            card.background == editorEx.colorsScheme.defaultBackground,
        )
    }

    /**
     * The card carries **no** accent edge (design R1): a commented range wears the accent in exactly one
     * place, the gutter bar over its lines, so a card and its range are tied by one mark instead of two
     * parallel blue lines. Asserted two ways, because either alone is weak: the border's insets are
     * symmetric (an accent line carried by the border — the shipped-then-retracted design — makes the
     * leading inset wider), and no pixel of the accent is painted anywhere along a border row (an accent
     * carried by an extra child, or by a same-width line, leaves the insets symmetric).
     */
    fun `test the card carries no accent edge`() {
        val card = buildCard()
        val insets = card.insets

        assertEquals("leading and trailing insets must match — no accent line", insets.right, insets.left)

        val row = paintedBorderRow(card)
        val accent = RelayStyle.ACCENT.rgb
        assertTrue("no border pixel may be painted in the accent", row.none { it == accent })
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
        assertEquals(InlineWidth.readingMeasurePx() - insets.left - insets.right, header.width)
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

    /**
     * The collision rule (design D5): when the header row is too narrow for both, the icons keep their
     * full width *inside* the row and the author label is the thing that yields. Asserted on bounds,
     * since nothing renders headlessly — and asserted at a row width no real card reaches, because the
     * failure it guards against is exactly the unreachable-action defect at an extreme width.
     */
    fun `test a narrow header keeps both actions in full and truncates the author label`() {
        val card = buildCard()
        val header = headerOf(card)
        val (edit, delete) = actionsOf(card)
        val iconWidths = edit.preferredSize.width + delete.preferredSize.width

        // Narrower than the label wants but still wide enough for the two icons plus their gap.
        header.setSize(iconWidths + JBUI.scale(8), header.preferredSize.height.coerceAtLeast(16))
        header.doLayout()

        assertEquals("the edit icon keeps its full width", edit.preferredSize.width, edit.width)
        assertEquals("the delete icon keeps its full width", delete.preferredSize.width, delete.width)
        assertTrue("both actions stay inside the row", edit.x >= 0 && delete.x + delete.width <= header.width)
        assertTrue("the label yields to the actions", labelOf(header).x + labelOf(header).width <= edit.x)
    }

    // -- Width (comment-box-sizing invariants this change must not regress) --

    /** On a viewport wider than the reading measure, the measure is what the card opens at. */
    fun `test the card opens at the reading measure on a wide viewport`() {
        val row = buildRow()
        val card = cardOf(row)
        row.setSize(InlineWidth.readingMeasurePx() * 3, 200)
        row.doLayout()

        assertEquals(InlineWidth.readingMeasurePx(), card.preferredSize.width)
        assertEquals("the row lays the card out at what it measured", InlineWidth.readingMeasurePx(), card.width)
    }

    /**
     * The deliberate behavior change: the editor's configured right margin is no longer a cap. A guide
     * column far narrower than the reading measure used to shrink the card to it; the card is now sized
     * by the platform's viewport-fitting row and the measure alone (`review-batch` "A narrow right
     * margin does not narrow the card").
     */
    fun `test a narrow right margin does not narrow the card`() {
        editorEx.settings.setRightMargin(1)

        val row = buildRow()
        val card = cardOf(row)
        row.setSize(InlineWidth.readingMeasurePx() * 3, 200)
        row.doLayout()

        assertEquals(InlineWidth.readingMeasurePx(), card.width)
    }

    /**
     * `review-batch` "Card follows the editor when it narrows": the card's width is whatever its row
     * currently allots, not a number frozen when the card was built. So narrowing the viewport narrows
     * the card — and because the body is measured at that same number, it re-wraps and the card grows
     * *taller* instead of having its text clipped.
     *
     * The one-width invariant is asserted in the same pass (design D1): after the narrowing, the header
     * and the body are laid out at one content width, the one `getPreferredSize` measured at.
     */
    fun `test the card follows the row's width, re-wrapping its body rather than clipping it`() {
        // Long enough that halving the card's width must cost it visual lines whatever the metrics are.
        val row = buildRow("a body long enough that it must re-wrap onto more visual lines once the card is narrowed, ".repeat(4))
        val card = cardOf(row)
        row.setSize(InlineWidth.readingMeasurePx() * 3, 400)
        row.doLayout()
        val wide = card.preferredSize

        // What a split or a window resize does: the platform re-lays the row out at a narrower viewport.
        val narrow = InlineWidth.readingMeasurePx() / 2
        row.setSize(narrow, 400)
        row.doLayout()
        // The row sizes the card; the card positions its own children (offscreen there is no peer, so
        // nothing descends into it on its own).
        card.doLayout()

        assertEquals("the card must have followed the row", narrow, card.preferredSize.width)
        assertTrue("the narrowed body must re-wrap and grow the card taller", card.preferredSize.height > wide.height)

        val contentWidth = card.preferredSize.width - card.insets.left - card.insets.right
        assertEquals("the body is laid out at the width it was measured at", contentWidth, bodyOf(card).width)
        assertEquals("...and so is the header", contentWidth, headerOf(card).width)
    }

    // -- Helpers --

    /** Builds a card and returns the card panel itself (inside its [ReadingWidthRow]). */
    private fun buildCard(body: String = "look here"): JPanel = cardOf(buildRow(body))

    /** The row [StoredCommentCard.build] returns — what the inlay is given, and what caps the card. */
    private fun buildRow(body: String = "look here"): ReadingWidthRow =
        StoredCommentCard.build(
            editorEx,
            body,
            onEdit = {},
            onDelete = {},
            onHover = { hovers += it },
        ) as ReadingWidthRow

    private fun cardOf(row: ReadingWidthRow): JPanel = bodyOf(row).parent as JPanel

    private fun bodyOf(root: Container): JBTextArea =
        findComponent(root, JBTextArea::class.java) ?: error("no body area")

    private fun headerOf(card: JPanel): JPanel =
        card.components.filterIsInstance<JPanel>().singleOrNull() ?: error("no header row")

    private fun labelOf(header: JPanel): JBLabel =
        header.components.filterIsInstance<JBLabel>().singleOrNull() ?: error("no author label")

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
