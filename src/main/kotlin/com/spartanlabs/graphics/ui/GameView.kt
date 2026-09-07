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
     * Asks the server to have the unit with entity id [followerEntityId] chase
     * the unit with entity id [targetEntityId] (GameTools' `Follow` command -
     * the follower re-homes on the target's position every tick). A no-op
     * (logged) if the follower is no longer in the world state; the target may
     * be any actor, including one of the player's own.
     */
    fun follow(followerEntityId: Long, targetEntityId: Long)

    /**
     * Asks the server to halt the unit with entity id [entityId] where it is
     * (GameTools' `Stop` command - a movement order, it does not call off an
     * attack). A no-op (logged) if that unit is no longer in the world state.
     */
    fun stop(entityId: Long)

    /**
     * Attacks the nearest attackable enemy anywhere in the current world state -
     * the fallback for a forced attack click (see
     * [com.spartanlabs.graphics.ui.ClickState.ATTACK]) that did not land on a
     * target. "Enemy" is any `Alive` not owned by this player; nearest is by
     * world distance from the attacker.
     *
     * @return true if an enemy was found and an attack request was sent; false
     * if [attackerEntityId] is gone from the world state or there is no enemy
     */
    fun attackNearestEnemy(attackerEntityId: Long): Boolean

    /**
     * Drops a short-lived, fading visual marker at the world position under
     * the given window pixel - the client-side "you right-clicked here" cue.
     * Purely cosmetic; the server is never told.
     */
    fun markLocation(xPx: Double, yPx: Double)

    /** Hot-swaps between the menu and game scenes. */
    fun toggleScene()
}
