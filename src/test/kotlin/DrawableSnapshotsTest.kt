import com.spartanlabs.gaming.gameobjects.ActorSnapshot
import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.StatGroupSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.geometry.serializations.PointSnapshot
import com.spartanlabs.networking.drawableCore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Covers the GameTools 1.6.0 wire change: a `STATE` broadcast is now a
 * polymorphic list of [DrawableSnapshot]. Confirms the client can decode the
 * exact JSON the server's default [Json] produces, and that [drawableCore]
 * unwraps every kind - including nested sub-objects - back to the plain
 * [VisibleObjectSnapshot] the renderer uses.
 */
class DrawableSnapshotsTest {

    // Matches NetworkClient's decoder configuration.
    private val clientJson = Json { ignoreUnknownKeys = true }

    private fun visibleObject(texture: String, subObjects: List<DrawableSnapshot> = emptyList()) =
        visibleObjectSnapshot(x = 1.0, y = 2.0, width = 3.0, height = 4.0, texture = texture)
            .copy(subObjects = subObjects)

    private fun actor(texture: String) =
        ActorSnapshot(visibleObject(texture), speed = 5.0, destination = PointSnapshot(9.0, 9.0))

    private fun alive(texture: String) =
        AliveSnapshot(
            actor(texture),
            health = StatGroupSnapshot(50.0, 100.0, 100.0),
            faction = "red",
            ownerName = "Player1",
            damage = 10.0
        )

    /** Serializes the way GameServer.broadcast does, then decodes the way NetworkClient does. */
    private fun roundTrip(snapshots: List<DrawableSnapshot>): List<DrawableSnapshot> =
        clientJson.decodeFromString(Json.encodeToString(snapshots))

    @Test
    fun `a polymorphic STATE list decodes and every kind unwraps to its drawable core`() {
        val decoded = roundTrip(listOf(visibleObject("plain.png"), actor("actor.png"), alive("alive.png")))

        val cores = decoded.map { it.drawableCore() }
        assertEquals(listOf("plain.png", "actor.png", "alive.png"), cores.map { it.texture })
        assertEquals(listOf(1.0, 1.0, 1.0), cores.map { it.gameObject.location.x })
    }

    @Test
    fun `drawableCore recurses into a sub-object that is itself an actor`() {
        val parent = visibleObject("parent.png", subObjects = listOf(actor("child.png")))

        val core = roundTrip(listOf(parent)).single().drawableCore()

        assertEquals("parent.png", core.texture)
        val child = core.subObjects.single()
        assertEquals("child.png", (child as VisibleObjectSnapshot).texture)
    }

    @Test
    fun `drawableCore returns the same instance when nothing needs unwrapping`() {
        val plain = visibleObjectSnapshot(texture = "plain.png")

        assertSame(plain, (plain as DrawableSnapshot).drawableCore())
    }
}
