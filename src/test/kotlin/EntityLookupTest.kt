import com.spartanlabs.gaming.gameobjects.ActorSnapshot
import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.CombinedStatSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.geometry.serializations.PointSnapshot
import com.spartanlabs.networking.attackTarget
import com.spartanlabs.networking.byEntityId
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for the entity-id lookups over a `STATE` list
 * (`com.spartanlabs.networking.EntityLookup`). Every case is a pure
 * list-in / value-out mapping - no network, no GL.
 */
class EntityLookupTest {

    private fun alive(id: Long, owner: String?): AliveSnapshot =
        AliveSnapshot(
            id = EntityId(id),
            actor = ActorSnapshot(
                id = EntityId(id),
                visibleObject = visibleObjectSnapshot(id = id, texture = "unit.png"),
                speed = 1.0,
                destination = PointSnapshot(0.0, 0.0)
            ),
            health = CombinedStatSnapshot(100.0, 100.0),
            faction = "red",
            ownerName = owner,
            damage = 10.0,
            attackTime = 1.0,
            attackSpeed = 1.0,
            attackRange = 1.0,
            evasion = 0.0
        )

    @Nested
    @DisplayName("byEntityId()")
    inner class ByEntityId {

        @Test
        fun `finds the entry whose id matches`() {
            val wanted = visibleObjectSnapshot(id = 42L)
            val state: List<DrawableSnapshot> = listOf(visibleObjectSnapshot(id = 7L), wanted, visibleObjectSnapshot(id = 9L))

            assertSame(wanted, state.byEntityId(42L))
        }

        @Test
        fun `returns null when no entry has the id`() {
            val state: List<DrawableSnapshot> = listOf(visibleObjectSnapshot(id = 7L), visibleObjectSnapshot(id = 9L))

            assertNull(state.byEntityId(42L))
        }

        @Test
        fun `returns null for UNIDENTIFIED even when an entry carries that sentinel`() {
            val state: List<DrawableSnapshot> = listOf(visibleObjectSnapshot(id = EntityId.UNASSIGNED.raw))

            assertNull(state.byEntityId(EntityId.UNASSIGNED.raw))
        }

        @Test
        fun `returns the first match if two entries share an id`() {
            val first = visibleObjectSnapshot(id = 5L, texture = "first.png")
            val second = visibleObjectSnapshot(id = 5L, texture = "second.png")
            val state: List<DrawableSnapshot> = listOf(first, second)

            assertSame(first, state.byEntityId(5L))
        }
    }

    @Nested
    @DisplayName("attackTarget()")
    inner class AttackTarget {

        @Test
        fun `an enemy Alive under the cursor is a valid target`() {
            val enemy = alive(id = 20L, owner = "Player2")
            val state: List<DrawableSnapshot> = listOf(alive(id = 10L, owner = "Player1"), enemy)

            assertSame(enemy, state.attackTarget(attackerId = 10L, targetId = 20L, playerName = "Player1"))
        }

        @Test
        fun `one of the player's own units is not a target`() {
            val state: List<DrawableSnapshot> = listOf(alive(id = 10L, owner = "Player1"), alive(id = 20L, owner = "Player1"))

            assertNull(state.attackTarget(attackerId = 10L, targetId = 20L, playerName = "Player1"))
        }

        @Test
        fun `the attacker cannot target itself`() {
            val state: List<DrawableSnapshot> = listOf(alive(id = 10L, owner = "Player1"))

            assertNull(state.attackTarget(attackerId = 10L, targetId = 10L, playerName = "Player1"))
        }

        @Test
        fun `a non-Alive pick (terrain or a plain actor) is not a target`() {
            val state: List<DrawableSnapshot> = listOf(alive(id = 10L, owner = "Player1"), visibleObjectSnapshot(id = 30L))

            assertNull(state.attackTarget(attackerId = 10L, targetId = 30L, playerName = "Player1"))
        }

        @Test
        fun `a target id no longer in the list is not a target`() {
            val state: List<DrawableSnapshot> = listOf(alive(id = 10L, owner = "Player1"))

            assertNull(state.attackTarget(attackerId = 10L, targetId = 99L, playerName = "Player1"))
        }

        @Test
        fun `an unidentified target is not a target`() {
            val state: List<DrawableSnapshot> = listOf(alive(id = 10L, owner = "Player1"))

            assertNull(state.attackTarget(attackerId = 10L, targetId = EntityId.UNASSIGNED.raw, playerName = "Player1"))
        }
    }
}
