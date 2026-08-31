package com.spartanlabs.graphics.ui

import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.geometry.Square
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger(Viewport::class.java)

/**
 * The full-window [Element] the game world is seen and played through. It is
 * meant to sit at the **back** of every [Scene] (added first): the world is
 * drawn by the Window before any UI, and the viewport is transparent, so the
 * world shows through wherever no panel/label covers it.
 *
 * All the "clicked on the game" behaviour lives here - it is the element that
 * consumes any mouse event no panel or label in front of it took:
 *
 * - **left press**   - hit-tests the actors and selects the one under the
 *   cursor (client-side only; the server has no notion of a selection)
 * - **right press**  - drops a fading marker at the clicked spot (always),
 *   then asks the server to move the selected actor there; the move is a
 *   no-op if nothing is selected
 * - **middle press** - toggles between the menu and game scenes
 *
 * @property position defaults to the whole window (`0, 0, 1, 1`); it only
 * matters if the viewport is given a non-transparent [color] to render,
 * since routing always treats the viewport as the full-window backstop - see
 * [Scene.dispatchMouse]
 */
class Viewport(
    private val game: GameView,
    override val position: Square = screenRect(0.0, 0.0, 1.0, 1.0),
    override val color: Color = Color.TRANSPARENT
) : Element(position, color) {

    /** Index of the actor a left-click selected, or null. Survives scene swaps. */
    var selectedActor: Int? = null
        private set

    override fun onMouseAction(action: MouseAction) {
        if (action.type != MouseActionType.PRESS) return

        when (action.button) {
            LEFT_BUTTON -> selectActorUnder(action)
            RIGHT_BUTTON -> {
                game.markLocation(action.x, action.y)
                moveSelectedActorTo(action)
            }
            MIDDLE_BUTTON -> game.toggleScene()
        }
    }

    private fun selectActorUnder(action: MouseAction) {
        selectedActor = game.pickActor(action.x, action.y)
        selectedActor
            ?.let { log.info("Selected actor {}", it) }
            ?: log.debug("Click at ({}, {}) selected no actor", action.x, action.y)
    }

    private fun moveSelectedActorTo(action: MouseAction) {
        val actor = selectedActor
        if (actor == null) {
            log.debug("Right click ignored - left-click an actor to select it first")
            return
        }
        game.moveActor(actor, action.x, action.y)
    }

    private companion object {
        // GLFW mouse button codes.
        const val LEFT_BUTTON = 0
        const val RIGHT_BUTTON = 1
        const val MIDDLE_BUTTON = 2
    }
}
