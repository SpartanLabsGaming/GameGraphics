package com.spartanlabs.graphics.ui

import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.generaltools.Color
import com.spartanlabs.geometry.Square
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger(Button::class.java)

/**
 * Whether a [Button] is currently held down - by the mouse, by its assigned
 * key, or both. [Button.color] and [Button.state] are derived from this.
 */
enum class ButtonState {
    /** Nothing is pressing the button. */
    IDLE,

    /** The mouse button is held on it, or its assigned key is held. */
    ACTIVE
}

/**
 * The callback a [Button] reports to. One instance is handed to the button at
 * construction; the button calls back on it (never the other way round) as the
 * cursor and mouse interact with it.
 */
interface ButtonListener {

    /**
     * The cursor has just moved onto [button] (fired once per enter, not for
     * every move while it stays over).
     */
    fun onMouseOver(button: Button)

    /**
     * [button] has been activated: either a full left-press-then-release with
     * the cursor still over it, or a press of its assigned key.
     */
    fun onClick(button: Button)
}

/**
 * A clickable [Element] with a [name] and a live [ButtonState].
 *
 * Behaviour:
 * - **Highlight** - while the cursor is over it (see [Element.onHover]) it
 *   paints a lighter tint of its current colour, and calls
 *   [ButtonListener.onMouseOver] once as the cursor enters.
 * - **State** - [state] is [ButtonState.ACTIVE] whenever the left mouse button
 *   is held down on it *or* its assigned [key] is held down, and
 *   [ButtonState.IDLE] otherwise. [color] follows [state] ([activeColor] vs
 *   [idleColor]), then the hover tint is applied on top.
 * - **Click** - a left press on the button followed by a release with the
 *   cursor still over it fires [ButtonListener.onClick]. Pressing the assigned
 *   [key] fires it too, so a bound key is a genuine keyboard shortcut. The
 *   cursor leaving mid-press cancels the press (no click).
 *
 * [name] is drawn over the button (via [TextElement]), centred in its box by
 * default (see [textAlignment]); [position] is screen-relative like every other
 * element.
 *
 * @property key the GLFW key code bound to this button (e.g. `GLFW_KEY_SPACE`),
 * or null for a mouse-only button
 * @property textAlignment where [name] sits within the button; centred unless overridden
 */
class Button(
    val name: String,
    private val listener: ButtonListener,
    override val position: Square = originSquare(),
    val key: Int? = null,
    override val texture: String? = null,
    val idleColor: Color = DEFAULT_IDLE_COLOR,
    val activeColor: Color = DEFAULT_ACTIVE_COLOR,
    val textColor: Color = Color.WHITE,
    val textAlignment: TextAlignment = TextAlignment.CENTER
) : Element(position), TextElement {

    /** True while the left mouse button is held down on this button. */
    var mouseHeld: Boolean = false
        private set

    /** True while this button's assigned [key] is held down. */
    var keyHeld: Boolean = false
        private set

    /** True while the cursor is over this button. */
    var hovered: Boolean = false
        private set

    /** [ButtonState.ACTIVE] while the mouse or the assigned key holds it down. */
    val state: ButtonState
        get() = if (mouseHeld || keyHeld) ButtonState.ACTIVE else ButtonState.IDLE

    override val color: Color
        get() {
            val base = if (state == ButtonState.ACTIVE) activeColor else idleColor
            return if (hovered) base.lightened() else base
        }

    override val displayText: String get() = name
    override val displayTextColor: Color get() = textColor
    override val displayTextAlignment: TextAlignment get() = textAlignment

    override fun onHover(hovering: Boolean) {
        if (hovering && !hovered) {
            log.trace("Cursor entered button '{}'", name)
            listener.onMouseOver(this)
        }
        hovered = hovering
        // Cursor dragged off while pressed: abandon the press, so the release
        // (which this button will not receive) cannot register as a click.
        if (!hovering) mouseHeld = false
    }

    override fun onMouseAction(action: MouseAction) {
        if (action.button != LEFT_BUTTON) return
        when (action.type) {
            MouseActionType.PRESS -> mouseHeld = true
            MouseActionType.RELEASE -> {
                val wasHeld = mouseHeld
                mouseHeld = false
                if (wasHeld && hovered) {
                    log.info("Button '{}' clicked", name)
                    listener.onClick(this)
                }
            }
            MouseActionType.MOVE -> Unit // hover is handled through onHover
        }
    }

    override fun onKeyAction(action: KeyAction) {
        if (action.key != key) return
        when (action.type) {
            KeyActionType.PRESS -> if (!keyHeld) {
                keyHeld = true
                log.info("Button '{}' activated by key {}", name, key)
                listener.onClick(this)
            }
            KeyActionType.RELEASE -> keyHeld = false
        }
    }

    companion object {
        /** GLFW left mouse button code. */
        private const val LEFT_BUTTON = 0

        /** [idleColor] when none is given - a muted blue-grey. */
        val DEFAULT_IDLE_COLOR = Color(70, 80, 100)

        /** [activeColor] when none is given - a green "pressed" tint. */
        val DEFAULT_ACTIVE_COLOR = Color(90, 150, 95)
    }
}
