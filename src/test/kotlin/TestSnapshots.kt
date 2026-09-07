import com.spartanlabs.gaming.gameobjects.ColorSnapshot
import com.spartanlabs.gaming.gameobjects.EntityId
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
    // GameTools stamps a stable entity id on every DrawableSnapshot; 0 (EntityId.UNASSIGNED) is unowned.
    // Taken here as a bare Long and wrapped, since 5.0.0 types the field as EntityId.
    id: Long = 0L,
    subObjects: List<VisibleObjectSnapshot> = emptyList()
): VisibleObjectSnapshot = VisibleObjectSnapshot(
    id = EntityId(id),
    gameObject = GameObjectSnapshot(PointSnapshot(x, y)),
    dimensions = DimensionsSnapshot(width, height),
    color = color,
    texture = texture,
    angle = angle,
    turns = turns,
    subObjects = subObjects
)
