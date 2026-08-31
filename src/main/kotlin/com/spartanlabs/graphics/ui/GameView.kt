package com.spartanlabs.graphics.ui

/**
 * The bridge a [Viewport] uses to reach the running game. Everything is in
 * window pixels (origin top-left) so the [Viewport] itself needs no
 * knowledge of the camera transform, the world coordinate space, or the
 * network protocol - the implementation (wired up in Main from the Window
 * and the NetworkClient) handles all of that.
 */
interface GameView {

    /**
     * @return the index of the top-most actor currently drawn under the given
     * window pixel, or null if the pixel is over empty space
     */
    fun pickActor(xPx: Double, yPx: Double): Int?

    /**
     * Asks the server to move actor [actorIndex] to the world position that
     * is currently rendered at the given window pixel.
     */
    fun moveActor(actorIndex: Int, xPx: Double, yPx: Double)

    /**
     * Drops a short-lived, fading visual marker at the world position under
     * the given window pixel - the client-side "you right-clicked here" cue.
     * Purely cosmetic; the server is never told.
     */
    fun markLocation(xPx: Double, yPx: Double)

    /** Hot-swaps between the menu and game scenes. */
    fun toggleScene()
}
