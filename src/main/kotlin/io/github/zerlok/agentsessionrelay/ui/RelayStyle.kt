package io.github.zerlok.agentsessionrelay.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font

/**
 * The single home for every color Relay paints, and for the surface fill its two inline surfaces share
 * (design R4). Nothing else in the plugin declares a color.
 *
 * That rule exists because the surfaces are meant to read as *one* object seen in two states: a stored
 * comment's read-only card ([StoredCommentCard]) and the box that edits it ([CommentDraft]) occupy the
 * same screen position for the same comment — the card is suppressed while its box is open — and the
 * range they annotate is marked by the same accent in the gutter ([RangeHighlight]). When those values
 * lived next to each surface they duplicated: the stored-comment accent and the draft's active edge were
 * literally the same two RGB pairs declared in two files, and the overlay had to reach into the *draft*
 * for the color of a *stored comment's* hover wash.
 *
 * Colors, the surface fill and the shared body font only — no components, no borders, no layout. A font
 * is a shared visual token like a color: it introduces no second place a *width* is derived from. Each
 * surface still composes its own border, because the card's geometry is load-bearing (its insets are the
 * single place the content width is derived from) and a style object that hands out borders would become
 * a second place where that geometry lives.
 */
internal object RelayStyle {

    /**
     * Relay's blue, tuned to be visible as a *line*: the gutter bar over a commented range, the draft's
     * active (hovered/dragged) range edge, and the frame around the authoring box's body field.
     *
     * The dark variant is the lighter of the pair on purpose — a 3px stripe on a dark editor has to
     * out-lighten its background to register. That is also why it cannot double as [ACCENT_FILL].
     */
    val ACCENT = JBColor(Color(0x3B, 0x74, 0xE8), Color(0x6E, 0x9B, 0xF0))

    /**
     * The same hue as [ACCENT] at the luminance a *filled* surface needs: the primary action's fill in
     * the authoring box. Kept separate rather than reusing [ACCENT] because no single luminance serves
     * both roles — [ACCENT]'s dark variant is light enough to read as a hairline on a dark editor, which
     * makes [ACCENT_FILL_TEXT] on it unreadable.
     */
    val ACCENT_FILL = JBColor(Color(0x3B, 0x74, 0xE8), Color(0x35, 0x74, 0xF0))

    /** Label color for a control filled with [ACCENT_FILL] — white in both themes, as the fill is dark in both. */
    val ACCENT_FILL_TEXT = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0xFF, 0xFF, 0xFF))

    /**
     * The pale wash laid *behind text* over a commented line range, à la a pull-request review
     * selection: the draft's live range and the transient stored-comment hover highlight, so the two
     * read as one highlight. Deliberately not [ACCENT] — a color picked to sit behind code is invisible
     * drawn as a line, and vice versa.
     */
    val RANGE_WASH = JBColor(Color(0xDD, 0xE7, 0xFF), Color(0x2A, 0x3A, 0x5A))

    /** Idle range edge in the draft — a slightly stronger blue than [RANGE_WASH], hinting it is grabbable. */
    val EDGE_IDLE = JBColor(Color(0x88, 0xA8, 0xE0), Color(0x3E, 0x54, 0x82))

    /**
     * The fill shared by the stored comment's card and the authoring box: the platform's UI-surface
     * color, which is a different value from `editor.colorsScheme.defaultBackground` in effectively
     * every bundled theme. That distinctness is the point — it is what makes both surfaces read as
     * controls floating over code rather than as more code.
     *
     * A function, not a `val`: the panel background is a live LaF value and changes when the user
     * switches theme, so it must be read at build time rather than captured once at class-init.
     */
    fun surface(): Color = UIUtil.getPanelBackground()

    /**
     * The font the stored comment's card and the authoring box both render their **body** in, so a
     * comment does not change typeface the moment it is opened for editing (design D6). Neither surface
     * may take this from the default its Swing component class inherits: the platform's LaF gives
     * `TextArea.font` a *Monospaced* resource while an [com.intellij.ui.EditorTextField] carries the UI
     * font, so the card and the box disagreed by construction.
     *
     * The UI font is the direction of the parity, following the platform's own review-comment surfaces,
     * which force their comment editor onto the UI font and render the read-only side in UI-font panes.
     *
     * A function, not a `val`, for the same reason as [surface]: it is a live LaF value that changes with
     * the theme and the IDE font-size setting, so it must be read at build time rather than at class-init.
     * Pinned to the plain weight: a comment body is running text, and `Label.font` is bold under some
     * LaFs (the headless test runtime's among them), which would render every stored comment bold.
     */
    fun bodyFont(): Font = JBFont.label().asPlain()
}
