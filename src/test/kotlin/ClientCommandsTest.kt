import com.spartanlabs.gaming.gameobjects.EntityId
import com.spartanlabs.gaming.networking.command.Attack
import com.spartanlabs.gaming.networking.command.ClientCommandCodec
import com.spartanlabs.gaming.networking.command.Follow
import com.spartanlabs.gaming.networking.command.MoveTo
import com.spartanlabs.gaming.networking.command.Stop
import com.spartanlabs.networking.ClientCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The client's `COMMAND` datagrams (`com.spartanlabs.networking.ClientCommands`)
 * must be exactly what a server-side [ClientCommandCodec] - built the same way,
 * from the six standard commands with no application module - decodes back.
 * Pure string-in / value-out; no socket.
 */
class ClientCommandsTest {

    /** Stands in for the server's codec: same construction, independent instance. */
    private val serverCodec = ClientCommandCodec()

    private fun decodeAsServer(datagram: String) =
        serverCodec.decode(datagram.removePrefix("COMMAND ")).getOrThrow()

    @Test
    fun `moveTo is a COMMAND datagram the server reads back as the same MoveTo`() {
        val datagram = ClientCommands.moveTo(entityId = 7L, x = 120.0, y = -40.0)

        assertTrue(datagram.startsWith("COMMAND "), datagram)
        assertEquals(MoveTo(EntityId(7L), 120.0, -40.0), decodeAsServer(datagram))
    }

    @Test
    fun `stop round-trips to a Stop naming the same unit`() {
        assertEquals(Stop(EntityId(3L)), decodeAsServer(ClientCommands.stop(entityId = 3L)))
    }

    @Test
    fun `follow round-trips to a Follow with follower and target preserved`() {
        assertEquals(
            Follow(EntityId(4L), EntityId(8L)),
            decodeAsServer(ClientCommands.follow(followerId = 4L, targetId = 8L))
        )
    }

    @Test
    fun `attack round-trips to an Attack with attacker and target preserved`() {
        assertEquals(
            Attack(EntityId(7L), EntityId(13L)),
            decodeAsServer(ClientCommands.attack(attackerId = 7L, targetId = 13L))
        )
    }

    @Test
    fun `an entity id rides the wire as a bare number, not a wrapper object`() {
        val payload = ClientCommands.moveTo(entityId = 7L, x = 0.0, y = 0.0).filterNot { it.isWhitespace() }

        assertTrue("\"actor\":7" in payload, payload)
    }
}
