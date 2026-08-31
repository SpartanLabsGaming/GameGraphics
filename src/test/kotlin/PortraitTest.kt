import com.spartanlabs.gaming.gameobjects.ColorSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.graphics.ui.Color
import com.spartanlabs.graphics.ui.Portrait
import com.spartanlabs.graphics.ui.screenRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private fun portrait(subject: () -> VisibleObjectSnapshot?) =
    Portrait(subject, screenRect(0.0, 0.0, 1.0, 1.0))

class PortraitTest {

    @Test
    fun `with no subject it renders nothing`() {
        val p = portrait { null }

        assertEquals(Color.TRANSPARENT, p.color)
        assertNull(p.texture)
    }

    @Test
    fun `it copies the subject's colour straight from the snapshot`() {
        val p = portrait { visibleObjectSnapshot(color = ColorSnapshot(10, 20, 30, 200)) }

        assertEquals(Color(10, 20, 30, 200), p.color)
    }

    @Test
    fun `it copies the subject's texture name straight from the snapshot`() {
        val p = portrait { visibleObjectSnapshot(texture = "hero.png") }

        assertEquals("hero.png", p.texture)
    }

    @Test
    fun `the copied object may change between reads`() {
        var current: VisibleObjectSnapshot? = null
        val p = portrait { current }

        assertEquals(Color.TRANSPARENT, p.color)
        assertNull(p.texture)

        current = visibleObjectSnapshot(color = ColorSnapshot(1, 2, 3, 4), texture = "a.png")
        assertEquals(Color(1, 2, 3, 4), p.color)
        assertEquals("a.png", p.texture)

        current = visibleObjectSnapshot(color = ColorSnapshot(9, 9, 9, 9), texture = "b.png")
        assertEquals(Color(9, 9, 9, 9), p.color)
        assertEquals("b.png", p.texture)

        current = null
        assertEquals(Color.TRANSPARENT, p.color)
        assertNull(p.texture)
    }
}
