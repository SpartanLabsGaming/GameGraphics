import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.lwjgl.glfw.GLFW.glfwGetTime
import java.net.InetAddress


private const val TITLE = "Kotlin LWJGL - Textured Quad"
private const val TEXTURE_PATH = "res/natures prophet.jpg"
private const val LISTEN_PORT = 9999

// Where the server listens for input from this client.
// The client's own outgoing socket uses an OS-assigned ephemeral port
// (see NetworkClient), so only this destination port needs to be fixed.
private const val SERVER_HOST = "127.0.0.1"
private const val SERVER_INPUT_PORT = 9998

private const val UPDATES_PER_SECOND = 60.0
private const val UPDATE_INTERVAL = 1.0 / UPDATES_PER_SECOND

private val json = Json
private val serverAddress = InetAddress.getByName(SERVER_HOST)

/**
 * Entry point. Creates the [Window] and [NetworkClient], then runs the main
 * loop: pump events, poll the client for the latest quad positions, and hand
 * them to the window to render. Neither Window nor NetworkClient know about
 * each other — Main is the only place that connects them.
 */
fun main() {
    val client = NetworkClient(listenPort = LISTEN_PORT)

    val window = Window(TITLE, TEXTURE_PATH, onMouseAction = { action ->
        onMouseAction(action, client)
    })

    window.open()
    client.start()

    runLoop(window, client)

    client.stop()
    window.close()
}

/**
 * Called by Window whenever the mouse moves or a button is pressed/released.
 * Serializes the event to JSON and forwards it to the server over UDP.
 */
private fun onMouseAction(action: MouseAction, client: NetworkClient) {
    val payload = json.encodeToString(action)
    client.send(payload.toByteArray(Charsets.UTF_8), serverAddress, SERVER_INPUT_PORT)
}

private fun runLoop(window: Window, client: NetworkClient) {
    var previousTime = glfwGetTime()
    var accumulator = 0.0

    while (!window.shouldClose()) {
        val currentTime = glfwGetTime()
        var frameTime = currentTime - previousTime
        previousTime = currentTime

        // Avoid a huge catch-up burst if the loop stalls (e.g. window drag)
        if (frameTime > 0.25) frameTime = 0.25
        accumulator += frameTime

        window.pollEvents()

        // Fixed 60Hz tick, reserved for future client-side logic
        // (input handling, interpolation, prediction, etc).
        while (accumulator >= UPDATE_INTERVAL) {
            accumulator -= UPDATE_INTERVAL
        }

        window.render(client.getQuads())
    }
}