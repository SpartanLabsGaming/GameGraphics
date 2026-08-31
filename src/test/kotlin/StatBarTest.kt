import com.spartanlabs.geometry.Dimensions
import com.spartanlabs.geometry.Point
import com.spartanlabs.geometry.Square
import com.spartanlabs.graphics.ui.Panel
import com.spartanlabs.graphics.ui.Scene
import com.spartanlabs.graphics.ui.StatBar
import com.spartanlabs.graphics.ui.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StatBar]'s pure model: its [StatBar.fraction] math and the
 * two-layer geometry [flatten] produces from it. GL rendering is exercised
 * manually, same as the rest of the UI.
 */
class StatBarTest {

    private fun square(x: Double, y: Double, w: Double, h: Double) =
        Square(Point(x, y), Dimensions(w, h))

    private val W = 1000
    private val H = 500

    @Test
    fun `fraction is value over maxValue, clamped to 0 and 1`() {
        assertEquals(0.5, StatBar(value = 50.0, maxValue = 100.0).fraction)
        assertEquals(1.0, StatBar(value = 250.0, maxValue = 100.0).fraction)
        assertEquals(0.0, StatBar(value = -10.0, maxValue = 100.0).fraction)
    }

    @Test
    fun `fraction is zero when maxValue is not positive`() {
        assertEquals(0.0, StatBar(value = 10.0, maxValue = 0.0).fraction)
        assertEquals(0.0, StatBar(value = 10.0, maxValue = -5.0).fraction)
    }

    @Test
    fun `fraction and suppliers are re-read every access`() {
        var value = 100.0
        val bar = StatBar(value = { value }, maxValue = { 100.0 })

        assertEquals(1.0, bar.fraction)
        value = 25.0
        assertEquals(0.25, bar.fraction)
    }

    @Test
    fun `flatten lays a full-size track behind a fractional-width fill`() {
        val bar = StatBar(position = square(0.1, 0.2, 0.5, 0.1), value = 30.0, maxValue = 100.0)
        val scene = Scene().apply { add(bar) }

        val positioned = scene.flatten(W, H)

        // bar itself, then track, then fill
        assertEquals(3, positioned.size)
        assertSame(bar, positioned[0].element)

        val barWidthPx = 0.5 * W
        val track = positioned[1]
        assertEquals(barWidthPx, track.width, 1e-9)
        assertEquals(0.1 * H, track.height, 1e-9)
        assertEquals((track.element as Panel).color, StatBar.DEFAULT_TRACK_COLOR)

        val fill = positioned[2]
        assertEquals(0.3 * barWidthPx, fill.width, 1e-9)
        assertEquals(track.height, fill.height, 1e-9)
        assertEquals(track.x, fill.x, 1e-9)
        assertEquals(track.y, fill.y, 1e-9)
        assertEquals((fill.element as Panel).color, StatBar.DEFAULT_FILL_COLOR)
    }

    @Test
    fun `a full bar's fill matches the track width`() {
        val bar = StatBar(position = square(0.0, 0.0, 1.0, 1.0), value = 100.0, maxValue = 100.0)

        val positioned = Scene().apply { add(bar) }.flatten(W, H)

        assertEquals(positioned[1].width, positioned[2].width, 1e-9)
    }

    @Test
    fun `an invisible bar contributes no layers to draw`() {
        val bar = StatBar(position = square(0.0, 0.0, 1.0, 1.0), value = 50.0, maxValue = 100.0, visible = false)

        val positioned = Scene().apply { add(bar) }.flatten(W, H)

        assertEquals(1, positioned.size) // the bar element itself, no track / fill
        assertSame(bar, positioned[0].element)
    }

    @Test
    fun `visibility is re-read every frame`() {
        var show = false
        val bar = StatBar(
            position = square(0.0, 0.0, 1.0, 1.0),
            value = { 50.0 }, maxValue = { 100.0 }, visible = { show }
        )
        val scene = Scene().apply { add(bar) }

        assertEquals(1, scene.flatten(W, H).size)
        show = true
        assertEquals(3, scene.flatten(W, H).size)
    }
}
