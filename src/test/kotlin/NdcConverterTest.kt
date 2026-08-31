import com.spartanlabs.networking.Camera
import com.spartanlabs.networking.NdcConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [com.spartanlabs.networking.NdcConverter]'s pixel-to-NDC conversion math. Pure
 * arithmetic with no GLFW/OpenGL dependency, so no mocking is needed - this
 * is the part of [com.spartanlabs.graphics.Window] that can actually be unit tested; the rest
 * requires a live GPU context and is exercised manually/via integration testing.
 *
 * Every assertion uses [EPSILON] tolerance rather than exact equality.
 * IEEE 754 floating point distinguishes +0.0 from -0.0 (`-2.0 * 0.0`
 * evaluates to -0.0), and JUnit's no-delta float assertEquals uses
 * Float.compare() internally, which does not consider those equal - a real
 * trap for offset(), whose Y formula has a leading negative sign. A delta
 * comparison sidesteps that and is generally the right tool for float
 * assertions regardless.
 */
class NdcConverterTest {

    @Nested
    @DisplayName("offset()")
    inner class OffsetTests {

        @Test
        fun `center of the window maps to the NDC origin`() {
            val (x, y) = NdcConverter.offset(xPx = 0.0, yPx = 0.0, windowWidth = 800, windowHeight = 600)

            assertEquals(0.0f, x, EPSILON)
            assertEquals(0.0f, y, EPSILON)
        }

        @Test
        fun `positive x moves right in NDC`() {
            val (x, _) = NdcConverter.offset(xPx = 400.0, yPx = 0.0, windowWidth = 800, windowHeight = 600)

            assertEquals(1.0f, x, EPSILON)
        }

        @Test
        fun `positive y (downward in pixel space) becomes negative in NDC (Y-up)`() {
            val (_, y) = NdcConverter.offset(xPx = 0.0, yPx = 300.0, windowWidth = 800, windowHeight = 600)

            assertEquals(-1.0f, y, EPSILON)
        }

        @Test
        fun `default camera leaves the position unchanged`() {
            val (x, _) = NdcConverter.offset(xPx = 400.0, yPx = 0.0, windowWidth = 800, windowHeight = 600)

            assertEquals(1.0f, x, EPSILON)
        }

        @Test
        fun `a zoomFactor of 2 doubles the distance from center`() {
            val (x, _) = NdcConverter.offset(
                xPx = 200.0, yPx = 0.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(zoomFactor = 2.0f)
            )

            assertEquals(1.0f, x, EPSILON)
        }

        @Test
        fun `a zoomFactor of 0-point-5 halves the distance from center`() {
            val (x, _) = NdcConverter.offset(
                xPx = 400.0, yPx = 0.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(zoomFactor = 0.5f)
            )

            assertEquals(0.5f, x, EPSILON)
        }

        @Test
        fun `an actor already at the center stays at the center regardless of zoom`() {
            val (x, y) = NdcConverter.offset(
                xPx = 0.0, yPx = 0.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(zoomFactor = 3.0f)
            )

            assertEquals(0.0f, x, EPSILON)
            assertEquals(0.0f, y, EPSILON)
        }
    }

    @Nested
    @DisplayName("offset() panning")
    inner class PanningTests {

        @Test
        fun `panning right shifts an actor's apparent position left`() {
            val (x, _) = NdcConverter.offset(
                xPx = 0.0, yPx = 0.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(panOffsetXPx = 200.0f)
            )

            assertEquals(-0.5f, x, EPSILON)
        }

        @Test
        fun `panning down shifts an actor's apparent position up`() {
            val (_, y) = NdcConverter.offset(
                xPx = 0.0, yPx = 0.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(panOffsetYPx = 150.0f)
            )

            assertEquals(0.5f, y, EPSILON)
        }

        @Test
        fun `panning to an actor's exact position brings it to the center`() {
            val (x, y) = NdcConverter.offset(
                xPx = 300.0, yPx = -100.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(panOffsetXPx = 300.0f, panOffsetYPx = -100.0f)
            )

            assertEquals(0.0f, x, EPSILON)
            assertEquals(0.0f, y, EPSILON)
        }

        @Test
        fun `pan and zoom combine - pan applies before zoom scales the result`() {
            // worldX = 300 - 100 = 200; NDC before zoom = 2*200/800 = 0.5; *zoom(2) = 1.0
            val (x, _) = NdcConverter.offset(
                xPx = 300.0, yPx = 0.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(zoomFactor = 2.0f, panOffsetXPx = 100.0f)
            )

            assertEquals(1.0f, x, EPSILON)
        }
    }

    @Nested
    @DisplayName("halfSize()")
    inner class HalfSizeTests {

        @Test
        fun `a quad as wide as the window has a half-size of 1`() {
            val (halfWidth, _) = NdcConverter.halfSize(
                widthPx = 800.0, heightPx = 100.0, windowWidth = 800, windowHeight = 600
            )

            assertEquals(1.0f, halfWidth, EPSILON)
        }

        @Test
        fun `a 100px quad on an 800px-wide window converts to the expected fraction`() {
            val (halfWidth, _) = NdcConverter.halfSize(
                widthPx = 100.0, heightPx = 100.0, windowWidth = 800, windowHeight = 600
            )

            assertEquals(0.125f, halfWidth, EPSILON)
        }

        @Test
        fun `width and height convert independently`() {
            val (halfWidth, halfHeight) = NdcConverter.halfSize(
                widthPx = 200.0, heightPx = 300.0, windowWidth = 800, windowHeight = 600
            )

            assertEquals(0.25f, halfWidth, EPSILON)
            assertEquals(0.5f, halfHeight, EPSILON)
        }

        @Test
        fun `default camera leaves the size unchanged`() {
            val (halfWidth, _) = NdcConverter.halfSize(
                widthPx = 100.0, heightPx = 100.0, windowWidth = 800, windowHeight = 600
            )

            assertEquals(0.125f, halfWidth, EPSILON)
        }

        @Test
        fun `a zoomFactor of 2 doubles the rendered size`() {
            val (halfWidth, halfHeight) = NdcConverter.halfSize(
                widthPx = 100.0, heightPx = 100.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(zoomFactor = 2.0f)
            )

            // width: 100/800 = 0.125, doubled -> 0.25
            assertEquals(0.25f, halfWidth, EPSILON)
            // height: 100/600 ~= 0.1667, doubled -> ~0.3333
            assertEquals(0.33333334f, halfHeight, EPSILON)
        }

        @Test
        fun `a zoomFactor of 0-point-5 halves the rendered size`() {
            val (halfWidth, _) = NdcConverter.halfSize(
                widthPx = 100.0, heightPx = 100.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(zoomFactor = 0.5f)
            )

            assertEquals(0.0625f, halfWidth, EPSILON)
        }

        @Test
        fun `panning does not affect size, only zoom does`() {
            val (halfWidth, _) = NdcConverter.halfSize(
                widthPx = 100.0, heightPx = 100.0, windowWidth = 800, windowHeight = 600,
                camera = Camera(panOffsetXPx = 500.0f, panOffsetYPx = 500.0f)
            )

            assertEquals(0.125f, halfWidth, EPSILON)
        }
    }

    @Nested
    @DisplayName("screenToWorld()")
    inner class ScreenToWorldTests {

        @Test
        fun `the window center maps to the world origin`() {
            val (x, y) = NdcConverter.screenToWorld(
                cursorXPx = 400.0, cursorYPx = 300.0, windowWidth = 800, windowHeight = 600
            )

            assertEquals(0.0, x, EPSILON_D)
            assertEquals(0.0, y, EPSILON_D)
        }

        @Test
        fun `the top-left corner maps to negative-x, negative-y world coords`() {
            val (x, y) = NdcConverter.screenToWorld(
                cursorXPx = 0.0, cursorYPx = 0.0, windowWidth = 800, windowHeight = 600
            )

            assertEquals(-400.0, x, EPSILON_D)
            assertEquals(-300.0, y, EPSILON_D)
        }

        @Test
        fun `it is the exact inverse of offset() under pan and zoom`() {
            val camera = Camera(zoomFactor = 1.7f, panOffsetXPx = 220.0f, panOffsetYPx = -90.0f)

            // offset() takes a world point and returns where it lands on screen (NDC);
            // screenToWorld() must recover that same world point from the equivalent pixel.
            val worldX = 175.0
            val worldY = -60.0
            val (ndcX, ndcY) = NdcConverter.offset(worldX, worldY, 800, 600, camera)
            val cursorXPx = (ndcX + 1.0) / 2.0 * 800
            val cursorYPx = (1.0 - ndcY) / 2.0 * 600

            val (backX, backY) = NdcConverter.screenToWorld(cursorXPx, cursorYPx, 800, 600, camera)

            assertEquals(worldX, backX, 1e-3)
            assertEquals(worldY, backY, 1e-3)
        }
    }

    @Nested
    @DisplayName("topLeftOffset()")
    inner class TopLeftOffsetTests {

        @Test
        fun `a rect filling the window is centered at the NDC origin`() {
            val (x, y) = NdcConverter.topLeftOffset(
                xPx = 0.0, yPx = 0.0, widthPx = 800.0, heightPx = 600.0,
                windowWidth = 800, windowHeight = 600
            )

            assertEquals(0.0f, x, EPSILON)
            assertEquals(0.0f, y, EPSILON)
        }

        @Test
        fun `a rect in the top-left quadrant has a negative-x, positive-y center`() {
            // 100x100 rect at (0,0): center (50,50) -> x = 2*50/800-1, y = 1-2*50/600
            val (x, y) = NdcConverter.topLeftOffset(
                xPx = 0.0, yPx = 0.0, widthPx = 100.0, heightPx = 100.0,
                windowWidth = 800, windowHeight = 600
            )

            assertEquals(-0.875f, x, EPSILON)
            assertEquals(0.8333333f, y, EPSILON)
        }

        @Test
        fun `a rect flush against the bottom-right corner centers near NDC (1, -1)`() {
            val (x, y) = NdcConverter.topLeftOffset(
                xPx = 700.0, yPx = 500.0, widthPx = 100.0, heightPx = 100.0,
                windowWidth = 800, windowHeight = 600
            )

            assertEquals(0.875f, x, EPSILON)
            assertEquals(-0.8333333f, y, EPSILON)
        }
    }

    @Nested
    @DisplayName("pixelToNdc()")
    inner class PixelToNdcTests {

        @Test
        fun `the top-left pixel maps to NDC (-1, 1)`() {
            val (x, y) = NdcConverter.pixelToNdc(0f, 0f, windowWidth = 800, windowHeight = 600)

            assertEquals(-1.0f, x, EPSILON)
            assertEquals(1.0f, y, EPSILON)
        }

        @Test
        fun `the bottom-right pixel maps to NDC (1, -1)`() {
            val (x, y) = NdcConverter.pixelToNdc(800f, 600f, windowWidth = 800, windowHeight = 600)

            assertEquals(1.0f, x, EPSILON)
            assertEquals(-1.0f, y, EPSILON)
        }
    }

    @Nested
    @DisplayName("angleRadians()")
    inner class AngleRadiansTests {

        @Test
        fun `zero degrees is zero radians`() {
            assertEquals(0.0f, NdcConverter.angleRadians(0), EPSILON)
        }

        @Test
        fun `180 degrees is pi radians`() {
            assertEquals(Math.PI.toFloat(), NdcConverter.angleRadians(180), EPSILON)
        }

        @Test
        fun `360 degrees is a full turn (2pi radians)`() {
            assertEquals((2 * Math.PI).toFloat(), NdcConverter.angleRadians(360), EPSILON)
        }
    }

    private companion object {
        /** Tolerance for float comparisons - see the class KDoc for why exact equality isn't used here. */
        const val EPSILON = 0.0001f

        /** The same tolerance for the double-valued conversions ([NdcConverter.screenToWorld]). */
        const val EPSILON_D = 0.0001
    }
}