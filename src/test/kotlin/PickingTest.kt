import com.spartanlabs.graphics.Picking
import com.spartanlabs.networking.Camera
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Picking], the pure cursor-to-quad hit-testing. It reuses
 * [com.spartanlabs.networking.NdcConverter]'s conversions internally, so the
 * expected values here are worked out against the same offset/half-size
 * arithmetic that test covers - no GL context needed.
 *
 * All tests use an 800x600 window and, unless stated, a default (no
 * pan/zoom) camera.
 */
class PickingTest {

    private fun actor(x: Double, y: Double, width: Double, height: Double, angle: Int = 0, turns: Boolean = false) =
        visibleObjectSnapshot(x = x, y = y, width = width, height = height, angle = angle, turns = turns)

    @Nested
    @DisplayName("contains()")
    inner class ContainsTests {

        @Test
        fun `a click on an actor's centre is a hit`() {
            val hit = Picking.contains(actor(0.0, 0.0, 100.0, 100.0), 400.0, 300.0, 800, 600, Camera())

            assertTrue(hit)
        }

        @Test
        fun `a click well outside the actor is a miss`() {
            val hit = Picking.contains(actor(0.0, 0.0, 100.0, 100.0), 400.0, 100.0, 800, 600, Camera())

            assertFalse(hit)
        }

        @Test
        fun `the horizontal edge is the boundary between hit and miss`() {
            // half-width 100px on an 800px window -> NDC 0.125 -> pixel x 450
            val actor = actor(0.0, 0.0, 100.0, 100.0)

            assertTrue(Picking.contains(actor, 449.0, 300.0, 800, 600, Camera()))
            assertFalse(Picking.contains(actor, 451.0, 300.0, 800, 600, Camera()))
        }

        @Test
        fun `rotation is honoured for a turning actor - a point off the long axis only hits once turned`() {
            // 100x40 quad. Cursor at NDC (0, 0.1): outside the 0.0667 half-height
            // upright, inside the 0.125 half-width once rotated 90 degrees.
            val cursorX = 400.0
            val cursorY = 270.0

            assertFalse(Picking.contains(actor(0.0, 0.0, 100.0, 40.0, angle = 0, turns = true), cursorX, cursorY, 800, 600, Camera()))
            assertTrue(Picking.contains(actor(0.0, 0.0, 100.0, 40.0, angle = 90, turns = true), cursorX, cursorY, 800, 600, Camera()))
        }

        @Test
        fun `a positive angle turns the actor clockwise on screen`() {
            // 200x40 quad at 45 degrees. Its long axis runs down-right (a
            // clockwise turn from horizontal), so a click down-right of centre
            // is on the quad and one up-right of centre is off it. Flip the
            // rotation sign and this reverses.
            val actor = actor(0.0, 0.0, 200.0, 40.0, angle = 45, turns = true)

            assertTrue(Picking.contains(actor, 460.0, 345.0, 800, 600, Camera()))  // down-right
            assertFalse(Picking.contains(actor, 460.0, 255.0, 800, 600, Camera())) // up-right
        }

        @Test
        fun `a non-turning actor is hit-tested as an axis-aligned box, ignoring its angle`() {
            // Same cursor and quad as above, angle 90 - but turns=false, so it's
            // still tested upright and the point off the long axis misses.
            val hit = Picking.contains(
                actor(0.0, 0.0, 100.0, 40.0, angle = 90, turns = false),
                400.0, 270.0, 800, 600, Camera()
            )

            assertFalse(hit)
        }

        @Test
        fun `a camera pan moves the hit box with the actor on screen`() {
            // panOffsetXPx 200 shifts the actor's screen centre to NDC -0.5 -> pixel x 200
            val actor = actor(0.0, 0.0, 100.0, 100.0)
            val panned = Camera(panOffsetXPx = 200.0f)

            assertTrue(Picking.contains(actor, 200.0, 300.0, 800, 600, panned))
            assertFalse(Picking.contains(actor, 400.0, 300.0, 800, 600, panned))
        }
    }

    @Nested
    @DisplayName("pick()")
    inner class PickTests {

        @Test
        fun `returns null when nothing is under the cursor`() {
            val snapshots = listOf(actor(0.0, 0.0, 40.0, 40.0))

            assertNull(Picking.pick(400.0, 100.0, 800, 600, Camera(), snapshots))
        }

        @Test
        fun `returns null for an empty scene`() {
            assertNull(Picking.pick(400.0, 300.0, 800, 600, Camera(), emptyList()))
        }

        @Test
        fun `picks the top-most (last-drawn) actor when quads overlap`() {
            val snapshots = listOf(
                actor(0.0, 0.0, 100.0, 100.0),
                actor(0.0, 0.0, 100.0, 100.0)
            )

            assertEquals(1, Picking.pick(400.0, 300.0, 800, 600, Camera(), snapshots))
        }

        @Test
        fun `picks the only actual hit even if it is not the front-most in the list`() {
            val snapshots = listOf(
                actor(0.0, 0.0, 100.0, 100.0),   // under the cursor
                actor(600.0, 0.0, 100.0, 100.0)  // far to the right
            )

            assertEquals(0, Picking.pick(400.0, 300.0, 800, 600, Camera(), snapshots))
        }
    }
}
