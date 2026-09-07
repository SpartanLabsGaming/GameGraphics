import com.spartanlabs.gaming.gameobjects.ActorSnapshot
import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.CombinedStatSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.geometry.serializations.PointSnapshot
import com.spartanlabs.networking.attackTarget
import com.spartanlabs.networking.byEntityId
import com.spartanlabs.networking.nearestEnemy
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

    private fun alive(id: Long, owner: String?, x: Double = 0.0, y: Double = 0.0): AliveSnapshot =
        AliveSnapshot(
            id = EntityId(id),
            actor = ActorSnapshot(
                id = EntityId(id),
                visibleObject = visibleObjectSnapshot(id = id, x = x, y = y, texture = "unit.png"),
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

    @Nested
    @DisplayName("nearestEnemy()")
    inner class NearestEnemy {

        @Test
        fun `picks the closest enemy by world distance`() {
            val attacker = alive(id = 10L, owner = "Player1", x = 0.0, y = 0.0)
            val far = alive(id = 20L, owner = "Player2", x = 10.0, y = 0.0)
            val near = alive(id = 21L, owner = "Player2", x = 3.0, y = 0.0)
            val state: List<DrawableSnapshot> = listOf(attacker, far, near)

            assertSame(near, state.nearestEnemy(attackerId = 10L, playerName = "Player1"))
        }

        @Test
        fun `ignores the player's own units`() {
            val attacker = alive(id = 10L, owner = "Player1", x = 0.0, y = 0.0)
            val ownCloser = alive(id = 11L, owner = "Player1", x = 1.0, y = 0.0)
            val enemy = alive(id = 20L, owner = "Player2", x = 5.0, y = 0.0)
            val state: List<DrawableSnapshot> = listOf(attacker, ownCloser, enemy)

            assertSame(enemy, state.nearestEnemy(attackerId = 10L, playerName = "Player1"))
        }

        @Test
        fun `ignores non-Alive objects even when they are closer`() {
            val attacker = alive(id = 10L, owner = "Player1", x = 0.0, y = 0.0)
            val terrain = visibleObjectSnapshot(id = 30L, x = 1.0, y = 0.0)
            val enemy = alive(id = 20L, owner = "Player2", x = 9.0, y = 0.0)
            val state: List<DrawableSnapshot> = listOf(attacker, terrain, enemy)

            assertSame(enemy, state.nearestEnemy(attackerId = 10L, playerName = "Player1"))
        }

        @Test
        fun `treats an unowned creep as an enemy`() {
            val attacker = alive(id = 10L, owner = "Player1", x = 0.0, y = 0.0)
            val creep = alive(id = 20L, owner = null, x = 2.0, y = 0.0)
            val state: List<DrawableSnapshot> = listOf(attacker, creep)

            assertSame(creep, state.nearestEnemy(attackerId = 10L, playerName = "Player1"))
        }

        @Test
        fun `skips the attacker itself and unidentified objects`() {
            val attacker = alive(id = 10L, owner = "Player1", x = 0.0, y = 0.0)
            val unidentified = alive(id = EntityId.UNASSIGNED.raw, owner = "Player2", x = 1.0, y = 0.0)
            val enemy = alive(id = 20L, owner = "Player2", x = 8.0, y = 0.0)
            val state: List<DrawableSnapshot> = listOf(attacker, unidentified, enemy)

            assertSame(enemy, state.nearestEnemy(attackerId = 10L, playerName = "Player1"))
        }

        @Test
        fun `returns null when the attacker is not in the list`() {
            val state: List<DrawableSnapshot> = listOf(alive(id = 20L, owner = "Player2", x = 3.0, y = 0.0))

            assertNull(state.nearestEnemy(attackerId = 99L, playerName = "Player1"))
        }

        @Test
        fun `returns null when there is no enemy`() {
            val attacker = alive(id = 10L, owner = "Player1", x = 0.0, y = 0.0)
            val own = alive(id = 11L, owner = "Player1", x = 1.0, y = 0.0)
            val state: List<DrawableSnapshot> = listOf(attacker, own)

            assertNull(state.nearestEnemy(attackerId = 10L, playerName = "Player1"))
        }
    }
}
