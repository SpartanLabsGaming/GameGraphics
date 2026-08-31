package com.spartanlabs

import com.spartanlabs.audio.SoundPlayer
import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.StatGroupSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.geometry.Square
import com.spartanlabs.graphics.Window
import com.spartanlabs.graphics.ui.Color
import com.spartanlabs.graphics.ui.GameView
import com.spartanlabs.graphics.ui.Label
import com.spartanlabs.graphics.ui.Panel
import com.spartanlabs.graphics.ui.Portrait
import com.spartanlabs.graphics.ui.Scene
import com.spartanlabs.graphics.ui.Stage
import com.spartanlabs.graphics.ui.StatBar
import com.spartanlabs.graphics.ui.Viewport
import com.spartanlabs.graphics.ui.screenRect
import com.spartanlabs.networking.NetworkClient
import com.spartanlabs.networking.drawableCore
import org.lwjgl.glfw.GLFW.glfwGetTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val log: Logger = LoggerFactory.getLogger("Main")

private const val TITLE = "Kotlin LWJGL - Game Client"

private const val MENU_SCENE = "Menu"
private const val GAME_SCENE = "Game"

// The server this client connects to, and the name it hands the server
// during the handshake. Change SERVER_HOST to point at a real server;
// PLAYER_NAME must not contain whitespace (handshake messages are
// whitespace-split on the server side).
private const val SERVER_HOST = "127.0.0.1"
private const val PLAYER_NAME = "Player1"

private const val UPDATES_PER_SECOND = 60.0
private const val UPDATE_INTERVAL = 1.0 / UPDATES_PER_SECOND

// Sound effect played on every right-click that issues a move order (see gameView()).
private const val MOVE_COMMAND_SOUND = "beep-07a.mp3"

/**
 * Entry point. Creates the [Window] and [NetworkClient], builds the UI
 * [Stage] (whose back-most element is a [Viewport] the game is played
 * through), connects to the server, then runs the main loop: pump events,
 * poll the client for the latest world state, and render it. Neither Window
 * nor NetworkClient know about each other — Main is the only place that
 * connects them.
 */
fun main() {
    val client = NetworkClient(SERVER_HOST, PLAYER_NAME)
    val window = Window(TITLE)
    val sounds = SoundPlayer()

    // The borderless window has no close button (Escape is the only exit), so
    // a run is easy to lose track of - and a crash or Ctrl+C / IDE-stop skips
    // the cleanup below. This hook releases the client's fixed UDP port on any
    // exit path, so the next launch can always bind it.
    val releasePort = Thread({ client.stop() }, "release-udp-port")
    Runtime.getRuntime().addShutdownHook(releasePort)

    try {
        window.open()
            .onFailure { cause ->
                log.error("Could not open the window, aborting: {}", cause.message)
                return
            }

        val viewport = Viewport(gameView(client, window, sounds)) // fills the window by default
        val (windowWidth, windowHeight) = window.sizePx()
        window.loadStage(buildStage(viewport, client, windowWidth, windowHeight))
        window.showScene(MENU_SCENE)
            .onFailure { cause -> log.warn("Could not show the menu scene: {}", cause.message) }

        client.start()
            .onSuccess { log.info("Connected to {} as '{}'", SERVER_HOST, PLAYER_NAME) }
            .onFailure { cause -> log.error("Could not connect to {}: {}", SERVER_HOST, cause.message) }

        runLoop(window, client)
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(releasePort) }
        sounds.close()
        client.stop().onFailure { cause -> log.warn("Client did not stop cleanly: {}", cause.message) }
        window.close().onFailure { cause -> log.warn("Window did not close cleanly: {}", cause.message) }
    }
}

/**
 * The [Window]- and [NetworkClient]-backed bridge the game [Viewport] drives.
 * Everything it exposes is in window pixels; the world/camera/protocol
 * translation all happens here so the UI layer stays free of it.
 */
private fun gameView(client: NetworkClient, window: Window, sounds: SoundPlayer): GameView = object : GameView {

    override fun pickActor(xPx: Double, yPx: Double): Int? = window.pick(xPx, yPx)

    override fun moveActor(actorIndex: Int, xPx: Double, yPx: Double) {
        val (worldX, worldY) = window.screenToWorld(xPx, yPx)
        sounds.play(MOVE_COMMAND_SOUND)
        client.setDestination(actorIndex, worldX, worldY)
            .onFailure { cause -> log.warn("Could not move actor {}: {}", actorIndex, cause.message) }
    }

    override fun markLocation(xPx: Double, yPx: Double) = window.addClickMarker(xPx, yPx)

    override fun toggleScene() {
        val next = if (window.currentScene() == MENU_SCENE) GAME_SCENE else MENU_SCENE
        window.showScene(next).onFailure { cause -> log.warn("Could not swap scene: {}", cause.message) }
    }
}

/**
 * Builds the demo [Stage]. Both scenes share the same back-most [viewport]
 * (added first) and the same bottom info panel, so the world, the
 * click-to-command behaviour, and the selected-object read-out are present in
 * each; the "Menu" scene also layers a titled [Panel] of [Label]s, the
 * "Game" scene a HUD label. Middle-click toggles between them (see [Viewport]).
 *
 * A top-level rectangle is a fraction of the window; a panel child's is a
 * fraction of that panel's box. `(0, 0)` is the frame's top-left, `(1, 1)`
 * its bottom-right.
 */
private fun buildStage(
    viewport: Viewport,
    client: NetworkClient,
    windowWidth: Int,
    windowHeight: Int
): Stage {
    // The object the player last left-clicked, resolved live against the newest
    // world state every frame (null once nothing's picked or the object is
    // gone). `selected` is its drawable core, shared by the portrait and the
    // info labels; `selectedHealth` is non-null only when that object is an
    // Alive, which is what gates the health bar.
    val selectedRaw: () -> DrawableSnapshot? = {
        viewport.selectedActor?.let { client.getWorldState().getOrNull(it) }
    }
    val selected: () -> VisibleObjectSnapshot? = { selectedRaw()?.drawableCore() }
    val selectedHealth: () -> StatGroupSnapshot? = { (selectedRaw() as? AliveSnapshot)?.health }

    val info = bottomInfoPanel(selected, selectedHealth, windowWidth, windowHeight) { viewport.selectedActor }

    val menu = Scene().apply {
        add(viewport)
        add(
            Panel(
                position = screenRect(x = 0.03, y = 0.50, width = 0.30, height = 0.30),
                color = Color(20, 24, 40, 220),
                children = listOf(
                    Label(
                        position = screenRect(x = 0.05, y = 0.05, width = 0.90, height = 0.20),
                        color = Color(60, 90, 160, 255),
                        text = "MAIN MENU"
                    ),
                    Label(
                        position = screenRect(x = 0.05, y = 0.32, width = 0.90, height = 0.16),
                        text = "Left click: select an actor"
                    ),
                    Label(
                        position = screenRect(x = 0.05, y = 0.52, width = 0.90, height = 0.16),
                        text = "Right click: move selected actor"
                    ),
                    Label(
                        position = screenRect(x = 0.05, y = 0.72, width = 0.90, height = 0.16),
                        text = "Middle click: toggle scene"
                    )
                )
            )
        )
        add(info)
    }

    val game = Scene().apply {
        add(viewport)
        add(
            Label(
                position = screenRect(x = 0.01, y = 0.02, width = 0.25, height = 0.04),
                color = Color(0, 0, 0, 140),
                text = "GAME - middle click for menu"
            )
        )
        add(info)
    }

    return Stage().apply {
        put(MENU_SCENE, menu)
        put(GAME_SCENE, game)
    }
}

/** The bottom-of-screen inspector panel's box, as a fraction of the window. */
private val INFO_PANEL_RECT = screenRect(x = 0.25, y = 0.85, width = 0.50, height = 0.15)

// The "healthbar" spans the info panel to the right of the portrait. Its width
// is a fixed fraction of the panel; its height fraction is derived from the
// window size (see healthBarRect) so the *rendered* bar stays about this
// aspect - height ~= 0.2 * width - whatever the window's shape.
private const val HEALTH_BAR_X = 0.17
private const val HEALTH_BAR_WIDTH = 0.81
private const val HEALTH_BAR_HEIGHT_OVER_WIDTH = 0.2

/**
 * The "healthbar"'s box within [panel], picked so its rendered pixel height is
 * about [HEALTH_BAR_HEIGHT_OVER_WIDTH] of its rendered pixel width at the given
 * window size, then centred vertically in the panel.
 */
private fun healthBarRect(panel: Square, windowWidth: Int, windowHeight: Int): Square {
    val renderedWidthPx = HEALTH_BAR_WIDTH * panel.dimensions.width * windowWidth
    val renderedHeightPx = HEALTH_BAR_HEIGHT_OVER_WIDTH * renderedWidthPx
    val heightFraction = (renderedHeightPx / (panel.dimensions.height * windowHeight)).coerceIn(0.0, 1.0)
    return screenRect(HEALTH_BAR_X, (1.0 - heightFraction) / 2.0, HEALTH_BAR_WIDTH, heightFraction)
}

/**
 * The bottom-of-screen inspector: a [Portrait] of the selected object on the
 * left, a [StatBar] "healthbar" behind the read-out (shown only while the
 * selection has a health stat), and [Label]s reading out its index, position,
 * size and facing on top. Every child re-reads [selected] / [selectedIndex] /
 * [health] each frame, so the panel updates the instant a new object is
 * clicked and tracks it as it moves.
 */
private fun bottomInfoPanel(
    selected: () -> VisibleObjectSnapshot?,
    health: () -> StatGroupSnapshot?,
    windowWidth: Int,
    windowHeight: Int,
    selectedIndex: () -> Int?
): Panel = Panel(
    position = INFO_PANEL_RECT,
    color = Color(15, 18, 30, 235),
    children = listOf(
        Portrait(
            subject = selected,
            position = screenRect(x = 0.02, y = 0.10, width = 0.13, height = 0.80)
        ),
        StatBar(
            position = healthBarRect(INFO_PANEL_RECT, windowWidth, windowHeight),
            value = { health()?.value ?: 0.0 },
            maxValue = { health()?.maxValue ?: 1.0 },
            visible = { health() != null }
        ),
        infoLabel(row = 0) { selectedIndex()?.let { "Actor #$it" } ?: "Nothing selected" },
        infoLabel(row = 1) { selected()?.let { "Pos   ${fmt(it.gameObject.location.x)}, ${fmt(it.gameObject.location.y)}" } ?: "" },
        infoLabel(row = 2) { selected()?.let { "Size  ${fmt(it.dimensions.width)} x ${fmt(it.dimensions.height)}" } ?: "" },
        infoLabel(row = 3) { selected()?.let { "Angle ${it.angle}${if (it.turns) "" else " (fixed)"}" } ?: "" }
    )
)

/** One line of the info panel's read-out, stacked by [row] (0..3), right of the portrait. */
private fun infoLabel(row: Int, text: () -> String): Label =
    Label(
        position = screenRect(x = 0.18, y = 0.06 + row * 0.24, width = 0.80, height = 0.22),
        textColor = Color(220, 225, 235),
        textSource = text
    )

private fun fmt(value: Double): String = "%.0f".format(value)

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

        window.render(client.getWorldState())
    }
}
