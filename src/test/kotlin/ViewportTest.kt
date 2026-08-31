import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.graphics.ui.GameView
import com.spartanlabs.graphics.ui.Viewport
import com.spartanlabs.graphics.ui.screenRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Records every call so a test can assert what the [Viewport] asked of the game. */
class FakeGameView(private val pickResult: Int? = null) : GameView {
    var pickedAt: Pair<Double, Double>? = null
    var moved: Triple<Int, Double, Double>? = null
    var markedAt: Pair<Double, Double>? = null
    var toggleCount = 0

    override fun pickActor(xPx: Double, yPx: Double): Int? {
        pickedAt = xPx to yPx
        return pickResult
    }

    override fun moveActor(actorIndex: Int, xPx: Double, yPx: Double) {
        moved = Triple(actorIndex, xPx, yPx)
    }

    override fun markLocation(xPx: Double, yPx: Double) {
        markedAt = xPx to yPx
    }

    override fun toggleScene() {
        toggleCount++
    }
}

fun newViewport(game: GameView): Viewport =
    Viewport(game, screenRect(0.0, 0.0, 1.0, 1.0))

private fun press(button: Int, x: Double = 10.0, y: Double = 20.0) =
    MouseAction(MouseActionType.PRESS, button, x, y)

class ViewportTest {

    @Test
    fun `left press hit-tests actors and stores the selection`() {
        val game = FakeGameView(pickResult = 3)
        val view = newViewport(game)

        view.onMouseAction(press(button = 0, x = 42.0, y = 99.0))

        assertEquals(42.0 to 99.0, game.pickedAt)
        assertEquals(3, view.selectedActor)
    }

    @Test
    fun `left press on empty space leaves nothing selected`() {
        val view = newViewport(FakeGameView(pickResult = null))

        view.onMouseAction(press(button = 0))

        assertNull(view.selectedActor)
    }

    @Test
    fun `right press moves the selected actor to the clicked pixel`() {
        val game = FakeGameView(pickResult = 7)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select actor 7

        view.onMouseAction(press(button = 1, x = 800.0, y = 450.0))

        assertEquals(Triple(7, 800.0, 450.0), game.moved)
    }

    @Test
    fun `right press with nothing selected still drops a marker but moves no actor`() {
        val game = FakeGameView(pickResult = null)
        val view = newViewport(game)

        view.onMouseAction(press(button = 1, x = 120.0, y = 240.0))

        assertNull(game.moved)
        assertEquals(120.0 to 240.0, game.markedAt)
    }

    @Test
    fun `right press drops a marker at the clicked pixel`() {
        val game = FakeGameView(pickResult = 7)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select actor 7

        view.onMouseAction(press(button = 1, x = 800.0, y = 450.0))

        assertEquals(800.0 to 450.0, game.markedAt)
        assertEquals(Triple(7, 800.0, 450.0), game.moved)
    }

    @Test
    fun `middle press toggles the scene`() {
        val game = FakeGameView()
        val view = newViewport(game)

        view.onMouseAction(press(button = 2))

        assertEquals(1, game.toggleCount)
    }

    @Test
    fun `moves and releases do nothing`() {
        val game = FakeGameView(pickResult = 1)
        val view = newViewport(game)

        view.onMouseAction(MouseAction(MouseActionType.MOVE, -1, 3.0, 4.0))
        view.onMouseAction(MouseAction(MouseActionType.RELEASE, 0, 3.0, 4.0))

        assertNull(game.pickedAt)
        assertNull(game.moved)
        assertEquals(0, game.toggleCount)
    }
}
