package com.spartanlabs.networking

import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.webtools.MultiConnectionUDPServer
import com.spartanlabs.webtools.resolveLocalAddress
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.BindException
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
 * Connecting is a two-phase process:
 * 1. **Handshake**: sends `Iam <name> <address>` to the server's well-known
 *    [MultiConnectionUDPServer.COMMON_LISTEN_PORT], from a socket already
 *    bound to [MultiConnectionUDPServer.COMMON_SEND_PORT] so the server's
 *    reply can be received on that same socket. The server replies with
 *    `<address> TXRXON <sendPort> <receivePort>`.
 * 2. **Dedicated channel**: `TXRXON`'s `sendPort` is the local port this
 *    client must listen on for `STATE <json>` world broadcasts and `PONG`
 *    replies; `receivePort` is the server's port outgoing commands
 *    (`PING`, `SET_DEST`, `SET_SPEED`, `STOP`) must be sent to. A second
 *    socket is opened on the assigned local port for all of this.
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
 * of sync with the library. Each entry is immediately reduced to its drawable
 * core (see [drawableCore]) since this client renders nothing beyond that.
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
    private val worldState = AtomicReference<List<VisibleObjectSnapshot>>(emptyList())

    // Bound to MultiConnectionUDPServer.COMMON_SEND_PORT; used only during
    // the handshake to send "Iam ..." and receive the "TXRXON ..." reply.
    private var commonSocket: DatagramSocket? = null

    // Bound to the dedicated port the server assigned this connection; used
    // for all traffic once the handshake completes.
    private var dedicatedSocket: DatagramSocket? = null

    // The server's dedicated receive port for this connection - i.e. where
    // outgoing commands (SET_DEST, PING, ...) must be sent. Learned from the
    // TXRXON reply; null until the handshake completes.
    @Volatile private var serverDedicatedPort: Int? = null

    @Volatile private var running = false
    private var listenerThread: Thread? = null

    /**
     * Performs the handshake and, once it succeeds, starts a background
     * thread that listens for world-state broadcasts on the dedicated channel.
     * This call blocks the calling thread for up to a few seconds while it
     * waits for the server's handshake reply.
     * @return [Result.success] once connected and listening, or the failure
     * that prevented it (e.g. the server never replied)
     */
    fun start(): Result<Unit> =
        handshake().flatMap { ports -> openDedicatedChannel(ports) }

    /** Returns the most recently received world state. Safe to call from any thread. */
    fun getWorldState(): List<VisibleObjectSnapshot> = worldState.get()

    /** Asks the server to move actor [index] toward ([x], [y]). */
    fun setDestination(index: Int, x: Double, y: Double): Result<Unit> =
        sendCommand("SET_DEST $index $x $y")

    /** Asks the server to change actor [index]'s base speed. */
    fun setSpeed(index: Int, speed: Double): Result<Unit> =
        sendCommand("SET_SPEED $index $speed")

    /** Asks the server to stop actor [index] where it currently is. */
    fun stopActor(index: Int): Result<Unit> =
        sendCommand("STOP $index")

    /** Sends a `PING`; a `PONG` reply (if any) arrives asynchronously on the dedicated channel. */
    fun ping(): Result<Unit> = sendCommand("PING")

    /**
     * Stops the listener thread and closes both sockets. Every step runs
     * even if an earlier one failed, so a partial failure never leaks a
     * bound port.
     * @return [Result.success] if every step succeeded, or the first failure encountered
     */
    fun stop(): Result<Unit> {
        log.info("Stopping network client")
        running = false

        val listenerJoined = runCatching { listenerThread?.join(LISTENER_JOIN_TIMEOUT_MILLIS) }
            .map { }
            .onFailure { cause ->
                if (cause is InterruptedException) Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for the listener thread to stop")
            }

        val socketsClosed = runCatching {
            commonSocket?.close()
            dedicatedSocket?.close() ?: Unit
        }.onFailure { cause -> log.error("Could not close the network client's sockets", cause) }

        return listenerJoined.flatMap { socketsClosed }
    }

    // -----------------------------------------------------------------
    // Handshake
    // -----------------------------------------------------------------

    /**
     * Sends the `Iam` handshake and blocks (up to [HANDSHAKE_TIMEOUT_MILLIS])
     * for the server's `TXRXON` reply.
     */
    private fun handshake(): Result<DedicatedChannelPorts> = runCatching {
        val localAddress = resolveLocalAddress().getOrDefault(InetAddress.getLoopbackAddress())
        log.info("Resolved local address as {}", localAddress)

        val socket = DatagramSocket(MultiConnectionUDPServer.COMMON_SEND_PORT)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS.toInt()
        commonSocket = socket

        val handshakeMessage = ProtocolParsing.buildHandshakeMessage(playerName, localAddress.hostAddress)
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

        ProtocolParsing.parseTxrxonReply(text).getOrThrow()
    }
        .onSuccess { ports -> log.info("Handshake complete: {}", ports) }
        .onFailure { cause ->
            // Release COMMON_SEND_PORT now instead of holding it (idle, on a
            // dead client) until stop() - otherwise the next launch, or a
            // retry, fails to bind it.
            commonSocket?.close()
            commonSocket = null

            if (cause is BindException) {
                log.error(
                    "UDP port {} is already in use - another game client is probably still running (close it with Escape, or kill the process)",
                    MultiConnectionUDPServer.COMMON_SEND_PORT, cause
                )
            } else {
                log.error("Handshake with {} failed", serverHost, cause)
            }
        }

    /** Opens the dedicated channel and starts the background listener thread. */
    private fun openDedicatedChannel(ports: DedicatedChannelPorts): Result<Unit> = runCatching {
        val socket = DatagramSocket(ports.localListenPort)
        socket.soTimeout = LISTENER_WAKE_INTERVAL_MILLIS.toInt()
        dedicatedSocket = socket
        serverDedicatedPort = ports.serverCommandPort
        running = true

        listenerThread = Thread(::receiveLoop, "udp-dedicated-listener").apply {
            isDaemon = true
            start()
        }
    }.onFailure { cause ->
        running = false
        dedicatedSocket?.close()
        dedicatedSocket = null
        log.error("Could not open the dedicated channel on port {}", ports.localListenPort, cause)
    }

    // -----------------------------------------------------------------
    // Dedicated channel: send + receive
    // -----------------------------------------------------------------

    /** Sends a raw text command to the server on the dedicated channel. */
    private fun sendCommand(command: String): Result<Unit> = runCatching {
        val port = requireNotNull(serverDedicatedPort) { "Not connected yet - call start() and check its Result first" }
        val socket = requireNotNull(dedicatedSocket) { "Not connected yet - call start() and check its Result first" }
        log.trace("Sending command: {}", command)
        val payload = command.toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(serverHost), port))
    }.onFailure { cause -> log.error("Could not send command '{}'", command, cause) }

    private fun receiveLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                dedicatedSocket?.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue // just a wakeup to re-check `running`
            } catch (e: Exception) {
                if (running) log.warn("UDP receive error: {}", e.message, e)
                continue
            }

            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
            handleDedicatedMessage(text)
        }
    }

    private fun handleDedicatedMessage(text: String) {
        val (verb, payload) = ProtocolParsing.splitVerbAndPayload(text)

        when (verb) {
            ProtocolParsing.STATE_VERB ->
                runCatching { json.decodeFromString<List<DrawableSnapshot>>(payload) }
                    .onSuccess { snapshots -> worldState.set(snapshots.map { it.drawableCore() }) }
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
    }
}

/**
 * Chains a [Result]-returning [transform] onto this result, short-circuiting on failure.
 * Reproduced locally to avoid depending on GameTools' internal (non-exported) copy.
 */
private inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    fold(onSuccess = transform, onFailure = { Result.failure(it) })
