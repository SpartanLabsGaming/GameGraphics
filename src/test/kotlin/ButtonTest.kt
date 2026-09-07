import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import com.spartanlabs.geometry.Square
import com.spartanlabs.graphics.ui.Button
import com.spartanlabs.graphics.ui.ButtonListener
import com.spartanlabs.graphics.ui.ButtonState
import com.spartanlabs.graphics.ui.KeyAction
import com.spartanlabs.graphics.ui.KeyActionType
import com.spartanlabs.graphics.ui.TextAlignment
import com.spartanlabs.graphics.ui.lightened
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** A [ButtonListener] that records every callback for assertions. */
class RecordingButtonListener : ButtonListener {
    var overCount = 0
    val clicks = mutableListOf<Button>()

    override fun onMouseOver(button: Button) {
        overCount++
    }

    override fun onClick(button: Button) {
        clicks += button
    }
}

private const val KEY_B = 66

private fun button(listener: ButtonListener, key: Int? = null) =
    Button(
        name = "Go",
        listener = listener,
        position = Square(Point(0.0, 0.0), Dimensions(1.0, 1.0)),
        key = key
    )

private fun leftPress() = MouseAction(MouseActionType.PRESS, 0, 0.0, 0.0)
private fun leftRelease() = MouseAction(MouseActionType.RELEASE, 0, 0.0, 0.0)

/**
 * Unit tests for [Button]'s pure interaction logic: hover highlighting, the
 * mouse/key-driven [ButtonState], and when [ButtonListener.onClick] fires.
 * The GL drawing of the button is exercised manually with the rest of the
 * window.
 */
class ButtonTest {

    @Nested
    @DisplayName("text")
    inner class TextTests {

        @Test
        fun `a button's caption is its name, centred by default`() {
            val button = button(RecordingButtonListener())

            assertEquals("Go", button.displayText)
            assertEquals(TextAlignment.CENTER, button.displayTextAlignment)
        }

        @Test
        fun `the caption alignment can be overridden`() {
            val button = Button(
                name = "Go",
                listener = RecordingButtonListener(),
                position = Square(Point(0.0, 0.0), Dimensions(1.0, 1.0)),
                textAlignment = TextAlignment.TOP_LEFT
            )

            assertEquals(TextAlignment.TOP_LEFT, button.displayTextAlignment)
        }
    }

    @Nested
    @DisplayName("hover")
    inner class HoverTests {

        @Test
        fun `a button starts un-hovered and idle`() {
            val button = button(RecordingButtonListener())

            assertFalse(button.hovered)
            assertEquals(ButtonState.IDLE, button.state)
        }

        @Test
        fun `the cursor entering fires onMouseOver exactly once until it leaves`() {
            val listener = RecordingButtonListener()
            val button = button(listener)

            button.onHover(true)
            button.onHover(true)

            assertTrue(button.hovered)
            assertEquals(1, listener.overCount)

            button.onHover(false)
            button.onHover(true)

            assertEquals(2, listener.overCount)
        }

        @Test
        fun `hovering paints a lightened tint of the current state colour`() {
            val button = button(RecordingButtonListener())

            assertEquals(button.idleColor, button.color)

            button.onHover(true)

            assertEquals(button.idleColor.lightened(), button.color)
        }
    }

    @Nested
    @DisplayName("state from the mouse")
    inner class MouseStateTests {

        @Test
        fun `holding the left button down over it makes it ACTIVE, releasing makes it IDLE`() {
            val button = button(RecordingButtonListener())
            button.onHover(true)

            button.onMouseAction(leftPress())
            assertEquals(ButtonState.ACTIVE, button.state)
            assertEquals(button.activeColor.lightened(), button.color)

            button.onMouseAction(leftRelease())
            assertEquals(ButtonState.IDLE, button.state)
        }

        @Test
        fun `a non-left mouse button is ignored`() {
            val button = button(RecordingButtonListener())
            button.onHover(true)

            button.onMouseAction(MouseAction(MouseActionType.PRESS, 1, 0.0, 0.0))

            assertEquals(ButtonState.IDLE, button.state)
        }
    }

    @Nested
    @DisplayName("click")
    inner class ClickTests {

        @Test
        fun `a press then release with the cursor still over it is a click`() {
            val listener = RecordingButtonListener()
            val button = button(listener)
            button.onHover(true)

            button.onMouseAction(leftPress())
            button.onMouseAction(leftRelease())

            assertEquals(1, listener.clicks.size)
            assertSame(button, listener.clicks.single())
        }

        @Test
        fun `the cursor leaving mid-press cancels the click`() {
            val listener = RecordingButtonListener()
            val button = button(listener)
            button.onHover(true)

            button.onMouseAction(leftPress())
            button.onHover(false)        // cursor dragged off
            button.onMouseAction(leftRelease())

            assertTrue(listener.clicks.isEmpty())
            assertEquals(ButtonState.IDLE, button.state)
        }

        @Test
        fun `a release with no preceding press is not a click`() {
            val listener = RecordingButtonListener()
            val button = button(listener)
            button.onHover(true)

            button.onMouseAction(leftRelease())

            assertTrue(listener.clicks.isEmpty())
        }
    }

    @Nested
    @DisplayName("state from the assigned key")
    inner class KeyStateTests {

        @Test
        fun `pressing the assigned key makes it ACTIVE and fires one click`() {
            val listener = RecordingButtonListener()
            val button = button(listener, key = KEY_B)

            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B))

            assertTrue(button.keyHeld)
            assertEquals(ButtonState.ACTIVE, button.state)
            assertEquals(1, listener.clicks.size)
        }

        @Test
        fun `key auto-repeat does not fire another click while the key is held`() {
            val listener = RecordingButtonListener()
            val button = button(listener, key = KEY_B)

            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B))
            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B))

            assertEquals(1, listener.clicks.size)
        }

        @Test
        fun `releasing the assigned key returns it to IDLE`() {
            val button = button(RecordingButtonListener(), key = KEY_B)

            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B))
            button.onKeyAction(KeyAction(KeyActionType.RELEASE, KEY_B))

            assertFalse(button.keyHeld)
            assertEquals(ButtonState.IDLE, button.state)
        }

        @Test
        fun `a different key is ignored`() {
            val listener = RecordingButtonListener()
            val button = button(listener, key = KEY_B)

            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B + 1))

            assertEquals(ButtonState.IDLE, button.state)
            assertTrue(listener.clicks.isEmpty())
        }

        @Test
        fun `a button with no assigned key ignores every key event`() {
            val listener = RecordingButtonListener()
            val button = button(listener, key = null)

            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B))

            assertEquals(ButtonState.IDLE, button.state)
            assertTrue(listener.clicks.isEmpty())
        }

        @Test
        fun `it stays ACTIVE while the key is held even after the mouse releases`() {
            val button = button(RecordingButtonListener(), key = KEY_B)
            button.onHover(true)

            button.onMouseAction(leftPress())
            button.onKeyAction(KeyAction(KeyActionType.PRESS, KEY_B))
            button.onMouseAction(leftRelease())

            assertEquals(ButtonState.ACTIVE, button.state)
        }
    }
}
