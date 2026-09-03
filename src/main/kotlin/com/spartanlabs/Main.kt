package com.spartanlabs

import com.spartanlabs.audio.SoundPlayer
import com.spartanlabs.gaming.gameobjects.AliveSnapshot
import com.spartanlabs.gaming.gameobjects.DrawableSnapshot
import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.geometry.Square
import com.spartanlabs.graphics.Window
import com.spartanlabs.graphics.ui.Button
import com.spartanlabs.graphics.ui.ButtonListener
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
import org.lwjgl.glfw.GLFW.GLFW_KEY_B
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

// Sound effect played on a right-click that issues an attack order. Shares the
// move sound's file for now; swap in its own once an attack cue is recorded.
private const val ATTACK_COMMAND_SOUND = MOVE_COMMAND_SOUND

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
        window.loadStage(buildStage(viewport, client, sounds, windowWidth, windowHeight))
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

    override fun attack(attackerIndex: Int, xPx: Double, yPx: Double): Boolean {
        val targetIndex = window.pick(xPx, yPx) ?: return false
        if (targetIndex == attackerIndex) return false
        // Only an Alive can be attacked; the target's ownerName is what tells an
        // enemy unit from one of this client's own (which we treat as a move, not
        // an attack). The picked index resolves against the same list getWorldState
        // returns - see Window.pick / Window.render.
        val target = client.getWorldState().getOrNull(targetIndex) as? AliveSnapshot ?: return false
        if (target.ownerName == PLAYER_NAME) return false

        sounds.play(ATTACK_COMMAND_SOUND)
        client.attack(attackerIndex, targetIndex)
            .onFailure { cause -> log.warn("Could not attack actor {}: {}", targetIndex, cause.message) }
        return true
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
    sounds: SoundPlayer,
    windowWidth: Int,
    windowHeight: Int
): Stage {
    // The object the player last left-clicked, resolved live against the newest
    // world state every frame (null once nothing's picked or the object is
    // gone). `selected` is its drawable core, shared by the portrait and the
    // header label; `selectedAlive` is non-null only when that object is an
    // Alive, which is what gates the health bar and fills the stats panel.
    val selectedRaw: () -> DrawableSnapshot? = {
        viewport.selectedActor?.let { client.getWorldState().getOrNull(it) }
    }
    val selected: () -> VisibleObjectSnapshot? = { selectedRaw()?.drawableCore() }
    val selectedAlive: () -> AliveSnapshot? = { selectedRaw() as? AliveSnapshot }

    val info = bottomInfoPanel(selected, selectedAlive, sounds, windowWidth, windowHeight) { viewport.selectedActor }

    // A demo Button: highlights on hover, turns green while held (by the mouse
    // or by its assigned "B" key), and beeps on every activation.
    val beepButton = Button(
        name = "BEEP (B)",
        listener = beepButtonListener(sounds),
        position = screenRect(x = 0.03, y = 0.82, width = 0.12, height = 0.045),
        key = GLFW_KEY_B
    )

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
                        text = "Right click: move, or attack an enemy"
                    ),
                    Label(
                        position = screenRect(x = 0.05, y = 0.72, width = 0.90, height = 0.16),
                        text = "Middle click: toggle scene"
                    )
                )
            )
        )
        add(beepButton)
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

/**
 * A [ButtonListener] for the demo BEEP button: it logs when the cursor enters
 * the button and plays [MOVE_COMMAND_SOUND] on every activation (a full
 * mouse click, or a press of the button's assigned key).
 */
private fun beepButtonListener(sounds: SoundPlayer): ButtonListener = object : ButtonListener {

    override fun onMouseOver(button: Button) {
        log.debug("Cursor over button '{}'", button.name)
    }

    override fun onClick(button: Button) {
        log.info("Button '{}' activated", button.name)
        sounds.play(MOVE_COMMAND_SOUND)
    }
}

/** The bottom-of-screen inspector panel's box, as a fraction of the window. */
private val INFO_PANEL_RECT = screenRect(x = 0.25, y = 0.85, width = 0.50, height = 0.15)

// The info panel is split left-to-right: the portrait, then a narrow column
// holding the selection header, the healthbar and the action buttons, then the
// stats panel filling the rest. Every box below is a fraction of the info panel.

// The "healthbar" sits in the middle column, under the header. Its width is a
// fixed fraction of the panel; its height fraction is derived from the window
// size (see healthBarRect) so the *rendered* bar stays about this aspect -
// height ~= 0.05 * width - whatever the window's shape.
private const val HEALTH_BAR_X = 0.49
private const val HEALTH_BAR_Y = 0.10
private const val HEALTH_BAR_WIDTH = 0.46
private const val HEALTH_BAR_HEIGHT_OVER_WIDTH = 0.05

/**
 * The "healthbar"'s box within [panel], picked so its rendered pixel height is
 * about [HEALTH_BAR_HEIGHT_OVER_WIDTH] of its rendered pixel width at the given
 * window size, then centred vertically in the panel.
 */
private fun healthBarRect(panel: Square, windowWidth: Int, windowHeight: Int): Square {
    val renderedWidthPx = HEALTH_BAR_WIDTH * panel.dimensions.width * windowWidth
    val renderedHeightPx = HEALTH_BAR_HEIGHT_OVER_WIDTH * renderedWidthPx
    val heightFraction = (renderedHeightPx / (panel.dimensions.height * windowHeight)).coerceIn(0.0, 1.0)
    return screenRect(HEALTH_BAR_X, HEALTH_BAR_Y, HEALTH_BAR_WIDTH, heightFraction)
}

// Two square action buttons in a row just below the healthbar, left-aligned with
// it. ACTION_BUTTON_SIDE is a fraction of the panel's *height*; the matching
// width fraction is worked out per window size so each button renders as a true
// on-screen square (see actionButtonRect). ACTION_BUTTON_GAP is the space
// between the two, as a fraction of the panel's width.
private const val ACTION_BUTTON_X = HEALTH_BAR_X + 0.02
private const val ACTION_BUTTON_Y = 0.44
private const val ACTION_BUTTON_GAP = 0.02

private fun actionButtonRect(column: Int): Square {
    val width = HEALTH_BAR_WIDTH * 0.23
    val x = ACTION_BUTTON_X + column * (width + ACTION_BUTTON_GAP)
    return screenRect(x, ACTION_BUTTON_Y, width, width)
}

// The stats panel fills the info panel to the right of the portrait/health
// column. Its labels are a plain STATS_COLUMNS x STATS_ROWS grid (10 cells for
// the 10 Alive stats we show).
private val STATS_PANEL_RECT = screenRect(x = 0.17, y = 0.1, width = 0.28, height = 0.85)
private const val STATS_COLUMNS = 2
private const val STATS_ROWS = 5

/** Text colour shared by the header label and every stat label. */
private val LABEL_COLOR = Color(220, 225, 235)

/**
 * The box for the stat label at ([column], [row]) as a fraction of the stats
 * panel: an even [STATS_COLUMNS] x [STATS_ROWS] grid with each cell inset a
 * little so neighbouring labels do not run together.
 */
private fun statCellRect(column: Int, row: Int): Square {
    val cellWidth = 1.0 / STATS_COLUMNS
    val cellHeight = 1.0 / STATS_ROWS
    return screenRect(
        x = column * cellWidth + 0.03,
        y = row * cellHeight + 0.02,
        width = cellWidth - 0.05,
        height = cellHeight - 0.04
    )
}

/**
 * The stats sub-panel: the selected [AliveSnapshot]'s faction, owner, health,
 * damage, movement speed, four attack stats, evasion and destination, one per
 * [Label] in a two-column grid. Position, size, facing and texture are left out
 * on purpose - the portrait and header already cover the drawable state. Each
 * label re-reads [alive] every frame and shows nothing while the selection is
 * not an [AliveSnapshot].
 */
private fun statsPanel(alive: () -> AliveSnapshot?): Panel {
    // "name  value" for the current selection, or "" when nothing Alive is picked.
    fun stat(column: Int, row: Int, name: String, value: (AliveSnapshot) -> String): Label =
        Label(
            position = statCellRect(column, row),
            textColor = LABEL_COLOR,
            textSource = { alive()?.let { "$name  ${value(it)}" } ?: "" }
        )

    return Panel(
        position = STATS_PANEL_RECT,
        color = Color(10, 12, 22, 160),
        children = listOf(
            stat(column = 0, row = 0, name = "Faction") { it.faction },
            stat(column = 0, row = 1, name = "Owner") { it.ownerName ?: "none" },
            stat(column = 0, row = 2, name = "Health") { "${fmt(it.health.value)} / ${fmt(it.health.maxValue)}" },
            stat(column = 0, row = 3, name = "Damage") { fmt(it.damage) },
            stat(column = 0, row = 4, name = "Speed") { dec(it.actor.speed) },
            stat(column = 1, row = 0, name = "Atk time") { dec(it.attackTime) },
            stat(column = 1, row = 1, name = "Atk speed") { fmt(it.attackSpeed) },
            stat(column = 1, row = 2, name = "Atk range") { fmt(it.attackRange) },
            stat(column = 1, row = 3, name = "Evasion") { dec(it.evasion) },
            stat(column = 1, row = 4, name = "Dest") { "${fmt(it.actor.destination.x)}, ${fmt(it.actor.destination.y)}" },
        )
    )
}

/**
 * The bottom-of-screen inspector: a [Portrait] of the selected object on the
 * left, then a narrow column with the selection header, a [StatBar] "healthbar"
 * (shown only while an [AliveSnapshot] is selected) and two square action
 * [Button]s, then a [statsPanel] filling the rest. Every child re-reads
 * [selected] / [selectedAlive] / [selectedIndex] each frame, so the panel
 * updates the instant a new object is clicked and tracks it as it moves.
 */
private fun bottomInfoPanel(
    selected: () -> VisibleObjectSnapshot?,
    selectedAlive: () -> AliveSnapshot?,
    sounds: SoundPlayer,
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
        // Selection header, above the healthbar in the middle column.
        Label(
            position = screenRect(x = 0.17, y = 0.04, width = 0.30, height = 0.20),
            textColor = LABEL_COLOR,
            textSource = { selectedIndex()?.let { "Actor #$it" } ?: "Nothing selected" }
        ),
        StatBar(
            position = healthBarRect(INFO_PANEL_RECT, windowWidth, windowHeight),
            value = { selectedAlive()?.health?.value ?: 0.0 },
            maxValue = { selectedAlive()?.health?.maxValue ?: 1.0 },
            visible = { selectedAlive() != null }
        ),
        statsPanel(selectedAlive),
        Button(
            name = "Q",
            listener = beepButtonListener(sounds),
            position = actionButtonRect(0)
        ),
        Button(
            name = "W",
            listener = beepButtonListener(sounds),
            position = actionButtonRect(1)
        ),
    )
)

/** A whole-number readout of [value] - health, ranges, destination coordinates. */
private fun fmt(value: Double): String = "%.0f".format(value)

/** A two-decimal readout of [value] - the small stats like attack time and evasion. */
private fun dec(value: Double): String = "%.2f".format(value)

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
