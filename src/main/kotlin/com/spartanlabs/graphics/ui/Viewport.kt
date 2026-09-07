package com.spartanlabs.graphics.ui

import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.generaltools.Color
import com.spartanlabs.geometry.Square
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger(Viewport::class.java)

/**
 * How the [Viewport] reads the next click on the game world. The player arms
 * [MOVE] / [ATTACK] from the keyboard; both are **one-shot** - issuing the order
 * (or a right-click, which cancels) drops straight back to [DEFAULT].
 *
 * - [DEFAULT] - unchanged from before there were click states: a left-click
 *   selects the actor under the cursor, a right-click commands the selected
 *   actor (attack an attackable target under the cursor, otherwise move there).
 * - [MOVE] - armed with `M`. The next left-click forces a movement order for
 *   the selected actor: a `Follow` if it lands on another actor, otherwise a
 *   `MoveTo` (with the usual click marker).
 * - [ATTACK] - armed with `A`. The next left-click forces an attack order for
 *   the selected actor: the actor under the cursor if it is an attackable
 *   target, otherwise the nearest attackable enemy anywhere in the world.
 *
 * While [MOVE] or [ATTACK] is armed a left-click issues that order instead of
 * changing the selection - selection is suspended until the viewport is back in
 * [DEFAULT].
 */
enum class ClickState { DEFAULT, MOVE, ATTACK }

/**
 * The full-window [Element] the game world is seen and played through. It is
 * meant to sit at the **back** of every [Scene] (added first): the world is
 * drawn by the Window before any UI, and the viewport is transparent, so the
 * world shows through wherever no panel/label covers it.
 *
 * All the "clicked on the game" behaviour lives here - it is the element that
 * consumes any mouse event no panel or label in front of it took. What a click
 * does depends on the current [clickState]:
 *
 * - **[ClickState.DEFAULT]** - left press selects the actor under the cursor
 *   (client-side only; the server has no notion of a selection); right press,
 *   with an actor selected, attacks an attackable actor under the cursor or
 *   else drops a fading marker and moves the selected actor there; middle press
 *   toggles between the menu and game scenes.
 * - **[ClickState.MOVE] / [ClickState.ATTACK]** - left press issues the forced
 *   order (see [ClickState]) and returns to [ClickState.DEFAULT]; right press
 *   cancels back to [ClickState.DEFAULT] without issuing anything; middle press
 *   still toggles the scene.
 *
 * The `M` and `A` keys arm [ClickState.MOVE] / [ClickState.ATTACK]; pressing the
 * same key again, or the other one, re-arms accordingly. `S` is a keyless action
 * (no click state): it issues a `Stop` for the selection outright (see
 * [onKeyAction]).
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

    /** Stable entity id of the actor a left-click selected, or null. Survives scene swaps. */
    var selectedEntityId: Long? = null
        private set

    /** How the next click is interpreted; armed from the keyboard, reset after each forced order. */
    var clickState: ClickState = ClickState.DEFAULT
        private set

    override fun onMouseAction(action: MouseAction) {
        if (action.type != MouseActionType.PRESS) return

        when (clickState) {
            ClickState.DEFAULT -> handleDefaultClick(action)
            ClickState.MOVE -> handleArmedClick(action, ::issueForcedMove)
            ClickState.ATTACK -> handleArmedClick(action, ::issueForcedAttack)
        }
    }

    /**
     * Keyboard shortcuts on the game world:
     * - `M` / `A` arm [ClickState.MOVE] / [ClickState.ATTACK]; pressing the key
     *   that is already armed disarms it back to [ClickState.DEFAULT].
     * - `S` immediately issues a `Stop` order for the selection - a keyless
     *   action, not a click state: nothing else needs to be clicked, and the
     *   current [clickState] is left untouched.
     *
     * Key releases and every other key are ignored.
     */
    override fun onKeyAction(action: KeyAction) {
        if (action.type != KeyActionType.PRESS) return

        when (action.key) {
            MOVE_KEY -> setClickState(if (clickState == ClickState.MOVE) ClickState.DEFAULT else ClickState.MOVE)
            ATTACK_KEY -> setClickState(if (clickState == ClickState.ATTACK) ClickState.DEFAULT else ClickState.ATTACK)
            STOP_KEY -> stopSelection()
        }
    }

    private fun setClickState(state: ClickState) {
        clickState = state
        log.debug("Click state armed to {}", clickState)
    }

    /** `S`: halt the selected unit where it stands. A no-op (logged) with nothing selected. */
    private fun stopSelection() {
        val unit = selectedEntityId
        if (unit == null) {
            log.debug("Stop ignored - left-click an actor to select it first")
            return
        }
        game.stop(unit)
        log.info("Unit {} ordered to stop", unit)
    }

    // -----------------------------------------------------------------
    // DEFAULT - selection and context-sensitive right-click command
    // -----------------------------------------------------------------

    private fun handleDefaultClick(action: MouseAction) {
        when (action.button) {
            LEFT_BUTTON -> selectActorUnder(action)
            RIGHT_BUTTON -> commandSelectedActor(action)
            MIDDLE_BUTTON -> game.toggleScene()
        }
    }

    private fun selectActorUnder(action: MouseAction) {
        selectedEntityId = game.pickActor(action.x, action.y)
        selectedEntityId
            ?.let { log.info("Selected unit {}", it) }
            ?: log.debug("Click at ({}, {}) selected no actor", action.x, action.y)
    }

    /**
     * Routes a right click for the selected actor: an attack if the cursor is
     * over an attackable target, otherwise a marker-and-move. With nothing
     * selected, only the marker is dropped.
     */
    private fun commandSelectedActor(action: MouseAction) {
        val unit = selectedEntityId
        if (unit != null && game.attack(unit, action.x, action.y)) {
            log.info("Unit {} ordered to attack the target at ({}, {})", unit, action.x, action.y)
            return
        }
        game.markLocation(action.x, action.y)
        moveSelectedActorTo(action)
    }

    private fun moveSelectedActorTo(action: MouseAction) {
        val unit = selectedEntityId
        if (unit == null) {
            log.debug("Right click ignored - left-click an actor to select it first")
            return
        }
        game.moveActor(unit, action.x, action.y)
    }

    // -----------------------------------------------------------------
    // MOVE / ATTACK - one-shot forced orders
    // -----------------------------------------------------------------

    /**
     * In an armed state a left-click runs [issueOrder] and disarms, a
     * right-click just disarms (cancel), and a middle-click still toggles the
     * scene without disarming.
     */
    private fun handleArmedClick(action: MouseAction, issueOrder: (MouseAction) -> Unit) {
        when (action.button) {
            LEFT_BUTTON -> {
                issueOrder(action)
                clickState = ClickState.DEFAULT
            }
            RIGHT_BUTTON -> {
                log.debug("{} order cancelled", clickState)
                clickState = ClickState.DEFAULT
            }
            MIDDLE_BUTTON -> game.toggleScene()
        }
    }

    /**
     * Forced move: a `Follow` if the click lands on another actor, otherwise a
     * marker-and-`MoveTo`. A no-op (logged) with nothing selected.
     */
    private fun issueForcedMove(action: MouseAction) {
        val unit = selectedEntityId
        if (unit == null) {
            log.debug("MOVE click ignored - left-click an actor to select it first")
            return
        }
        val actorUnder = game.pickActor(action.x, action.y)
        if (actorUnder != null && actorUnder != unit) {
            game.follow(unit, actorUnder)
            log.info("Unit {} ordered to follow {}", unit, actorUnder)
        } else {
            game.markLocation(action.x, action.y)
            game.moveActor(unit, action.x, action.y)
        }
    }

    /**
     * Forced attack: the actor under the cursor if it is an attackable target,
     * otherwise the nearest attackable enemy anywhere. A no-op (logged) with
     * nothing selected or no enemy in the world.
     */
    private fun issueForcedAttack(action: MouseAction) {
        val unit = selectedEntityId
        if (unit == null) {
            log.debug("ATTACK click ignored - left-click an actor to select it first")
            return
        }
        if (game.attack(unit, action.x, action.y)) {
            log.info("Unit {} ordered to attack the target under the cursor", unit)
            return
        }
        if (!game.attackNearestEnemy(unit)) {
            log.debug("ATTACK click - no attackable enemy anywhere for unit {}", unit)
        }
    }

    private companion object {
        // GLFW mouse button codes.
        const val LEFT_BUTTON = 0
        const val RIGHT_BUTTON = 1
        const val MIDDLE_BUTTON = 2

        // GLFW key codes (ASCII for letter keys) for the game-world shortcuts.
        const val MOVE_KEY = 77 // GLFW_KEY_M
        const val ATTACK_KEY = 65 // GLFW_KEY_A
        const val STOP_KEY = 83 // GLFW_KEY_S
    }
}
