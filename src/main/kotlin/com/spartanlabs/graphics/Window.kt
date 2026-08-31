package com.spartanlabs.graphics

import com.spartanlabs.gaming.gameobjects.VisibleObjectSnapshot
import com.spartanlabs.gaming.networking.MouseAction
import com.spartanlabs.gaming.networking.MouseActionType
import com.spartanlabs.graphics.ui.Scene
import com.spartanlabs.graphics.ui.Stage
import com.spartanlabs.graphics.ui.dispatchMouse
import com.spartanlabs.networking.Camera
import com.spartanlabs.networking.NdcConverter
import com.spartanlabs.networking.drawableCore
import org.lwjgl.glfw.Callbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33.*
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Float
import kotlin.Boolean
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Long
import kotlin.Result
import kotlin.RuntimeException
import kotlin.String
import kotlin.Unit
import kotlin.floatArrayOf
import kotlin.fold
import kotlin.intArrayOf
import kotlin.onFailure
import kotlin.onSuccess
import kotlin.runCatching
import kotlin.use

/** Shared slf4j logger for all [Window] instances. */
private val log: Logger = LoggerFactory.getLogger(Window::class.java)


/**
 * Owns the GLFW window, the OpenGL context, and everything needed to render
 * actors to the screen. Has no knowledge of networking — callers simply pass
 * a list of [VisibleObjectSnapshot]s (GameTools' own wire/state DTO) into
 * [render] each frame.
 *
 * Mouse input is routed into the current UI [Scene] (see [routeMouseToScene]):
 * every move/press/release is offered to the element under the cursor, and a
 * full-window [com.spartanlabs.graphics.ui.Viewport] at the back of the scene
 * is where "clicked on the game" behaviour lives. Window itself stays free of
 * any networking knowledge.
 *
 * Each actor is drawn with the texture its snapshot names - loaded from the
 * `textures/` classpath folder by [TextureCache], a missing or unreadable
 * one falling back to a blank texture - tinted by the snapshot's colour.
 *
 * Every fallible lifecycle operation ([open], [close]) returns a [Result]
 * rather than throwing: GLFW/OpenGL setup has several genuine, expected
 * failure modes (no GPU/display available, a malformed shader), so callers
 * can react to or log a failure instead of the process crashing on an
 * uncaught exception.
 */
class Window(
    private val title: String
) {
    private var handle: Long = 0

    // Detected from the monitor at open(), updated on resize.
    private var width = 0
    private var height = 0

    // GPU handles
    private var vao = 0
    private var vbo = 0
    private var ebo = 0
    private var shaderProgram = 0

    // Actor textures, keyed by the name each snapshot supplies.
    private val textures = TextureCache()

    // Multiplier applied to every actor's rendered size, adjusted by the
    // mouse scroll wheel (see registerCallbacks()). Only ever touched from
    // the main/GLFW thread - the scroll callback and render() both run
    // there - so no synchronization is needed, same as width/height above.
    private var zoomFactor = 1.0f

    // Latest known cursor position, updated on every MOVE event (see
    // handleMouseActionInternally). Initialized to the window's center once
    // it's known (see initWindow()) so edge panning doesn't fire before the
    // user has moved the mouse at all.
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0

    // How far the camera has panned from the world origin, in pixels,
    // driven by the cursor sitting near a window edge (see applyEdgePanning()).
    private var panOffsetX = 0.0f
    private var panOffsetY = 0.0f

    private val vertexShaderSource = """
        #version 330 core
        layout (location = 0) in vec2 aPos;
        layout (location = 1) in vec2 aTexCoord;

        out vec2 vTexCoord;

        uniform vec2 uOffset;
        uniform vec2 uHalfSize;
        uniform float uAngleRadians;

        void main() {
            vec2 scaled = aPos * uHalfSize;

            float s = sin(uAngleRadians);
            float c = cos(uAngleRadians);
            vec2 rotated = vec2(
                scaled.x * c - scaled.y * s,
                scaled.x * s + scaled.y * c
            );

            gl_Position = vec4(rotated + uOffset, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderSource = """
        #version 330 core
        in vec2 vTexCoord;
        out vec4 FragColor;

        uniform sampler2D uTexture;
        uniform vec4 uColor;

        void main() {
            FragColor = uColor * texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    // The set of hot-swappable scenes and which one is currently shown.
    // A Window with no stage loaded simply renders the world with no UI on
    // top; showScene() picks which scene render() overlays each frame.
    private var stage: Stage = Stage()
    private var currentSceneName: String? = null

    private val uiRenderer = UiRenderer()

    // The snapshot list from the most recent render() call, kept so pick()
    // can hit-test against exactly what the user is currently looking at.
    private var lastSnapshots: List<VisibleObjectSnapshot> = emptyList()

    // Transient right-click markers: a textured quad dropped at a fixed world
    // position that fades from opaque to invisible over MARKER_FADE_SECONDS,
    // then is discarded. Purely client-side eye-candy - the server is never
    // told. Only ever touched from the main/GLFW thread (addClickMarker runs
    // in a mouse callback, drawClickMarkers in render()), so no sync needed.
    private val clickMarkers = mutableListOf<ClickMarker>()

    private class ClickMarker(val worldX: Double, val worldY: Double, val spawnTime: Double)

    /**
     * Creates the window, OpenGL context, shaders, and quad geometry.
     * @return [Result.success] once the window is ready to [render] to, or
     * the failure that prevented it (GLFW init failure, no monitor detected,
     * or a shader compile/link error)
     */
    fun open(): Result<Unit> =
        initWindow()
            .flatMap { initOpenGl() }
            .flatMap { initGraphicsResources() }
            .onSuccess { log.info("Window opened: {}x{} '{}'", width, height, title) }
            .onFailure { cause -> log.error("Could not open the window", cause) }

    /** True once the user has requested the window close (e.g. pressed Escape). */
    fun shouldClose(): Boolean = glfwWindowShouldClose(handle)

    /** Pumps the GLFW event queue. Call once per frame. */
    fun pollEvents() = glfwPollEvents()

    /**
     * Converts a window-pixel point (origin top-left, as GLFW reports the
     * cursor - e.g. [MouseAction.x]/[MouseAction.y]) into the world position
     * currently rendered at that pixel, accounting for the live camera
     * pan/zoom. Callers wiring mouse input to world commands (move-to-click,
     * etc.) must run clicks through this - the raw cursor coordinates are in
     * a different space and origin than actor locations.
     */
    fun screenToWorld(screenXPx: Double, screenYPx: Double): Pair<Double, Double> =
        NdcConverter.screenToWorld(
            screenXPx, screenYPx, width, height,
            Camera(zoomFactor, panOffsetX, panOffsetY)
        )

    /**
     * Replaces the set of hot-swappable [Scene]s this window can show. Does
     * not change what is currently on screen - call [showScene] for that.
     */
    fun loadStage(stage: Stage) {
        this.stage = stage
        log.info("Loaded stage with scenes: {}", stage.keys)
        currentSceneName?.let { if (it !in stage) currentSceneName = null }
    }

    /**
     * Hot-swaps the UI to the scene registered under [name], which [render]
     * then draws on top of the world every frame until the next swap.
     * @return [Result.success], or [Result.failure] if no scene is registered
     * under [name] (the current scene is left unchanged)
     */
    fun showScene(name: String): Result<Unit> =
        if (stage.containsKey(name)) {
            currentSceneName = name
            log.info("Showing scene '{}'", name)
            Result.success(Unit)
        } else {
            Result.failure(IllegalArgumentException("No scene named '$name' in the stage (have ${stage.keys})"))
        }

    /** The name of the scene currently drawn on top of the world, or null if none. */
    fun currentScene(): String? = currentSceneName

    /**
     * Drops a fading [MARKER_TEXTURE] quad at the world position currently
     * rendered under the given window pixel (top-left origin, as GLFW reports
     * the cursor). It is pinned to that world spot - panning/zooming the
     * camera carries it along like an actor - and fades from opaque to gone
     * over [MARKER_FADE_SECONDS] before [render] discards it. Purely a
     * client-side cue (e.g. a right-click move order); nothing is sent to the
     * server.
     */
    fun addClickMarker(screenXPx: Double, screenYPx: Double) {
        val (worldX, worldY) = screenToWorld(screenXPx, screenYPx)
        clickMarkers += ClickMarker(worldX, worldY, glfwGetTime())
        log.debug("Click marker dropped at world ({}, {})", worldX, worldY)
    }

    /**
     * Hit-tests a window-pixel point (top-left origin, as GLFW reports the
     * cursor) against the actors drawn in the most recent [render] call,
     * honouring their on-screen position, size, rotation and the camera
     * pan/zoom at that time.
     * @return the index - into that frame's snapshot list - of the top-most
     * actor under the point, or null if the point is over empty space
     */
    fun pick(screenXPx: Double, screenYPx: Double): Int? =
        Picking.pick(
            screenXPx, screenYPx, width, height,
            Camera(zoomFactor, panOffsetX, panOffsetY),
            lastSnapshots
        )

    /**
     * Clears the screen, draws every actor in [snapshots] (and its
     * sub-objects) at its current position/size/rotation/colour, adjusted by
     * the current camera pan/zoom, overlays the current UI [Scene] (if any),
     * and presents the frame.
     */
    fun render(snapshots: List<VisibleObjectSnapshot>) {
        log.trace("Rendering {} actor(s)", snapshots.size)
        applyEdgePanning()
        lastSnapshots = snapshots

        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val camera = Camera(zoomFactor, panOffsetX, panOffsetY)
        snapshots.forEach { snapshot -> drawActor(snapshot, camera) }
        drawClickMarkers(camera)

        currentSceneName?.let { name -> stage[name] }?.let { scene ->
            uiRenderer.render(scene, width, height)
        }

        glfwSwapBuffers(handle)
    }

    /**
     * Releases all GPU and GLFW resources.
     * @return [Result.success] if every resource was released, or the
     * failure that prevented it
     */
    fun close(): Result<Unit> = runCatching {
        uiRenderer.close()
        textures.close()

        glDeleteVertexArrays(vao)
        glDeleteBuffers(vbo)
        glDeleteBuffers(ebo)
        glDeleteProgram(shaderProgram)

        Callbacks.glfwFreeCallbacks(handle)
        glfwDestroyWindow(handle)

        glfwTerminate()
        glfwSetErrorCallback(null)?.free() ?: Unit
    }
        .onSuccess { log.info("Window closed") }
        .onFailure { cause -> log.error("Could not cleanly close the window", cause) }

    // ---------------------------------------------------------------
    // Internal setup, broken into small Result-returning steps so open()
    // can compose them with flatMap and short-circuit on the first failure.
    // ---------------------------------------------------------------

    /** Initializes GLFW, detects the monitor, and creates the window + input callbacks. */
    private fun initWindow(): Result<Unit> = runCatching {
        GLFWErrorCallback.createPrint(System.err).set()

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        // Detect the primary monitor's current resolution and size the
        // window to match it (borderless, fills the screen exactly).
        val primaryMonitor = glfwGetPrimaryMonitor()
        val vidmode = glfwGetVideoMode(primaryMonitor)
            ?: throw RuntimeException("Unable to detect monitor video mode")

        width = vidmode.width()
        height = vidmode.height()
        log.debug("Detected monitor resolution {}x{}", width, height)

        // Start edge-panning's tracked cursor position at the center, not
        // (0,0) - otherwise it would read as sitting in the top-left corner
        // and immediately start panning before the user has moved the mouse.
        lastMouseX = width / 2.0
        lastMouseY = height / 2.0

        glfwWindowHint(GLFW_REFRESH_RATE, vidmode.refreshRate())
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)

        handle = glfwCreateWindow(width, height, title, NULL, NULL)
        if (handle == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        stackPush().use { stack ->
            val monX = stack.mallocInt(1)
            val monY = stack.mallocInt(1)
            glfwGetMonitorPos(primaryMonitor, monX, monY)
            glfwSetWindowPos(handle, monX.get(0), monY.get(0))
        }

        registerCallbacks()
    }.onFailure { cause -> log.error("Could not initialize the window", cause) }

    private fun registerCallbacks() {
        glfwSetKeyCallback(handle) { win, key, _, action, _ ->
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true)
            }
        }

        glfwSetFramebufferSizeCallback(handle) { _, w, h ->
            glViewport(0, 0, w, h)
            width = w
            height = h
        }

        // Cursor movement -> MOVE events, throttled to at most 60 per second.
        // GLFW can fire this callback far more often than that on a fast
        // mouse/high-poll-rate device; there's no benefit sending more MOVE
        // events than the display (or server tick rate) can use.
        var lastMoveSentTime = 0.0
        val moveIntervalSeconds = 1.0 / 60.0

        glfwSetCursorPosCallback(handle) { _, xpos, ypos ->
            val now = glfwGetTime()
            if (now - lastMoveSentTime >= moveIntervalSeconds) {
                lastMoveSentTime = now
                dispatchMouseAction(MouseAction(MouseActionType.MOVE, button = -1, x = xpos, y = ypos))
            }
        }

        // Mouse button press/release -> PRESS/RELEASE events, tagged with the
        // cursor position at the time of the click. Never throttled — these
        // are discrete, low-frequency events that must not be dropped.
        glfwSetMouseButtonCallback(handle) { win, button, action, _ ->
            stackPush().use { stack ->
                val xBuf = stack.mallocDouble(1)
                val yBuf = stack.mallocDouble(1)
                glfwGetCursorPos(win, xBuf, yBuf)

                val type = when (action) {
                    GLFW_PRESS -> MouseActionType.PRESS
                    GLFW_RELEASE -> MouseActionType.RELEASE
                    else -> return@glfwSetMouseButtonCallback // ignore GLFW_REPEAT, etc.
                }

                dispatchMouseAction(MouseAction(type, button, xBuf.get(0), yBuf.get(0)))
            }
        }

        // Mouse wheel -> zoom in/out. GLFW reports yoffset as roughly +/-1
        // per wheel "click" on a traditional mouse (trackpads may report
        // fractional/continuous values); scrolling up zooms in, scrolling
        // down zooms out. Clamped so quads can never shrink to nothing or
        // grow unboundedly.
        glfwSetScrollCallback(handle) { _, _, yoffset ->
            zoomFactor = (zoomFactor + yoffset.toFloat() * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)
            log.debug("Zoom factor is now {}", zoomFactor)
        }
    }

    /**
     * Invoked on every mouse move/press/release. Runs [handleMouseActionInternally]
     * first (window-owned camera behaviour, always), then [routeMouseToScene]
     * so the UI - and through it the game viewport - gets the event.
     */
    private fun dispatchMouseAction(action: MouseAction) {
        handleMouseActionInternally(action)
        routeMouseToScene(action)
    }

    /**
     * Hands [action] to the current UI [Scene] for hit-testing and handling
     * (panels/labels in front, then the game viewport at the back). Does
     * nothing if no scene is shown.
     */
    private fun routeMouseToScene(action: MouseAction) {
        val scene = currentSceneName?.let { name -> stage[name] } ?: return
        scene.dispatchMouse(action, width, height)
    }

    /**
     * Window-internal reaction to mouse input, separate from the UI routing.
     * Tracks the latest cursor position (used by [applyEdgePanning]) and logs
     * at trace level - add any other window-owned mouse handling here.
     */
    private fun handleMouseActionInternally(action: MouseAction) {
        log.trace("Internal mouse action: {}", action)
        if (action.type == MouseActionType.MOVE) {
            lastMouseX = action.x
            lastMouseY = action.y
        }
    }

    /**
     * Pans the camera whenever the cursor sits within [EDGE_PAN_THRESHOLD_PX]
     * of a window edge, moving [panOffsetX]/[panOffsetY] by [EDGE_PAN_SPEED_PX_PER_FRAME]
     * toward that edge. Called once per rendered frame (from [render]) rather
     * than only on mouse-move events, so panning continues smoothly even
     * while the cursor sits still near an edge.
     */
    private fun applyEdgePanning() {
        when {
            lastMouseX < EDGE_PAN_THRESHOLD_PX -> panOffsetX -= EDGE_PAN_SPEED_PX_PER_FRAME
            lastMouseX > width - EDGE_PAN_THRESHOLD_PX -> panOffsetX += EDGE_PAN_SPEED_PX_PER_FRAME
        }
        when {
            lastMouseY < EDGE_PAN_THRESHOLD_PX -> panOffsetY -= EDGE_PAN_SPEED_PX_PER_FRAME
            lastMouseY > height - EDGE_PAN_THRESHOLD_PX -> panOffsetY += EDGE_PAN_SPEED_PX_PER_FRAME
        }
    }

    /** Makes the GL context current on this thread and sets initial GL state. */
    private fun initOpenGl(): Result<Unit> = runCatching {
        glfwMakeContextCurrent(handle)
        glfwSwapInterval(1) // v-sync
        glfwShowWindow(handle)

        GL.createCapabilities()

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glClearColor(0.1f, 0.1f, 0.15f, 1.0f)
    }.onFailure { cause -> log.error("Could not initialize OpenGL", cause) }

    /** Compiles/links the shader program, builds the reusable quad geometry, and readies the texture cache and UI renderer. */
    private fun initGraphicsResources(): Result<Unit> = runCatching {
        shaderProgram = Shaders.link(vertexShaderSource, fragmentShaderSource)
        log.debug("Compiled and linked shader program (handle {})", shaderProgram)

        createQuadGeometry()
        textures.initialize()

        uiRenderer.initialize().getOrThrow()
    }.onFailure { cause -> log.error("Could not initialize graphics resources", cause) }

    // ---------------------------------------------------------------
    // Internal rendering details
    // ---------------------------------------------------------------

    /**
     * Draws an actor snapshot with the given [camera] pan/zoom applied:
     * textured with the file [TextureCache] loads for its
     * [VisibleObjectSnapshot.texture] name, multiplied by its per-object
     * [VisibleObjectSnapshot.color] (so an opaque-black colour renders black,
     * and a sub-255 alpha renders it translucent).
     *
     * Its [VisibleObjectSnapshot.angle] is applied only when the snapshot
     * [turns][VisibleObjectSnapshot.turns]; a non-turning object is drawn
     * upright whatever its angle.
     *
     * Its [VisibleObjectSnapshot.subObjects] (health bars, nameplates, ...)
     * are drawn on top afterwards, recursively - each reduced to its drawable
     * core first, since a sub-object may itself arrive as an `ActorSnapshot`.
     * Their locations are already absolute world coordinates, so no parent
     * offset is applied. Sub-objects are visual only - [pick] still hit-tests
     * the top-level actors alone.
     */
    private fun drawActor(snapshot: VisibleObjectSnapshot, camera: Camera) {
        glUseProgram(shaderProgram)

        val (offsetX, offsetY) = NdcConverter.offset(
            snapshot.gameObject.location.x, snapshot.gameObject.location.y, width, height, camera
        )
        glUniform2f(glGetUniformLocation(shaderProgram, "uOffset"), offsetX, offsetY)

        val (halfSizeX, halfSizeY) = NdcConverter.halfSize(
            snapshot.dimensions.width, snapshot.dimensions.height, width, height, camera
        )
        glUniform2f(glGetUniformLocation(shaderProgram, "uHalfSize"), halfSizeX, halfSizeY)

        // Angle is negated so a positive angle turns the quad clockwise on
        // screen (the opposite of the math convention the shader's rotation
        // matrix uses). Picking mirrors this - keep the two in step.
        val angleRadians = NdcConverter.angleRadians(if (snapshot.turns) -snapshot.angle else 0)
        glUniform1f(glGetUniformLocation(shaderProgram, "uAngleRadians"), angleRadians)

        val color = snapshot.color
        glUniform4f(
            glGetUniformLocation(shaderProgram, "uColor"),
            color.r / 255f, color.g / 255f, color.b / 255f, color.a / 255f
        )

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textures.handleFor(snapshot.texture))
        glUniform1i(glGetUniformLocation(shaderProgram, "uTexture"), 0)

        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L)
        glBindVertexArray(0)

        snapshot.subObjects.forEach { subObject -> drawActor(subObject.drawableCore(), camera) }
    }

    /**
     * Draws every live [clickMarkers] entry with the given [camera] applied,
     * its opacity ramped from full down to zero across its first
     * [MARKER_FADE_SECONDS] of life, and drops any that have fully faded.
     */
    private fun drawClickMarkers(camera: Camera) {
        if (clickMarkers.isEmpty()) return

        val now = glfwGetTime()
        val markers = clickMarkers.iterator()
        while (markers.hasNext()) {
            val marker = markers.next()
            val age = now - marker.spawnTime
            if (age >= MARKER_FADE_SECONDS) {
                markers.remove()
                continue
            }
            drawMarker(marker, alpha = (1.0 - age / MARKER_FADE_SECONDS).toFloat(), camera = camera)
        }
    }

    /**
     * Draws a single [MARKER_TEXTURE] quad at [marker]'s world position,
     * [MARKER_SIZE_PX] on a side, upright, tinted white at the given [alpha].
     * A trimmed-down [drawActor] - no rotation, colour tint, or sub-objects.
     */
    private fun drawMarker(marker: ClickMarker, alpha: kotlin.Float, camera: Camera) {
        glUseProgram(shaderProgram)

        val (offsetX, offsetY) = NdcConverter.offset(marker.worldX, marker.worldY, width, height, camera)
        glUniform2f(glGetUniformLocation(shaderProgram, "uOffset"), offsetX, offsetY)

        val (halfSizeX, halfSizeY) = NdcConverter.halfSize(MARKER_SIZE_PX, MARKER_SIZE_PX, width, height, camera)
        glUniform2f(glGetUniformLocation(shaderProgram, "uHalfSize"), halfSizeX, halfSizeY)

        glUniform1f(glGetUniformLocation(shaderProgram, "uAngleRadians"), 0f)
        glUniform4f(glGetUniformLocation(shaderProgram, "uColor"), 1f, 1f, 1f, alpha)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textures.handleFor(MARKER_TEXTURE))
        glUniform1i(glGetUniformLocation(shaderProgram, "uTexture"), 0)

        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L)
        glBindVertexArray(0)
    }

    private fun createQuadGeometry() {
        // A unit square using +/-1 corner directions. The vertex shader
        // scales this by uHalfSize (set per actor, per draw call) to reach
        // the actual on-screen size, so this buffer is created once and
        // reused for every actor regardless of its individual width/height.
        val vertices = floatArrayOf(
            // positions   // texcoords
            -1f,  1f,      0.0f, 1.0f, // top-left
            1f,  1f,      1.0f, 1.0f, // top-right
            1f, -1f,      1.0f, 0.0f, // bottom-right
            -1f, -1f,      0.0f, 0.0f  // bottom-left
        )

        val indices = intArrayOf(
            0, 1, 2,
            2, 3, 0
        )

        vao = glGenVertexArrays()
        vbo = glGenBuffers()
        ebo = glGenBuffers()

        glBindVertexArray(vao)

        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)

        val stride = 4 * Float.BYTES

        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L)
        glEnableVertexAttribArray(0)

        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES)
        glEnableVertexAttribArray(1)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    private companion object {
        /** How much zoomFactor changes per unit of scroll wheel yoffset. */
        const val ZOOM_STEP = 0.1f

        /** Smallest allowed zoomFactor - prevents scrolling quads down to nothing. */
        const val MIN_ZOOM = 0.1f

        /** Largest allowed zoomFactor - prevents scrolling quads to an unbounded size. */
        const val MAX_ZOOM = 5.0f

        /** Distance (in pixels) from a window edge within which edge panning kicks in. */
        const val EDGE_PAN_THRESHOLD_PX = 50.0f

        /** How far the camera pans per rendered frame while the cursor sits in the edge zone. */
        const val EDGE_PAN_SPEED_PX_PER_FRAME = 1.0f

        /** Texture drawn for a right-click marker (see [addClickMarker]). */
        const val MARKER_TEXTURE = "inwardarrows.png"

        /** A right-click marker's on-screen size, in world pixels (before camera zoom). */
        const val MARKER_SIZE_PX = 96.0

        /** How long a right-click marker takes to fade from opaque to gone, in seconds. */
        const val MARKER_FADE_SECONDS = 1.0
    }
}

/**
 * Chains a [Result]-returning [transform] onto this result, short-circuiting on failure.
 * Reproduced locally to avoid depending on GameTools' internal (non-exported) copy.
 */
private inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    fold(onSuccess = transform, onFailure = { Result.failure(it) })