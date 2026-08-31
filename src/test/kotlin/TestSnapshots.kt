import com.spartanlabs.gaming.gameobjects.ColorSnapshot
import com.spartanlabs.gaming.gameobjects.GameObjectSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.geometry.serializations.DimensionsSnapshot
import com.spartanlabs.geometry.serializations.PointSnapshot

/** Builds a [VisibleObjectSnapshot] for tests, with sensible defaults for every field. */
fun visibleObjectSnapshot(
    x: Double = 0.0,
    y: Double = 0.0,
    width: Double = 0.0,
    height: Double = 0.0,
    angle: Int = 0,
    turns: Boolean = false,
    color: ColorSnapshot = ColorSnapshot(255, 255, 255, 255),
    texture: String = "default.png",
    subObjects: List<VisibleObjectSnapshot> = emptyList()
): VisibleObjectSnapshot = VisibleObjectSnapshot(
    GameObjectSnapshot(PointSnapshot(x, y)),
    DimensionsSnapshot(width, height),
    color,
    texture,
    angle,
    turns,
    subObjects
)
