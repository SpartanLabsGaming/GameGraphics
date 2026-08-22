import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Listens for quad-position updates from the server over UDP on its own
 * daemon thread, and maintains the latest list of [Quad]s. Has no knowledge
 * of rendering or OpenGL — callers just poll [getQuads] whenever they need
 * the current state.
 *
 * Uses two separate sockets so incoming and outgoing traffic never share a
 * port:
 * - [listenPort]: bound to a fixed, known port, used only to receive updates
 *   from the server (the server needs to know this port to send to it).
 * - The send socket binds to an OS-assigned ephemeral port, used only to
 *   send data to the server (e.g. input, handshake, acknowledgements) via
 *   [send]. It doesn't need a fixed port — only the destination port on the
 *   server matters, which the server reads from each incoming packet. This
 *   also means the send socket never conflicts with anything, including a
 *   server bound to the same port number on the same machine during local
 *   testing.
 *
 * Each incoming UDP datagram is expected to contain one JSON-encoded
 * [QuadUpdatePacket]. UDP is a good fit for this kind of frequent,
 * latency-sensitive position data: a dropped or out-of-order packet just
 * means one stale read, not a stall, and the next packet corrects it
 * immediately.
 */
class NetworkClient(private val listenPort: Int = 9999) {
    private val json = Json { ignoreUnknownKeys = true }

    // Latest snapshot of quad positions received from the server.
    // The network thread publishes a new immutable list here; any thread
    // can safely read via getQuads(), no locking needed on either side.
    private val quads = AtomicReference<List<Quad>>(emptyList())

    private var receiveSocket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Opens both UDP sockets and starts listening on a background thread. */
    fun start() {
        val recvSock = DatagramSocket(listenPort)
        recvSock.soTimeout = 1000 // periodically wake up so stop() is responsive
        receiveSocket = recvSock

        // No port specified -> OS assigns a free ephemeral port.
        sendSocket = DatagramSocket()

        running = true

        thread = Thread(::receiveLoop, "udp-network-client").apply {
            isDaemon = true
            start()
        }
    }

    /** Returns the most recently received list of quads. Safe to call from any thread. */
    fun getQuads(): List<Quad> = quads.get()

    /** Sends raw bytes to the server, from the dedicated send socket/port. */
    fun send(data: ByteArray, serverAddress: InetAddress, serverPort: Int) {
        val packet = DatagramPacket(data, data.size, serverAddress, serverPort)
        sendSocket?.send(packet)
    }

    /** Stops the background thread and closes both sockets. */
    fun stop() {
        running = false
        thread?.join(1500)
        receiveSocket?.close()
        sendSocket?.close()
    }

    private fun receiveLoop() {
        // Max theoretical UDP payload size; plenty for a batch of quad updates.
        val buffer = ByteArray(65_507)

        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                receiveSocket?.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue // just a wakeup to re-check `running`
            } catch (e: Exception) {
                if (running) System.err.println("UDP receive error: ${e.message}")
                continue
            }

            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            try {
                val update = json.decodeFromString<QuadUpdatePacket>(text)
                quads.set(update.quads)
            } catch (e: Exception) {
                System.err.println("Failed to parse quad update: ${e.message}")
            }
        }
    }
}