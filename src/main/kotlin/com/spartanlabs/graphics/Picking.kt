package com.spartanlabs.graphics

import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.networking.Camera
import com.spartanlabs.networking.NdcConverter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure "which quad is under this pixel" hit-testing.
 *
 * The test is done in NDC space using the very same [NdcConverter]
 * offset / half-size / rotation that [Window]'s vertex shader applies, so a
 * click resolves against exactly what is on screen - including camera
 * pan/zoom and a rotated quad on a non-square window (where the shader's
 * NDC-space rotation is not a rigid rotation in pixels).
 */
internal object Picking {

    /**
     * @return the index of the top-most (last-drawn, so visually front-most)
     * snapshot whose quad contains the cursor, or null if the cursor is over
     * empty space
     */
    fun pick(
        cursorXPx: Double,
        cursorYPx: Double,
        windowWidth: Int,
        windowHeight: Int,
        camera: Camera,
        snapshots: List<VisibleObjectSnapshot>
    ): Int? {
        for (index in snapshots.indices.reversed()) {
            if (contains(snapshots[index], cursorXPx, cursorYPx, windowWidth, windowHeight, camera)) {
                return index
            }
        }
        return null
    }

    /** True if the cursor pixel falls inside [snapshot]'s quad as currently rendered. */
    fun contains(
        snapshot: VisibleObjectSnapshot,
        cursorXPx: Double,
        cursorYPx: Double,
        windowWidth: Int,
        windowHeight: Int,
        camera: Camera
    ): Boolean {
        val (centerX, centerY) = NdcConverter.offset(
            snapshot.gameObject.location.x, snapshot.gameObject.location.y, windowWidth, windowHeight, camera
        )
        val (halfX, halfY) = NdcConverter.halfSize(
            snapshot.dimensions.width, snapshot.dimensions.height, windowWidth, windowHeight, camera
        )
        val (cursorNdcX, cursorNdcY) = NdcConverter.pixelToNdc(
            cursorXPx.toFloat(), cursorYPx.toFloat(), windowWidth, windowHeight
        )

        val deltaX = cursorNdcX - centerX
        val deltaY = cursorNdcY - centerY

        // The shader turns the quad by the negated angle (positive angle =
        // clockwise on screen), and only when the object turns. Undo that same
        // rotation on the click to reach the quad's axis-aligned local frame,
        // then it's a box test against the (NDC) half-extents. Keep this sign
        // in step with Window.drawActor.
        val angle = NdcConverter.angleRadians(if (snapshot.turns) -snapshot.angle else 0)
        val cos = cos(angle)
        val sin = sin(angle)
        val localX = deltaX * cos + deltaY * sin
        val localY = -deltaX * sin + deltaY * cos

        return abs(localX) <= halfX && abs(localY) <= halfY
    }
}
