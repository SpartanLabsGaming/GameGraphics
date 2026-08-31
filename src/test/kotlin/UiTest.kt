import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import com.spartanlabs.geometry.Square
import com.spartanlabs.graphics.ui.Color
import com.spartanlabs.graphics.ui.Label
import com.spartanlabs.graphics.ui.Panel
import com.spartanlabs.graphics.ui.Scene
import com.spartanlabs.graphics.ui.Viewport
import com.spartanlabs.graphics.ui.dispatchMouse
import com.spartanlabs.graphics.ui.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure parts of the UI model: [Color] channel
 * normalization and [Scene.flatten]'s panel-relative position resolution.
 * The GL rendering in [com.spartanlabs.graphics.UiRenderer] needs a live
 * context and is exercised manually, same as the rest of the window.
 */
class UiTest {

    private fun square(x: Double, y: Double, w: Double = 0.0, h: Double = 0.0) =
        Square(Point(x, y), Dimensions(w, h))

    @Nested
    @DisplayName("Color.normalized()")
    inner class ColorTests {

        @Test
        fun `maps 0-255 channels onto 0-1`() {
            val (r, g, b, a) = Color(255, 0, 51, 204).normalized()

            assertEquals(1.0f, r, EPSILON)
            assertEquals(0.0f, g, EPSILON)
            assertEquals(0.2f, b, EPSILON)
            assertEquals(0.8f, a, EPSILON)
        }

        @Test
        fun `alpha defaults to fully opaque`() {
            assertEquals(1.0f, Color(10, 20, 30).normalized()[3], EPSILON)
        }
    }

    @Nested
    @DisplayName("Label")
    inner class LabelTests {

        @Test
        fun `static text is returned as-is`() {
            assertEquals("hello", Label(text = "hello").text)
        }

        @Test
        fun `dynamic text is re-evaluated on every read`() {
            var n = 0
            val label = Label(position = square(0.0, 0.0)) { "count $n" }

            assertEquals("count 0", label.text)
            n = 42
            assertEquals("count 42", label.text)
        }
    }

    @Nested
    @DisplayName("Scene.flatten()")
    inner class FlattenTests {

        @Test
        fun `screen fractions are multiplied out to window pixels`() {
            val scene = Scene().apply {
                add(Label(position = square(0.1, 0.25, 0.5, 0.5), text = "hi"))
            }

            val flat = scene.flatten(W, H)

            assertEquals(1, flat.size)
            assertEquals(80.0, flat[0].x, EPSILON_D)   // 0.1 * 800
            assertEquals(150.0, flat[0].y, EPSILON_D)  // 0.25 * 600
            assertEquals(400.0, flat[0].width, EPSILON_D)
            assertEquals(300.0, flat[0].height, EPSILON_D)
        }

        @Test
        fun `a panel child's fractions are resolved against the panel's box`() {
            val scene = Scene().apply {
                add(
                    // panel px (80, 60, 400, 300)
                    Panel(
                        position = square(0.1, 0.1, 0.5, 0.5),
                        children = listOf(Label(position = square(0.05, 0.05, 0.25, 0.5), text = "child"))
                    )
                )
            }

            val flat = scene.flatten(W, H)

            // panel, then its child: x = 80 + 0.05*400, y = 60 + 0.05*300,
            //                        w = 0.25*400,       h = 0.5*300
            assertEquals(80.0, flat[0].x, EPSILON_D)
            assertEquals(60.0, flat[0].y, EPSILON_D)
            assertEquals(100.0, flat[1].x, EPSILON_D)
            assertEquals(75.0, flat[1].y, EPSILON_D)
            assertEquals(100.0, flat[1].width, EPSILON_D)
            assertEquals(150.0, flat[1].height, EPSILON_D)
        }

        @Test
        fun `nested panel frames compose multiplicatively`() {
            val scene = Scene().apply {
                add(
                    Panel(
                        position = square(0.1, 0.1, 0.5, 0.5), // px (80, 60, 400, 300)
                        children = listOf(
                            Panel(
                                position = square(0.05, 0.05, 0.5, 0.5), // px (100, 75, 200, 150)
                                children = listOf(Label(position = square(0.025, 0.05), text = "deep"))
                            )
                        )
                    )
                )
            }

            val deepest = scene.flatten(W, H).last()

            assertEquals(105.0, deepest.x, EPSILON_D)  // 100 + 0.025 * 200
            assertEquals(82.5, deepest.y, EPSILON_D)   // 75 + 0.05 * 150
        }

        @Test
        fun `a panel is emitted before its children so it paints behind them`() {
            val scene = Scene().apply {
                add(Panel(position = square(0.0, 0.0, 0.3, 0.3), children = listOf(Label(text = "over"))))
            }

            val flat = scene.flatten(W, H)

            assertEquals(Panel::class, flat[0].element::class)
            assertEquals(Label::class, flat[1].element::class)
        }
    }

    @Nested
    @DisplayName("Scene.dispatchMouse()")
    inner class DispatchTests {

        private fun press(x: Double, y: Double, button: Int = 0) =
            MouseAction(MouseActionType.PRESS, button, x, y)

        // A panel covering the top-left quarter: fraction (0,0,0.25,0.25) -> px (0,0)-(200,150).
        private fun topLeftPanel() = Panel(position = square(0.0, 0.0, 0.25, 0.25))

        @Test
        fun `a panel swallows a click within its bounds - the viewport never sees it`() {
            val game = FakeGameView(pickResult = 1)
            val scene = Scene().apply {
                add(newViewport(game))
                add(topLeftPanel())
            }

            val consumed = scene.dispatchMouse(press(x = 50.0, y = 50.0), W, H)

            assertTrue(consumed)
            assertNull(game.pickedAt)
        }

        @Test
        fun `a click that misses every panel falls through to the viewport`() {
            val game = FakeGameView(pickResult = 1)
            val scene = Scene().apply {
                add(newViewport(game))
                add(topLeftPanel())
            }

            scene.dispatchMouse(press(x = 500.0, y = 400.0), W, H)

            assertEquals(500.0 to 400.0, game.pickedAt)
        }

        @Test
        fun `an opaque label swallows a click - the viewport never sees it`() {
            val game = FakeGameView(pickResult = 2)
            val scene = Scene().apply {
                add(newViewport(game))
                add(Label(position = square(0.0, 0.0, 0.25, 0.1), text = "HUD")) // px (0,0)-(200,60)
            }

            val consumed = scene.dispatchMouse(press(x = 20.0, y = 20.0), W, H)

            assertTrue(consumed)
            assertNull(game.pickedAt)
        }

        @Test
        fun `the viewport is the backstop even when the click is outside its declared bounds`() {
            val game = FakeGameView(pickResult = 9)
            // zero-size viewport: routing must still hand it the event
            val scene = Scene().apply { add(Viewport(game, square(0.0, 0.0, 0.0, 0.0))) }

            scene.dispatchMouse(press(x = 640.0, y = 360.0), W, H)

            assertEquals(640.0 to 360.0, game.pickedAt)
        }

        @Test
        fun `with no viewport and nothing hit, the event goes unhandled`() {
            val scene = Scene().apply { add(Panel(position = square(0.0, 0.0, 0.01, 0.01))) }

            assertFalse(scene.dispatchMouse(press(x = 700.0, y = 500.0), W, H))
        }

        @Test
        fun `a click on a panel's child is consumed, so the viewport never sees it`() {
            val game = FakeGameView(pickResult = 1)
            val scene = Scene().apply {
                add(newViewport(game))
                add(
                    // panel px (80,60)-(480,360); child px (120,90)-(240,180)
                    Panel(
                        position = square(0.1, 0.1, 0.5, 0.5),
                        children = listOf(Label(position = square(0.1, 0.1, 0.3, 0.3), text = "x"))
                    )
                )
            }

            scene.dispatchMouse(press(x = 150.0, y = 120.0), W, H) // over the child label

            assertNull(game.pickedAt)
        }
    }

    private companion object {
        const val EPSILON = 0.0001f
        const val EPSILON_D = 0.0001

        // Window size the flatten/dispatch tests resolve fractions against.
        const val W = 800
        const val H = 600
    }
}
