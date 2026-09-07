import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.graphics.ui.ClickState
import com.spartanlabs.graphics.ui.GameView
import com.spartanlabs.graphics.ui.KeyAction
import com.spartanlabs.graphics.ui.KeyActionType
import com.spartanlabs.graphics.ui.Viewport
import com.spartanlabs.graphics.ui.screenRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Records every call so a test can assert what the [Viewport] asked of the game. */
class FakeGameView(
    /** The id [pickActor] returns - the actor "under the cursor". Reassign between clicks. */
    var pickResult: Long? = null,
    /** What [attack] returns - true simulates the cursor being over an attackable target. */
    var attackResult: Boolean = false,
    /** What [attackNearestEnemy] returns - true simulates an enemy existing somewhere. */
    var nearestEnemyFound: Boolean = false
) : GameView {
    var pickedAt: Pair<Double, Double>? = null
    var moved: Triple<Long, Double, Double>? = null
    var attacked: Triple<Long, Double, Double>? = null
    var followed: Pair<Long, Long>? = null
    var stopped: Long? = null
    var attackNearestFor: Long? = null
    var markedAt: Pair<Double, Double>? = null
    var toggleCount = 0

    override fun pickActor(xPx: Double, yPx: Double): Long? {
        pickedAt = xPx to yPx
        return pickResult
    }

    override fun moveActor(entityId: Long, xPx: Double, yPx: Double) {
        moved = Triple(entityId, xPx, yPx)
    }

    override fun attack(attackerEntityId: Long, xPx: Double, yPx: Double): Boolean {
        attacked = Triple(attackerEntityId, xPx, yPx)
        return attackResult
    }

    override fun follow(followerEntityId: Long, targetEntityId: Long) {
        followed = followerEntityId to targetEntityId
    }

    override fun stop(entityId: Long) {
        stopped = entityId
    }

    override fun attackNearestEnemy(attackerEntityId: Long): Boolean {
        attackNearestFor = attackerEntityId
        return nearestEnemyFound
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

// GLFW key codes (ASCII for letters), matching Viewport's private constants.
private const val KEY_M = 77
private const val KEY_A = 65
private const val KEY_S = 83

private fun keyPress(key: Int) = KeyAction(KeyActionType.PRESS, key)

class ViewportTest {

    @Test
    fun `left press hit-tests actors and stores the selection`() {
        val game = FakeGameView(pickResult = 3L)
        val view = newViewport(game)

        view.onMouseAction(press(button = 0, x = 42.0, y = 99.0))

        assertEquals(42.0 to 99.0, game.pickedAt)
        assertEquals(3L, view.selectedEntityId)
    }

    @Test
    fun `left press on empty space leaves nothing selected`() {
        val view = newViewport(FakeGameView(pickResult = null))

        view.onMouseAction(press(button = 0))

        assertNull(view.selectedEntityId)
    }

    @Test
    fun `right press moves the selected actor to the clicked pixel`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7

        view.onMouseAction(press(button = 1, x = 800.0, y = 450.0))

        assertEquals(Triple(7L, 800.0, 450.0), game.moved)
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
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7

        view.onMouseAction(press(button = 1, x = 800.0, y = 450.0))

        assertEquals(800.0 to 450.0, game.markedAt)
        assertEquals(Triple(7L, 800.0, 450.0), game.moved)
    }

    @Test
    fun `right press over an attackable target attacks it and does not move or mark`() {
        val game = FakeGameView(pickResult = 7L, attackResult = true)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7

        view.onMouseAction(press(button = 1, x = 300.0, y = 120.0))

        assertEquals(Triple(7L, 300.0, 120.0), game.attacked)
        assertNull(game.moved)
        assertNull(game.markedAt)
    }

    @Test
    fun `right press falls back to marker and move when the target is not attackable`() {
        val game = FakeGameView(pickResult = 7L, attackResult = false)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7

        view.onMouseAction(press(button = 1, x = 300.0, y = 120.0))

        assertEquals(Triple(7L, 300.0, 120.0), game.attacked)
        assertEquals(300.0 to 120.0, game.markedAt)
        assertEquals(Triple(7L, 300.0, 120.0), game.moved)
    }

    @Test
    fun `right press with nothing selected never attempts an attack`() {
        val game = FakeGameView(pickResult = null, attackResult = true)
        val view = newViewport(game)

        view.onMouseAction(press(button = 1, x = 120.0, y = 240.0))

        assertNull(game.attacked)
        assertEquals(120.0 to 240.0, game.markedAt)
        assertNull(game.moved)
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
        val game = FakeGameView(pickResult = 1L)
        val view = newViewport(game)

        view.onMouseAction(MouseAction(MouseActionType.MOVE, -1, 3.0, 4.0))
        view.onMouseAction(MouseAction(MouseActionType.RELEASE, 0, 3.0, 4.0))

        assertNull(game.pickedAt)
        assertNull(game.moved)
        assertEquals(0, game.toggleCount)
    }

    // -----------------------------------------------------------------
    // Click states: arming from the keyboard
    // -----------------------------------------------------------------

    @Test
    fun `M arms MOVE and A arms ATTACK`() {
        val view = newViewport(FakeGameView())

        view.onKeyAction(keyPress(KEY_M))
        assertEquals(ClickState.MOVE, view.clickState)

        view.onKeyAction(keyPress(KEY_A))
        assertEquals(ClickState.ATTACK, view.clickState)
    }

    @Test
    fun `pressing the armed key again disarms back to DEFAULT`() {
        val view = newViewport(FakeGameView())

        view.onKeyAction(keyPress(KEY_M))
        view.onKeyAction(keyPress(KEY_M))

        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `a key release never changes the click state`() {
        val view = newViewport(FakeGameView())

        view.onKeyAction(KeyAction(KeyActionType.RELEASE, KEY_M))

        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `an unrelated key leaves the armed state untouched`() {
        val view = newViewport(FakeGameView())
        view.onKeyAction(keyPress(KEY_M))

        view.onKeyAction(keyPress(70)) // GLFW_KEY_F

        assertEquals(ClickState.MOVE, view.clickState)
    }

    // -----------------------------------------------------------------
    // S - keyless stop
    // -----------------------------------------------------------------

    @Test
    fun `S issues a stop order for the selection`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7

        view.onKeyAction(keyPress(KEY_S))

        assertEquals(7L, game.stopped)
    }

    @Test
    fun `S with nothing selected issues nothing`() {
        val game = FakeGameView(pickResult = null)
        val view = newViewport(game)

        view.onKeyAction(keyPress(KEY_S))

        assertNull(game.stopped)
    }

    @Test
    fun `S is a keyless action - it does not touch the click state`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_M)) // arm MOVE

        view.onKeyAction(keyPress(KEY_S))

        assertEquals(7L, game.stopped)
        assertEquals(ClickState.MOVE, view.clickState)
    }

    @Test
    fun `a key release of S does nothing`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7

        view.onKeyAction(KeyAction(KeyActionType.RELEASE, KEY_S))

        assertNull(game.stopped)
    }

    // -----------------------------------------------------------------
    // MOVE state
    // -----------------------------------------------------------------

    @Test
    fun `in MOVE, left click on empty ground moves the selection, marks, and disarms`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        game.pickResult = null // nothing under the next click
        view.onKeyAction(keyPress(KEY_M))

        view.onMouseAction(press(button = 0, x = 100.0, y = 200.0))

        assertEquals(Triple(7L, 100.0, 200.0), game.moved)
        assertEquals(100.0 to 200.0, game.markedAt)
        assertNull(game.followed)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `in MOVE, left click on another actor issues a follow order and disarms`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        game.pickResult = 9L // a different actor under the next click
        view.onKeyAction(keyPress(KEY_M))

        view.onMouseAction(press(button = 0, x = 100.0, y = 200.0))

        assertEquals(7L to 9L, game.followed)
        assertNull(game.moved)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `in MOVE, left click on the selected unit itself moves rather than follows`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_M)) // unit 7 is still what pickActor returns

        view.onMouseAction(press(button = 0, x = 100.0, y = 200.0))

        assertNull(game.followed)
        assertEquals(Triple(7L, 100.0, 200.0), game.moved)
    }

    @Test
    fun `in MOVE, right click cancels the mode without ordering anything`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_M))

        view.onMouseAction(press(button = 1, x = 100.0, y = 200.0))

        assertNull(game.moved)
        assertNull(game.followed)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `a left click in an armed state does not change the selection`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        game.pickResult = 9L
        view.onKeyAction(keyPress(KEY_M))

        view.onMouseAction(press(button = 0, x = 100.0, y = 200.0))

        assertEquals(7L, view.selectedEntityId)
    }

    @Test
    fun `in MOVE with nothing selected, no order is issued`() {
        val game = FakeGameView(pickResult = null)
        val view = newViewport(game)
        view.onKeyAction(keyPress(KEY_M))

        view.onMouseAction(press(button = 0))

        assertNull(game.moved)
        assertNull(game.followed)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `in an armed state, middle click still toggles the scene and stays armed`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_M))

        view.onMouseAction(press(button = 2))

        assertEquals(1, game.toggleCount)
        assertEquals(ClickState.MOVE, view.clickState)
    }

    // -----------------------------------------------------------------
    // ATTACK state
    // -----------------------------------------------------------------

    @Test
    fun `in ATTACK, left click on a valid target attacks it and disarms`() {
        val game = FakeGameView(pickResult = 7L, attackResult = true)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_A))

        view.onMouseAction(press(button = 0, x = 300.0, y = 120.0))

        assertEquals(Triple(7L, 300.0, 120.0), game.attacked)
        assertNull(game.attackNearestFor)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `in ATTACK, left click with no target under the cursor attacks the nearest enemy`() {
        val game = FakeGameView(pickResult = 7L, attackResult = false, nearestEnemyFound = true)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_A))

        view.onMouseAction(press(button = 0, x = 300.0, y = 120.0))

        assertEquals(Triple(7L, 300.0, 120.0), game.attacked) // the pixel was tried first
        assertEquals(7L, game.attackNearestFor)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `in ATTACK, right click cancels the mode without ordering anything`() {
        val game = FakeGameView(pickResult = 7L)
        val view = newViewport(game)
        view.onMouseAction(press(button = 0, x = 5.0, y = 5.0)) // select unit 7
        view.onKeyAction(keyPress(KEY_A))

        view.onMouseAction(press(button = 1, x = 300.0, y = 120.0))

        assertNull(game.attacked)
        assertNull(game.attackNearestFor)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }

    @Test
    fun `in ATTACK with nothing selected, no order is issued`() {
        val game = FakeGameView(pickResult = null)
        val view = newViewport(game)
        view.onKeyAction(keyPress(KEY_A))

        view.onMouseAction(press(button = 0))

        assertNull(game.attacked)
        assertNull(game.attackNearestFor)
        assertEquals(ClickState.DEFAULT, view.clickState)
    }
}
