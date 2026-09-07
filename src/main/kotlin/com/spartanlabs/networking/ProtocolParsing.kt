package com.spartanlabs.networking

/**
 * Pure parsing and formatting for the GameTools UDP wire protocol - the
 * `Iam`/`REGISTERED` handshake grammar and the verb-prefixed message framing
 * (`STATE ...`, `PONG`, etc.) used on the shared common channel afterward.
 *
 * Deliberately has no socket, thread, or [com.spartanlabs.networking.NetworkClient] dependency: every
 * function here takes plain strings in and returns plain values or [Result],
 * so the protocol's actual grammar can be unit tested without any network I/O.
 */
internal object ProtocolParsing {

    /** The bare token the server replies with once a handshake is accepted. */
    const val REGISTERED_VERB = "REGISTERED"

    /** The verb that opens a world-state broadcast on the common channel. */
    const val STATE_VERB = "STATE"

    /** The verb the server replies with to a `PING`. */
    const val PONG_VERB = "PONG"

    /** The keepalive token sent on an idle interval to hold the NAT mapping open. */
    const val KEEPALIVE_MESSAGE = "KA"

    /**
     * Builds the `Iam <name>` handshake message this client sends to open a
     * connection. The server replies to the datagram's UDP source, so the
     * client no longer tells it an address (a trailing address token is still
     * accepted but ignored by the server, as of GameTools 2.0.0).
     * @param playerName this client's chosen name; must not contain whitespace
     */
    fun buildHandshakeMessage(playerName: String): String =
        "Iam $playerName"

    /**
     * Validates a handshake reply is the bare `REGISTERED` token.
     * @return [Result.success] if the reply is `REGISTERED` (surrounding
     * whitespace tolerated), or [Result.failure] if it is anything else
     */
    fun parseRegisteredReply(text: String): Result<Unit> = runCatching {
        require(text.trim() == REGISTERED_VERB) {
            "Expected '$REGISTERED_VERB' but got: $text"
        }
    }

    /**
     * Splits a whitespace-delimited protocol message into its leading verb
     * and the remaining payload.
     * @return (verb, payload) - payload is empty if the message has no space
     */
    fun splitVerbAndPayload(text: String): Pair<String, String> {
        val spaceIndex = text.indexOf(' ')
        return if (spaceIndex == -1) text to "" else text.substring(0, spaceIndex) to text.substring(spaceIndex + 1)
    }
}
