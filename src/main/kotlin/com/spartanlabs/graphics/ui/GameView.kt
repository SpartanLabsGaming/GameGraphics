package com.spartanlabs.graphics.ui

/**
 * The bridge a [Viewport] uses to reach the running game. Everything is in
 * window pixels (origin top-left) so the [Viewport] itself needs no
 * knowledge of the camera transform, the world coordinate space, or the
 * network protocol - the implementation (wired up in Main from the Window
 * and the NetworkClient) handles all of that.
 *
 * Units are addressed by their stable entity id (GameTools'
 * `DrawableSnapshot.id`), not by position in the server's state list: the
 * list reorders as units die and spawn, so an index captured on one click
 * points at a different unit a frame later. The `COMMAND` wire carries that
 * id directly (GameTools 5.0.0), so the implementation just passes it through.
 */
interface GameView {

    /**
     * @return the stable entity id of the top-most actor currently drawn under
     * the given window pixel, or null if the pixel is over empty space (or over
     * an actor with no stable id - see [com.spartanlabs.graphics.Window.pick])
     */
    fun pickActor(xPx: Double, yPx: Double): Long?

    /**
     * Asks the server to move the unit with entity id [entityId] to the world
     * position currently rendered at the given window pixel. A no-op (logged)
     * if that unit is no longer in the world state.
     */
    fun moveActor(entityId: Long, xPx: Double, yPx: Double)

    /**
     * Attempts to have the unit with entity id [attackerEntityId] attack
     * whatever actor is drawn under the given window pixel.
     *
     * @return true if the pixel was over an attackable actor (an `Alive` the
     * player does not own, and not the attacker itself) and an attack request
     * was sent to the server; false if it was over empty space, terrain, the
     * attacker, or one of the player's own units - in which case nothing is
     * sent and the caller should fall back to a move order.
     */
    fun attack(attackerEntityId: Long, xPx: Double, yPx: Double): Boolean

    /**
     * Drops a short-lived, fading visual marker at the world position under
     * the given window pixel - the client-side "you right-clicked here" cue.
     * Purely cosmetic; the server is never told.
     */
    fun markLocation(xPx: Double, yPx: Double)

    /** Hot-swaps between the menu and game scenes. */
    fun toggleScene()
}
