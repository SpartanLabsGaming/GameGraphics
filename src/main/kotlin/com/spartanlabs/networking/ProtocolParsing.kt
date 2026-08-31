package com.spartanlabs.networking

/**
 * The dedicated per-connection ports learned from the server's `TXRXON`
 * handshake reply.
 * @property localListenPort the local port this client must listen on for
 * `STATE`/`PONG` traffic
 * @property serverCommandPort the server's port outgoing commands must be sent to
 */
internal data class DedicatedChannelPorts(val localListenPort: Int, val serverCommandPort: Int)

/**
 * Pure parsing and formatting for the GameTools UDP wire protocol - the
 * `Iam`/`TXRXON` handshake grammar and the verb-prefixed message framing
 * (`STATE ...`, `PONG`, etc.) used on the dedicated channel afterward.
 *
 * Deliberately has no socket, thread, or [com.spartanlabs.networking.NetworkClient] dependency: every
 * function here takes plain strings in and returns plain values or [Result],
 * so the protocol's actual grammar can be unit tested without any network I/O.
 */
internal object ProtocolParsing {

    /** The verb a handshake reply must start with, after the echoed address token. */
    const val TXRXON_VERB = "TXRXON"

    /** The verb that opens a world-state broadcast on the dedicated channel. */
    const val STATE_VERB = "STATE"

    /** The verb the server replies with to a `PING`. */
    const val PONG_VERB = "PONG"

    /**
     * Builds the `Iam <name> <address>` handshake message this client sends
     * to open a connection, in the `/<ip>` form the server's parser expects.
     * @param playerName this client's chosen name; must not contain whitespace
     * @param localAddressHost this client's own reachable IP address (no leading slash)
     */
    fun buildHandshakeMessage(playerName: String, localAddressHost: String): String =
        "Iam $playerName /$localAddressHost"

    /**
     * Parses a handshake reply of the form `<address> TXRXON <sendPort> <receivePort>`.
     * @return the parsed [DedicatedChannelPorts], or [Result.failure] if the
     * reply is malformed (wrong verb, too few tokens, or non-numeric ports)
     */
    fun parseTxrxonReply(text: String): Result<DedicatedChannelPorts> = runCatching {
        val tokens = text.trim().split(' ')
        require(tokens.size >= 4 && tokens[1] == TXRXON_VERB) {
            "Expected '<address> $TXRXON_VERB <sendPort> <receivePort>' but got: $text"
        }
        DedicatedChannelPorts(localListenPort = tokens[2].toInt(), serverCommandPort = tokens[3].toInt())
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
