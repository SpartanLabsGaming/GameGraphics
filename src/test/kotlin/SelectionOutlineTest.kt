import com.spartanlabs.graphics.OutlineEdge
import com.spartanlabs.graphics.SelectionOutline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SelectionOutline.edges] - the pure world-pixel geometry of
 * the in-world selection highlight. Every case is a plain numbers-in /
 * rectangles-out mapping; no GL, no camera, no state.
 */
class SelectionOutlineTest {

    private val centerX = 100.0
    private val centerY = 40.0
    private val unitWidth = 50.0
    private val unitHeight = 30.0
    private val margin = 6.0
    private val thickness = 3.0

    private fun outline(): List<OutlineEdge> =
        SelectionOutline.edges(centerX, centerY, unitWidth, unitHeight, margin, thickness)

    /** The outer bounding box (min/max X and Y) the four edges together cover. */
    private fun bounds(edges: List<OutlineEdge>): DoubleArray {
        val minX = edges.minOf { it.cx - it.widthPx / 2.0 }
        val maxX = edges.maxOf { it.cx + it.widthPx / 2.0 }
        val minY = edges.minOf { it.cy - it.heightPx / 2.0 }
        val maxY = edges.maxOf { it.cy + it.heightPx / 2.0 }
        return doubleArrayOf(minX, maxX, minY, maxY)
    }

    @Test
    fun `returns exactly four edges - top, bottom, left, right`() {
        assertEquals(4, outline().size)
    }

    @Test
    fun `the inner edge of the frame sits margin outside the unit box on every side`() {
        val (top, bottom, left, right) = outline()

        // Inner edge = the frame edge nearest the unit centre.
        assertEquals(centerY - unitHeight / 2.0 - margin, top.cy + top.heightPx / 2.0, 1e-9)
        assertEquals(centerY + unitHeight / 2.0 + margin, bottom.cy - bottom.heightPx / 2.0, 1e-9)
        assertEquals(centerX - unitWidth / 2.0 - margin, left.cx + left.widthPx / 2.0, 1e-9)
        assertEquals(centerX + unitWidth / 2.0 + margin, right.cx - right.widthPx / 2.0, 1e-9)
    }

    @Test
    fun `each edge is thickness thick on its short axis`() {
        val (top, bottom, left, right) = outline()

        assertEquals(thickness, top.heightPx, 1e-9)
        assertEquals(thickness, bottom.heightPx, 1e-9)
        assertEquals(thickness, left.widthPx, 1e-9)
        assertEquals(thickness, right.widthPx, 1e-9)
    }

    @Test
    fun `horizontal edges span the full outer width and vertical edges the full inner height`() {
        val (top, _, left, _) = outline()

        val outerWidth = unitWidth + 2.0 * (margin + thickness)
        val innerHeight = unitHeight + 2.0 * margin

        assertEquals(outerWidth, top.widthPx, 1e-9)
        assertEquals(innerHeight, left.heightPx, 1e-9)
    }

    @Test
    fun `the edges tile the frame with no overlap and no gap between neighbours`() {
        val (top, bottom, left, _) = outline()

        // The vertical edges start exactly where the top edge ends and end
        // exactly where the bottom edge starts.
        assertEquals(top.cy + top.heightPx / 2.0, left.cy - left.heightPx / 2.0, 1e-9)
        assertEquals(bottom.cy - bottom.heightPx / 2.0, left.cy + left.heightPx / 2.0, 1e-9)
    }

    @Test
    fun `the outline is symmetric about the unit centre`() {
        val (minX, maxX, minY, maxY) = bounds(outline())

        assertEquals(centerX, (minX + maxX) / 2.0, 1e-9)
        assertEquals(centerY, (minY + maxY) / 2.0, 1e-9)
    }

    @Test
    fun `the outer bounding box is the unit box plus margin plus thickness on every side`() {
        val (minX, maxX, minY, maxY) = bounds(outline())
        val pad = margin + thickness

        assertEquals(centerX - unitWidth / 2.0 - pad, minX, 1e-9)
        assertEquals(centerX + unitWidth / 2.0 + pad, maxX, 1e-9)
        assertEquals(centerY - unitHeight / 2.0 - pad, minY, 1e-9)
        assertEquals(centerY + unitHeight / 2.0 + pad, maxY, 1e-9)
    }

    @Test
    fun `a zero-size unit still yields a well-formed frame around the point`() {
        val edges = SelectionOutline.edges(0.0, 0.0, 0.0, 0.0, margin, thickness)

        assertEquals(4, edges.size)
        assertTrue(edges.all { it.widthPx > 0.0 && it.heightPx > 0.0 }, "every edge has positive extent")
        val (minX, maxX, minY, maxY) = bounds(edges)
        assertEquals(0.0, (minX + maxX) / 2.0, 1e-9)
        assertEquals(0.0, (minY + maxY) / 2.0, 1e-9)
        assertEquals(2.0 * (margin + thickness), maxX - minX, 1e-9)
    }
}
