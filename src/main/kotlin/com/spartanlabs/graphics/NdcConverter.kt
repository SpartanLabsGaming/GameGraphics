package com.spartanlabs.networking

/**
 * The current view transform applied when rendering: how far zoomed in/out
 * the view is, and how far the camera has panned from the world origin (in
 * pixels). Bundled together since [NdcConverter] always applies both as a
 * single, consistent transform - keeping them as one object also avoids an
 * ever-growing parameter list on [NdcConverter.offset]/[NdcConverter.halfSize]
 * as more camera behavior (zoom, panning, ...) gets added.
 */
internal data class Camera(
    val zoomFactor: Float = 1.0f,
    val panOffsetXPx: Float = 0.0f,
    val panOffsetYPx: Float = 0.0f
)

/**
 * Pure pixel-space to OpenGL normalized-device-coordinate (NDC) conversions
 * used when rendering actors. Free of any GLFW/OpenGL calls or mutable
 * state, so every function here is a straightforward, fully unit-testable
 * calculation - unlike the rest of [com.spartanlabs.graphics.Window], which requires a live GPU context.
 */
internal object NdcConverter {

    /**
     * Converts a pixel-space position (relative to the window's center,
     * Y increasing downward) into an NDC offset (-1..1, Y increasing upward),
     * applying [camera]'s pan and zoom.
     *
     * Panning subtracts [Camera.panOffsetXPx]/[Camera.panOffsetYPx] from the
     * position before the NDC conversion, so a positive pan shifts every
     * actor's apparent position in the *opposite* direction on screen - the
     * same "camera moves right, world slides left" behavior a scrolling map has.
     *
     * Scaling by [Camera.zoomFactor] here (not just in [halfSize]) is what
     * makes this a true camera-style zoom rather than just resizing each
     * actor in place: the window's center is the NDC origin, so scaling
     * distance from it makes actors spread outward when zooming in and
     * converge inward when zooming out - the same focal point [halfSize]
     * scales actor sizes around.
     *
     * @param xPx horizontal position in pixels
     * @param yPx vertical position in pixels, positive is downward
     * @param windowWidth the window's current width in pixels
     * @param windowHeight the window's current height in pixels
     * @param camera the current pan/zoom state; the default leaves position unchanged
     * @return the (x, y) offset in NDC units
     */
    fun offset(
        xPx: Double,
        yPx: Double,
        windowWidth: Int,
        windowHeight: Int,
        camera: Camera = Camera()
    ): Pair<Float, Float> {
        val worldX = xPx - camera.panOffsetXPx
        val worldY = yPx - camera.panOffsetYPx
        return ((2.0 * worldX / windowWidth).toFloat() * camera.zoomFactor) to
                ((-2.0 * worldY / windowHeight).toFloat() * camera.zoomFactor)
    }

    /**
     * The exact inverse of [offset]: given a point in window pixels with the
     * origin at the **top-left** and Y increasing **downward** (the coordinate
     * space GLFW reports the cursor in), returns the world position that
     * [offset] would currently place at that pixel under the same [camera].
     *
     * Use this to turn a mouse click into a world-space target: without it a
     * click at the window centre reads as world `(width/2, height/2)` rather
     * than `(0, 0)`, and camera pan/zoom is ignored entirely.
     *
     * @param cursorXPx cursor X in window pixels, 0 at the left edge
     * @param cursorYPx cursor Y in window pixels, 0 at the top edge
     * @param windowWidth the window's current width in pixels
     * @param windowHeight the window's current height in pixels
     * @param camera the current pan/zoom state; must match what [offset] is given
     * @return the (x, y) world position, in the centre-origin pixel space actors live in
     */
    fun screenToWorld(
        cursorXPx: Double,
        cursorYPx: Double,
        windowWidth: Int,
        windowHeight: Int,
        camera: Camera = Camera()
    ): Pair<Double, Double> {
        val worldX = camera.panOffsetXPx + (cursorXPx - windowWidth / 2.0) / camera.zoomFactor
        val worldY = camera.panOffsetYPx + (cursorYPx - windowHeight / 2.0) / camera.zoomFactor
        return worldX to worldY
    }

    /**
     * Converts a pixel-space width/height into an NDC half-extent, so a
     * quad renders at its exact requested pixel size regardless of resolution.
     * @param widthPx the actor's width in pixels
     * @param heightPx the actor's height in pixels
     * @param windowWidth the window's current width in pixels
     * @param windowHeight the window's current height in pixels
     * @param camera the current pan/zoom state; the default leaves size unchanged
     * @return the (halfWidth, halfHeight) extent in NDC units
     */
    fun halfSize(
        widthPx: Double,
        heightPx: Double,
        windowWidth: Int,
        windowHeight: Int,
        camera: Camera = Camera()
    ): Pair<Float, Float> =
        ((widthPx / windowWidth).toFloat() * camera.zoomFactor) to ((heightPx / windowHeight).toFloat() * camera.zoomFactor)

    /**
     * Converts a rotation in degrees (as reported by [com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot.angle])
     * into radians, as the vertex shader's rotation uniform expects.
     */
    fun angleRadians(angleDegrees: Int): Float = Math.toRadians(angleDegrees.toDouble()).toFloat()

    /**
     * Converts a top-left-anchored pixel rectangle - a UI element's
     * `(x, y, width, height)` with the origin at the window's top-left and Y
     * increasing downward - into the NDC offset of the rectangle's *center*,
     * as the vertex shader's `uOffset` uniform expects.
     *
     * Unlike [offset], no [Camera] is involved: UI is drawn in fixed screen
     * space and never pans or zooms with the world.
     *
     * @return the (x, y) center offset in NDC units
     */
    fun topLeftOffset(
        xPx: Double,
        yPx: Double,
        widthPx: Double,
        heightPx: Double,
        windowWidth: Int,
        windowHeight: Int
    ): Pair<Float, Float> {
        val centerXPx = xPx + widthPx / 2.0
        val centerYPx = yPx + heightPx / 2.0
        return ((2.0 * centerXPx / windowWidth) - 1.0).toFloat() to
                (1.0 - (2.0 * centerYPx / windowHeight)).toFloat()
    }

    /**
     * Converts a single pixel position (origin top-left, Y down) straight to
     * an NDC coordinate, with no centering or camera applied. Used when
     * geometry is already expressed as absolute pixels - e.g. glyph vertices
     * from the bitmap text renderer.
     */
    fun pixelToNdc(xPx: Float, yPx: Float, windowWidth: Int, windowHeight: Int): Pair<Float, Float> =
        ((2.0f * xPx / windowWidth) - 1.0f) to (1.0f - (2.0f * yPx / windowHeight))
}