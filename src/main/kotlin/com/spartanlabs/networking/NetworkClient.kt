package com.spartanlabs.networking

import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.webtools.MultiConnectionUDPServer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Shared slf4j logger for all [NetworkClient] instances. */
private val log: Logger = LoggerFactory.getLogger(NetworkClient::class.java)

/**
 * A UDP client for the GameTools [MultiConnectionUDPServer] protocol, speaking
 * the same handshake and message grammar this specific game server expects.
 *
 * Connecting and all subsequent traffic share a single socket:
 * 1. **Handshake**: sends `Iam <name>` to the server's well-known
 *    [MultiConnectionUDPServer.COMMON_LISTEN_PORT] from a socket kept open for
 *    the lifetime of the connection. The server (GameTools 3.0.0+) replies
 *    straight back to that datagram's UDP source with the bare token
 *    `REGISTERED`.
 * 2. **Shared channel**: from then on every datagram - `STATE <json>` world
 *    broadcasts, `PONG` replies, and outgoing traffic (`PING`, and the
 *    `COMMAND <json>` orders built by [ClientCommands]) - travels over that same
 *    socket to/from the server's
 *    [MultiConnectionUDPServer.COMMON_LISTEN_PORT]; there is no
 *    per-player dedicated port pair. This client sends a bare `KA` keepalive
 *    on an idle interval to hold its NAT mapping open, as the server expects.
 *
 * The parsing/formatting of every message on the wire is delegated to
 * [ProtocolParsing], which has no socket dependency of its own - this class
 * is purely the I/O orchestration (which socket, which thread, when) around
 * that pure grammar.
 *
 * `STATE`'s JSON payload is a polymorphic array of [DrawableSnapshot] (as of
 * GameTools 1.6.0): each entry is a plain [VisibleObjectSnapshot], an
 * `ActorSnapshot`, or an `AliveSnapshot`, tagged with a `type` field. These
 * are the same classes the server broadcasts with, imported directly from
 * GameTools rather than re-declared here, so the wire shape can't drift out
 * of sync with the library. [getWorldState] hands back the list as decoded,
 * so a caller that wants the extra state (an `AliveSnapshot`'s health, say)
 * can pattern-match it; callers that only draw use [drawableCore].
 *
 * @property serverHost address or hostname of the server to connect to
 * @property playerName the name this client hands the server during the
 * handshake; must not contain whitespace, since handshake messages are
 * whitespace-split
 */
class NetworkClient(
    private val serverHost: String,
    private val playerName: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Latest parsed world-state snapshot. Written by the listener thread, read by anyone. */
    private val worldState = AtomicReference<List<DrawableSnapshot>>(emptyList())

    // The single socket used for the handshake and, afterward, for every
    // STATE/PONG/command/KA datagram to and from the server's common port.
    private var socket: DatagramSocket? = null

    @Volatile private var running = false
    private var listenerThread: Thread? = null
    private var keepaliveThread: Thread? = null

    /**
     * Performs the handshake and, once it succeeds, starts background threads
     * that listen for world-state broadcasts and send periodic keepalives on
     * the shared channel. This call blocks the calling thread for up to a few
     * seconds while it waits for the server's handshake reply.
     * @return [Result.success] once connected and listening, or the failure
     * that prevented it (e.g. the server never replied)
     */
    fun start(): Result<Unit> =
        handshake().flatMap { openSharedChannel() }

    /**
     * The most recently received world state, as decoded off the wire (each
     * entry a plain [VisibleObjectSnapshot], an `ActorSnapshot`, or an
     * `AliveSnapshot`). Safe to call from any thread.
     */
    fun getWorldState(): List<DrawableSnapshot> = worldState.get()

    /**
     * Asks the server to move the unit with entity id [entityId] toward
     * ([x], [y]) (world coordinates) and settle there.
     */
    fun moveTo(entityId: Long, x: Double, y: Double): Result<Unit> =
        sendCommand(ClientCommands.moveTo(entityId, x, y))

    /** Asks the server to halt the unit with entity id [entityId] where it is. */
    fun stop(entityId: Long): Result<Unit> =
        sendCommand(ClientCommands.stop(entityId))

    /**
     * Asks the server to have this client's unit [attackerId] attack unit
     * [targetId]. Both name objects by the stable
     * [com.spartanlabs.gaming.gameobjects.DrawableSnapshot.id] carried on every
     * `STATE` entry - the server resolves them with `World.byId`, so the order
     * stays bound to the unit the player meant even as the broadcast list
     * reorders. The server issues the attack only if the attacker is one of this
     * client's units and the target is an attackable actor it does not own; an
     * ignored request produces no reply.
     */
    fun attack(attackerId: Long, targetId: Long): Result<Unit> =
        sendCommand(ClientCommands.attack(attackerId, targetId))

    /** Sends a `PING`; a `PONG` reply (if any) arrives asynchronously on the shared channel. */
    fun ping(): Result<Unit> = sendCommand("PING")

    /**
     * Stops the listener and keepalive threads and closes the socket. Every
     * step runs even if an earlier one failed, so a partial failure never
     * leaks a bound port.
     * @return [Result.success] if every step succeeded, or the first failure encountered
     */
    fun stop(): Result<Unit> {
        log.info("Stopping network client")
        running = false

        val threadsJoined = runCatching {
            listenerThread?.join(LISTENER_JOIN_TIMEOUT_MILLIS)
            keepaliveThread?.join(LISTENER_JOIN_TIMEOUT_MILLIS)
        }
            .map { }
            .onFailure { cause ->
                if (cause is InterruptedException) Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for the background threads to stop")
            }

        val socketClosed = runCatching { socket?.close() }
            .map { }
            .onFailure { cause -> log.error("Could not close the network client's socket", cause) }

        return threadsJoined.flatMap { socketClosed }
    }

    // -----------------------------------------------------------------
    // Handshake
    // -----------------------------------------------------------------

    /**
     * Sends the `Iam` handshake and blocks (up to [HANDSHAKE_TIMEOUT_MILLIS])
     * for the server's `REGISTERED` reply.
     */
    private fun handshake(): Result<Unit> = runCatching {
        val socket = DatagramSocket() // ephemeral local port, kept open for the whole connection
        socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS.toInt()
        this.socket = socket

        val handshakeMessage = ProtocolParsing.buildHandshakeMessage(playerName)
        log.debug("Sending handshake: {}", handshakeMessage)
        val payload = handshakeMessage.toByteArray(Charsets.UTF_8)
        socket.send(
            DatagramPacket(
                payload, payload.size,
                InetAddress.getByName(serverHost), MultiConnectionUDPServer.COMMON_LISTEN_PORT
            )
        )

        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        val reply = DatagramPacket(buffer, buffer.size)
        socket.receive(reply) // throws SocketTimeoutException if the server never answers

        val text = String(reply.data, reply.offset, reply.length, Charsets.UTF_8).trim()
        log.debug("Received handshake reply: {}", text)

        ProtocolParsing.parseRegisteredReply(text).getOrThrow()
    }
        .onSuccess { log.info("Handshake complete") }
        .onFailure { cause ->
            // Release the handshake socket now instead of holding it (idle, on a
            // dead client) until stop().
            socket?.close()
            socket = null
            log.error("Handshake with {} failed", serverHost, cause)
        }

    /** Starts the background listener and keepalive threads on the shared channel. */
    private fun openSharedChannel(): Result<Unit> = runCatching {
        requireNotNull(socket) { "Handshake did not open a socket" }
            .soTimeout = LISTENER_WAKE_INTERVAL_MILLIS.toInt()
        running = true

        listenerThread = Thread(::receiveLoop, "udp-shared-listener").apply {
            isDaemon = true
            start()
        }
        keepaliveThread = Thread(::keepaliveLoop, "udp-keepalive").apply {
            isDaemon = true
            start()
        }
    }.onFailure { cause ->
        running = false
        socket?.close()
        socket = null
        log.error("Could not start listening on the shared channel", cause)
    }

    // -----------------------------------------------------------------
    // Shared channel: send + receive
    // -----------------------------------------------------------------

    /** Sends a raw text command to the server on the shared channel. */
    private fun sendCommand(command: String): Result<Unit> = runCatching {
        val socket = requireNotNull(socket) { "Not connected yet - call start() and check its Result first" }
        log.trace("Sending command: {}", command)
        val payload = command.toByteArray(Charsets.UTF_8)
        socket.send(
            DatagramPacket(
                payload, payload.size,
                InetAddress.getByName(serverHost), MultiConnectionUDPServer.COMMON_LISTEN_PORT
            )
        )
    }.onFailure { cause -> log.error("Could not send command '{}'", command, cause) }

    private fun receiveLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket?.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue // just a wakeup to re-check `running`
            } catch (e: Exception) {
                if (running) log.warn("UDP receive error: {}", e.message, e)
                continue
            }

            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
            handleSharedChannelMessage(text)
        }
    }

    /** Sends a `KA` keepalive on [KEEPALIVE_INTERVAL_MILLIS], to hold the NAT mapping open. */
    private fun keepaliveLoop() {
        while (running) {
            Thread.sleep(KEEPALIVE_INTERVAL_MILLIS)
            if (running) sendCommand(ProtocolParsing.KEEPALIVE_MESSAGE)
        }
    }

    private fun handleSharedChannelMessage(text: String) {
        val (verb, payload) = ProtocolParsing.splitVerbAndPayload(text)

        when (verb) {
            ProtocolParsing.STATE_VERB ->
                runCatching { json.decodeFromString<List<DrawableSnapshot>>(payload) }
                    .onSuccess { snapshots -> worldState.set(snapshots) }
                    .onFailure { cause -> log.warn("Failed to parse STATE payload: {}", cause.message) }

            ProtocolParsing.PONG_VERB -> log.debug("Received PONG")

            else -> log.trace("Ignoring unrecognised message: {}", text)
        }
    }

    private companion object {
        const val RECEIVE_BUFFER_BYTES = 65_507
        const val HANDSHAKE_TIMEOUT_MILLIS = 5000L
        const val LISTENER_WAKE_INTERVAL_MILLIS = 1000L
        const val LISTENER_JOIN_TIMEOUT_MILLIS = 1500L

        /** Server recommends ~20s; kept comfortably under that. */
        const val KEEPALIVE_INTERVAL_MILLIS = 15_000L
    }
}

/**
 * Chains a [Result]-returning [transform] onto this result, short-circuiting on failure.
 * Reproduced locally to avoid depending on GameTools' internal (non-exported) copy.
 */
private inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    fold(onSuccess = transform, onFailure = { Result.failure(it) })
