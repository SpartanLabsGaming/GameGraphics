package com.spartanlabs.networking

import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.EntityId

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
