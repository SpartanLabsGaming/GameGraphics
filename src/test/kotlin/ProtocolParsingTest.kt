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
        fun `formats the name after the Iam verb`() {
            val message = ProtocolParsing.buildHandshakeMessage("Player1")

            assertEquals("Iam Player1", message)
        }
    }

    @Nested
    @DisplayName("parseRegisteredReply()")
    inner class ParseRegisteredReplyTests {

        @Test
        fun `succeeds on the bare REGISTERED token`() {
            val result = ProtocolParsing.parseRegisteredReply("REGISTERED")

            assertTrue(result.isSuccess)
        }

        @Test
        fun `fails when the token is not REGISTERED`() {
            val result = ProtocolParsing.parseRegisteredReply("NOPE")

            assertTrue(result.isFailure)
        }

        @Test
        fun `tolerates surrounding whitespace`() {
            val result = ProtocolParsing.parseRegisteredReply("  REGISTERED  \n")

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
            val (verb, payload) = ProtocolParsing.splitVerbAndPayload("COMMAND {\"type\": \"gametools.stop\"}")

            assertEquals("COMMAND", verb)
            assertEquals("{\"type\": \"gametools.stop\"}", payload)
        }
    }
}
