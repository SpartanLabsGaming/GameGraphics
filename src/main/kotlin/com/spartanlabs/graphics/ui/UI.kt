package com.spartanlabs.graphics.ui

import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import com.spartanlabs.geometry.Square

/** A map of scene names to [Scene]s that a window can hot-swap between. */
typealias Stage = HashMap<String, Scene>

/** Every [Element] presented to the user at the same time, drawn back-to-front. */
typealias Scene = ArrayList<Element>

/**
 * A Red-Green-Blue-Alpha color, each channel `0..255`.
 *
 * The renderer works in `0f..1f` floats, so [normalized] does that conversion
 * once per draw rather than every call site doing it by hand.
 */
data class Color(val red: Int, val green: Int, val blue: Int, val alpha: Int = 255) {

    /** `[r, g, b, a]` with every channel mapped from `0..255` to `0f..1f`. */
    fun normalized(): FloatArray =
        floatArrayOf(red / 255f, green / 255f, blue / 255f, alpha / 255f)

    companion object {
        val WHITE = Color(255, 255, 255)
        val BLACK = Color(0, 0, 0)
        /** Fully transparent - a panel/label with no visible background of its own. */
        val TRANSPARENT = Color(0, 0, 0, 0)
    }
}

/**
 * A single rectangle of UI: a quad with a [color] and an optional [texture].
 *
 * [position] is **relative**: its [Square.location] and [Square.dimensions]
 * are fractions in `0.0..1.0` of this element's coordinate frame - the whole
 * window for a top-level element, or the containing [Panel]'s box for a
 * panel child. `(0, 0)` is that frame's top-left, `(1, 1)` its bottom-right.
 * So a half-width bar pinned to the top of the window is `location (0, 0)`,
 * `dimensions (0.5, 0.1)`; the same values inside a panel give a bar across
 * the top half of *the panel*. [flatten] resolves these against a concrete
 * window size, so elements keep their proportions when the window resizes.
 * Use [screenRect] to build one.
 *
 * When [texture] is null the quad is filled with [color]; when set, the
 * texture is sampled and multiplied by [color], so [color] doubles as a tint
 * (use [Color.WHITE] to draw the texture unchanged).
 *
 * `sealed` so the renderer can exhaustively handle every element kind.
 */
sealed class Element(
    open val position: Square = originSquare(),
    open val color: Color = Color.BLACK,
    open val texture: String? = null
) {
    /**
     * Called when a mouse event lands on this element - i.e. it is the
     * front-most element under the cursor. Every element is opaque to the
     * mouse: being under the cursor consumes the event, so nothing drawn
     * behind this one ever sees it (see [Scene.dispatchMouse]). Override to
     * react to the event; the default does nothing.
     */
    open fun onMouseAction(action: MouseAction) {}
}

/**
 * An [Element] that lays other elements out inside its own box. [flatten]
 * resolves each child's fractional [Element.position] against this element's
 * already-resolved pixel box, and emits the container before its children so
 * they paint on top. [Panel] and [StatBar] are containers.
 */
interface Container {
    /** The elements drawn inside this one, in back-to-front order. */
    val children: List<Element>
}

/**
 * An [Element] that draws a line of [text] in [textColor] on top of its own
 * quad. [textSource] is evaluated every frame, so a label can show live
 * information; the [String] secondary constructor covers the common
 * fixed-text case.
 *
 * Text is a fixed bitmap size (it does *not* scale with the window) and
 * clipped by nothing - keep [position] wide enough for the string.
 */
class Label(
    override val position: Square = originSquare(),
    override val color: Color = Color.TRANSPARENT,
    override val texture: String? = null,
    val textColor: Color = Color.WHITE,
    private val textSource: () -> String
) : Element(position, color, texture) {

    constructor(
        position: Square = originSquare(),
        color: Color = Color.TRANSPARENT,
        texture: String? = null,
        textColor: Color = Color.WHITE,
        text: String = ""
    ) : this(position, color, texture, textColor, { text })

    /** The line of text to draw this frame. */
    val text: String get() = textSource()
}

/**
 * A container that establishes a coordinate frame for its [children]: each
 * child's fractional [Element.position] is resolved against *this panel's
 * box*, not the window. A child at `location (0.5, 0)`, `dimensions
 * (0.5, 1)` fills the panel's right half, wherever the panel is and whatever
 * size it is. Panels may contain panels; the frames nest. The panel's own
 * quad ([color]/[texture]) is drawn behind its children.
 *
 * A zero-size panel therefore gives its children zero size too - a panel
 * used as a container needs real [position] dimensions.
 */
data class Panel(
    override val position: Square = originSquare(),
    override val color: Color = Color.TRANSPARENT,
    override val texture: String? = null,
    override val children: List<Element> = emptyList()
) : Element(position, color, texture), Container

/**
 * A two-layer bar showing a [value] against a [maxValue] - a health bar, a
 * cooldown, a resource meter. It is a [Container] of two [Panel] layers, both
 * anchored to this element's top-left:
 *
 * - a **track**: the full box, filled with [trackColor] (a neutral grey).
 * - a **fill**: in front of the track, filled with [fillColor] (red), the
 *   full box height but only [fraction] of its width. At `value >= maxValue`
 *   it covers the track; at `value <= 0` its width is zero and the renderer
 *   skips it.
 *
 * [value] and [maxValue] are read every frame through the `() -> Double`
 * primary constructor, so a bar built from live suppliers follows the stat as
 * it changes; the `Double` secondary constructor captures fixed numbers once.
 * [position] is screen-relative like every other [Element].
 */
class StatBar(
    override val position: Square = originSquare(),
    private val value: () -> Double,
    private val maxValue: () -> Double,
    val trackColor: Color = DEFAULT_TRACK_COLOR,
    val fillColor: Color = DEFAULT_FILL_COLOR
) : Element(position, Color.TRANSPARENT), Container {

    constructor(
        position: Square = originSquare(),
        value: Double,
        maxValue: Double,
        trackColor: Color = DEFAULT_TRACK_COLOR,
        fillColor: Color = DEFAULT_FILL_COLOR
    ) : this(position, { value }, { maxValue }, trackColor, fillColor)

    /**
     * How full the bar is this frame: [value] / [maxValue] clamped to
     * `0.0..1.0`, and `0.0` when [maxValue] is not positive.
     */
    val fraction: Double
        get() {
            val max = maxValue()
            return if (max <= 0.0) 0.0 else (value() / max).coerceIn(0.0, 1.0)
        }

    override val children: List<Element>
        get() = listOf(
            Panel(position = screenRect(0.0, 0.0, 1.0, 1.0), color = trackColor),
            Panel(position = screenRect(0.0, 0.0, fraction, 1.0), color = fillColor)
        )

    companion object {
        /** The track (background) layer's colour when none is given - a neutral grey. */
        val DEFAULT_TRACK_COLOR = Color(90, 90, 90)

        /** The fill (foreground) layer's colour when none is given - red. */
        val DEFAULT_FILL_COLOR = Color(200, 40, 40)
    }
}

/**
 * An [Element] resolved to an absolute, top-left-anchored rectangle **in
 * window pixels** - the screen fractions of [Element.position] multiplied
 * out against a concrete window size by [flatten].
 */
data class PositionedElement(
    val element: Element,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    /** True if the window-pixel point ([px], [py]) falls within this element's bounds. */
    fun contains(px: Double, py: Double): Boolean =
        px >= x && px < x + width && py >= y && py < y + height
}

/**
 * Flattens this scene into a back-to-front list of elements, each resolved to
 * an absolute rectangle in window **pixels**. A top-level element's
 * fractional [Element.position] is taken against the whole window; a [Panel]
 * child's is taken against that panel's already-resolved box, so frames nest
 * multiplicatively. Each [Container] appears before its children, so drawing
 * the list in order paints children over their container.
 */
fun Scene.flatten(windowWidth: Int, windowHeight: Int): List<PositionedElement> {
    val out = ArrayList<PositionedElement>()

    // (frameX, frameY, frameW, frameH) is the pixel box this element's
    // fractions are measured against: the window, or an enclosing panel.
    fun visit(element: Element, frameX: Double, frameY: Double, frameW: Double, frameH: Double) {
        val x = frameX + element.position.location.x * frameW
        val y = frameY + element.position.location.y * frameH
        val w = element.position.dimensions.width * frameW
        val h = element.position.dimensions.height * frameH
        out += PositionedElement(element, x, y, w, h)
        if (element is Container) element.children.forEach { visit(it, x, y, w, h) }
    }

    forEach { visit(it, 0.0, 0.0, windowWidth.toDouble(), windowHeight.toDouble()) }
    return out
}

/**
 * Routes a mouse [action] to the UI. Elements are checked front-to-back (the
 * reverse of paint order); the first one whose bounds contain the cursor
 * handles the event via [Element.onMouseAction] and routing stops - every
 * element is opaque, so nothing behind it is considered.
 *
 * A [Viewport], being the full-window backdrop the game is played through,
 * is always the fallback regardless of its declared bounds, so a click that
 * misses every panel/label still reaches the game.
 *
 * @return `true` if some element (or the viewport) handled the event,
 * `false` if it went entirely unhandled (no viewport, and nothing else hit)
 */
fun Scene.dispatchMouse(action: MouseAction, windowWidth: Int, windowHeight: Int): Boolean {
    val positioned = flatten(windowWidth, windowHeight)

    for (index in positioned.indices.reversed()) {
        val candidate = positioned[index]
        if (candidate.element is Viewport) continue // handled as the backstop below
        if (candidate.contains(action.x, action.y)) {
            candidate.element.onMouseAction(action)
            return true
        }
    }

    val viewport = firstOrNull { it is Viewport } as? Viewport ?: return false
    viewport.onMouseAction(action)
    return true
}

/**
 * Builds a screen-relative [Element] rectangle: [x]/[y] is the top-left
 * corner and [width]/[height] the size, all as fractions of the window in
 * `0.0..1.0` (`(0, 0, 1, 1)` covers the whole window). See [Element.position].
 */
fun screenRect(x: Double, y: Double, width: Double, height: Double): Square =
    Square(Point(x, y), Dimensions(width, height))

private fun originSquare() = Square(Point(0.0, 0.0), Dimensions(0.0, 0.0))
