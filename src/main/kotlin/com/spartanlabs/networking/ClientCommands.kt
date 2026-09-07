package com.spartanlabs.networking

import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.gaming.networking.command.Attack
import com.spartanlabs.gaming.networking.command.ClientCommandCodec
import com.spartanlabs.gaming.networking.command.MoveTo
import com.spartanlabs.gaming.networking.command.Stop

/**
 * Formats this client's orders as the `COMMAND <json>` datagrams GameTools
 * 5.0.0 defines ([ClientCommandCodec]), keeping [NetworkClient] pure socket I/O
 * the same way [ProtocolParsing] keeps the handshake/framing grammar out of it.
 *
 * Every order names its unit by the stable [EntityId] the server stamps on each
 * `STATE` entry, taken here as the bare `Long` the rest of the client passes
 * around ([EntityLookup]) and wrapped at the boundary. The server decodes with a
 * [ClientCommandCodec] built the same way - the six standard `gametools.*`
 * commands, no application module - so the two ends agree with no shared schema
 * file. Only [MoveTo], [Stop] and [Attack] are emitted; the client has no UI
 * for [com.spartanlabs.gaming.networking.command.Follow],
 * [com.spartanlabs.gaming.networking.command.MoveDir] or
 * [com.spartanlabs.gaming.networking.command.StopAttack] yet.
 *
 * `PING` stays a raw token (see [NetworkClient.ping]) - it never became a
 * `ClientCommand`.
 */
internal object ClientCommands {

    private val codec = ClientCommandCodec()

    /** `COMMAND` order: send unit [entityId] to world point ([x], [y]) and stop there. */
    fun moveTo(entityId: Long, x: Double, y: Double): String =
        codec.encode(MoveTo(EntityId(entityId), x, y))

    /** `COMMAND` order: halt unit [entityId] where it currently is. */
    fun stop(entityId: Long): String =
        codec.encode(Stop(EntityId(entityId)))

    /** `COMMAND` order: have owned unit [attackerId] attack unit [targetId]. */
    fun attack(attackerId: Long, targetId: Long): String =
        codec.encode(Attack(EntityId(attackerId), EntityId(targetId)))
}
