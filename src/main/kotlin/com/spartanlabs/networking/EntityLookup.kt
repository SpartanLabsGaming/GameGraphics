package com.spartanlabs.networking

import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.EntityId
import kotlin.math.hypot

/**
 * Resolving a `STATE` list by the stable entity id GameTools stamps on every
 * [DrawableSnapshot] ([DrawableSnapshot.id]), instead of by list position - the
 * server reorders the list as units die and spawn, so an index captured one
 * frame points at a different unit the next. Commands name their unit by this
 * id directly now (see [ClientCommands]); this is what the selection inspector
 * uses to stay locked to one unit as it moves.
 *
 * These helpers speak the bare [EntityId.raw] `Long` (that is also how the id
 * travels on the wire, per GameTools 5.0.0's [EntityId] serializer), so callers
 * never touch the wrapper type. [EntityId.UNASSIGNED]'s raw value (`0`) is not a
 * real id - it means the object had no `World`-assigned identity at snapshot
 * time (an unowned object, or a server older than 3.1) - so every lookup here
 * treats it as "no match".
 */

/** The `STATE` entry with this entity [id], or `null` if none has it (including for the unassigned `0`). */
internal fun List<DrawableSnapshot>.byEntityId(id: Long): DrawableSnapshot? =
    if (id == EntityId.UNASSIGNED.raw) null
    else firstOrNull { it.id.raw == id }

/**
 * The attackable enemy [AliveSnapshot] a right-click resolved to, or `null`
 * when the pick is not a valid attack target: the attacker itself, an
 * unidentified object, terrain or a plain actor (not an [AliveSnapshot]), one
 * of [playerName]'s own units, or an id no longer in the list.
 *
 * @param attackerId the selected unit's entity id
 * @param targetId the entity id under the cursor (from picking)
 * @param playerName this client's name, as it appears in [AliveSnapshot.ownerName]
 */
internal fun List<DrawableSnapshot>.attackTarget(
    attackerId: Long,
    targetId: Long,
    playerName: String
): AliveSnapshot? {
    if (targetId == attackerId || targetId == EntityId.UNASSIGNED.raw) return null
    val target = byEntityId(targetId) as? AliveSnapshot ?: return null
    return target.takeIf { it.ownerName != playerName }
}

/**
 * The attackable enemy [AliveSnapshot] closest to the unit with entity id
 * [attackerId], or `null` when there is none - the fallback target for a forced
 * attack click (see [com.spartanlabs.graphics.ui.ClickState.ATTACK]) that landed
 * on empty ground.
 *
 * "Enemy" is any [AliveSnapshot] whose [AliveSnapshot.ownerName] is not
 * [playerName] (so other players' units and unowned creeps both count), other
 * than the attacker itself and any unidentified object. Distance is measured in
 * world coordinates between the drawable cores. Returns `null` if [attackerId]
 * is not in the list.
 *
 * @param attackerId the ordering unit's entity id
 * @param playerName this client's name, as it appears in [AliveSnapshot.ownerName]
 */
internal fun List<DrawableSnapshot>.nearestEnemy(attackerId: Long, playerName: String): AliveSnapshot? {
    val origin = byEntityId(attackerId)?.drawableCore()?.gameObject?.location ?: return null
    return asSequence()
        .filterIsInstance<AliveSnapshot>()
        .filter { it.id.raw != attackerId && it.id.raw != EntityId.UNASSIGNED.raw && it.ownerName != playerName }
        .minByOrNull { enemy ->
            val here = enemy.drawableCore().gameObject.location
            hypot(here.x - origin.x, here.y - origin.y)
        }
}
