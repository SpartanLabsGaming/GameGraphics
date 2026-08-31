import com.spartanlabs.networking.ProtocolParsing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ProtocolParsing], the pure message-grammar helpers
 * [com.spartanlabs.networking.NetworkClient] uses to speak the GameTools UDP protocol. No sockets are
 * involved, so these run without any network I/O - this is the part of
 * NetworkClient most worth testing in isolation, since a malformed-message
 * bug here would otherwise only surface against a real running server.
 */
class ProtocolParsingTest {

    @Nested
    @DisplayName("buildHandshakeMessage()")
    inner class BuildHandshakeMessageTests {

        @Test
        fun `formats the name and address with a leading slash`() {
            val message = ProtocolParsing.buildHandshakeMessage("Player1", "192.168.1.5")

            assertEquals("Iam Player1 /192.168.1.5", message)
        }
    }

    @Nested
    @DisplayName("parseTxrxonReply()")
    inner class ParseTxrxonReplyTests {

        @Test
        fun `parses a well-formed reply`() {
            val result = ProtocolParsing.parseTxrxonReply("/127.0.0.1 TXRXON 9997 9996")

            assertTrue(result.isSuccess)
            val ports = result.getOrThrow()
            assertEquals(9997, ports.localListenPort)
            assertEquals(9996, ports.serverCommandPort)
        }

        @Test
        fun `fails when the verb is not TXRXON`() {
            val result = ProtocolParsing.parseTxrxonReply("/127.0.0.1 NOPE 9997 9996")

            assertTrue(result.isFailure)
        }

        @Test
        fun `fails when there are too few tokens`() {
            val result = ProtocolParsing.parseTxrxonReply("/127.0.0.1 TXRXON 9997")

            assertTrue(result.isFailure)
        }

        @Test
        fun `fails when a port is not numeric`() {
            val result = ProtocolParsing.parseTxrxonReply("/127.0.0.1 TXRXON abc 9996")

            assertTrue(result.isFailure)
        }

        @Test
        fun `tolerates surrounding whitespace`() {
            val result = ProtocolParsing.parseTxrxonReply("  /127.0.0.1 TXRXON 9997 9996  \n")

            assertTrue(result.isSuccess)
        }
    }

    @Nested
    @DisplayName("splitVerbAndPayload()")
    inner class SplitVerbAndPayloadTests {

        @Test
        fun `splits a verb with a payload`() {
            val (verb, payload) = ProtocolParsing.splitVerbAndPayload("STATE [1,2,3]")

            assertEquals("STATE", verb)
            assertEquals("[1,2,3]", payload)
        }

        @Test
        fun `a verb with no payload returns an empty payload`() {
            val (verb, payload) = ProtocolParsing.splitVerbAndPayload("PONG")

            assertEquals("PONG", verb)
            assertEquals("", payload)
        }

        @Test
        fun `only the first space separates verb from payload`() {
            val (verb, payload) = ProtocolParsing.splitVerbAndPayload("SET_DEST 0 12.5 -4.0")

            assertEquals("SET_DEST", verb)
            assertEquals("0 12.5 -4.0", payload)
        }
    }
}
