# GameGraphics

A desktop game **client** for the SpartanLabsGaming game server. It renders the
world with [LWJGL](https://www.lwjgl.org/) (GLFW + OpenGL 3.3), talks to the
server over UDP, and draws a small retained-mode UI on top of the scene.

Written in Kotlin. The wire types and server protocol come from
[`GameTools`](https://central.sonatype.com/artifact/io.github.spartanlabsgaming/gametools)
(`io.github.spartanlabsgaming:gametools` — the umbrella artifact that, as of 5.0.0,
re-exports `gametools-core` and `gametools-net`); this repository is only the client.

---

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | **23+** | `GameTools` is compiled for JVM 23. The Gradle toolchain auto-provisions one if it isn't already installed. |
| Gradle | 9.0 (wrapper) | Use `./gradlew`; nothing to install. |
| OS | Windows / macOS / Linux | The correct LWJGL natives are selected at build time from `os.name` / `os.arch`. |
| A running game server | — | The client connects on start-up; see [Configuration](#configuration). |

## Build and run

```bash
./gradlew run       # build and launch the client
./gradlew test      # run the unit tests (111 tests, JUnit 5 style)
./gradlew build     # compile, test, and assemble
```

The application entry point is `com.spartanlabs.MainKt`. On macOS the `run` task
adds `-XstartOnFirstThread` automatically (GLFW must own the main thread).

## Configuration

Connection settings are compile-time constants at the top of
[`src/main/kotlin/com/spartanlabs/Main.kt`](src/main/kotlin/com/spartanlabs/Main.kt):

```kotlin
private const val SERVER_HOST = "127.0.0.1"
private const val PLAYER_NAME = "Player1"   // must not contain whitespace
```

Point `SERVER_HOST` at your server and rebuild. If the handshake fails the
window still opens; it just shows an empty world.

## Controls

| Input | Action |
|-------|--------|
| **Left click** | Select the actor under the cursor (client-side only); a green outline marks it in the world |
| **Right click** | With a selection: attack the enemy actor under the cursor, otherwise move there (and drop a fading marker). With no selection: just the marker. |
| **Middle click** | Toggle between the menu and game scenes |
| **`M`** then left click | Force a **move** order for the selection: `Follow` if the click lands on another actor, otherwise `MoveTo`. Right click cancels; `M` again disarms. |
| **`A`** then left click | Force an **attack** order for the selection: the target under the cursor if valid, otherwise the nearest enemy anywhere. Right click cancels; `A` again disarms. |
| **`S`** | Stop the selected unit where it stands (immediate — not a click state) |
| **Scroll wheel** | Zoom the camera |
| **Cursor near a window edge** | Pan the camera |
| **`B`** | Demo button ("BEEP (B)") — also clickable with the mouse |
| **Escape** | Quit |

`M` / `A` arm a one-shot **click state** on the `Viewport` (`ClickState.MOVE` /
`ClickState.ATTACK`); issuing the order, or a right click, returns it to
`ClickState.DEFAULT`. Selection is suspended while a state is armed.

---

## Architecture

`Main` is the only place that knows about every part at once: it creates the
`Window`, the `NetworkClient` and the `SoundPlayer`, builds the UI `Stage`, and
runs the frame loop (pump input → poll latest world state → render). `Window`
and `NetworkClient` never reference each other.

```
Main
 ├─ Window ............ GLFW window + OpenGL context, actor rendering, input routing
 │   ├─ UiRenderer .... draws the UI Scene (quads + STBEasyFont text)
 │   ├─ Picking ....... "which actor is under this pixel"
 │   ├─ TextureCache .. loads images to the GPU once, keyed by name
 │   └─ Shaders ....... GLSL compile/link helper
 ├─ NetworkClient ..... UDP I/O with the server; exposes the latest world state
 │   └─ ProtocolParsing pure wire-grammar parsing/formatting (no sockets)
 └─ SoundPlayer ....... fire-and-forget MP3 sound effects
```

### Packages

- **`com.spartanlabs`** — `Main.kt`, the composition root and game loop.
- **`com.spartanlabs.graphics`** — the renderer and window: `Window`,
  `UiRenderer`, `Picking`, `TextureCache`, `Shaders`.
- **`com.spartanlabs.graphics.ui`** — a retained-mode UI toolkit. `Element` is a
  sealed hierarchy (`Label`, `Panel`, `Button`, `StatBar`, `Portrait`,
  `Viewport`); a `Scene` is a back-to-front list of elements and a `Stage` maps
  names to scenes the window hot-swaps between. Element positions are fractions
  of their frame (`0.0..1.0`), resolved to pixels by `Scene.flatten`. Input is
  delivered through `Scene.dispatchMouse` (hit-tested, opaque elements consume)
  and `Scene.dispatchKey` (broadcast). `Viewport` sits at the back of every
  scene and is where "clicked on the game" behaviour lives; it reaches the game
  through the `GameView` interface.
- **`com.spartanlabs.networking`** — `NetworkClient` (which socket, which thread,
  when), `ProtocolParsing` (the grammar), `DrawableSnapshots` (`drawableCore()`,
  which unwraps any server snapshot kind to the plain `VisibleObjectSnapshot`
  the renderer needs), and `NdcConverter` / `Camera` (pixel ↔ normalized device
  coordinates, with pan and zoom).

### Server protocol

UDP, via GameTools' `MultiConnectionUDPServer`:

1. **Handshake** — the client sends `Iam <name>` to the server's well-known
   port from a socket it keeps open for the connection's lifetime; the server
   replies straight back to that datagram's source with the bare token
   `REGISTERED`. (GameTools 3.0.0 / WebTools 2.0.0c dropped the dedicated
   per-connection port pair in favor of multiplexing every client's traffic
   over the single well-known port.)
2. **Shared channel** — the server broadcasts `STATE <json>` every tick (a
   polymorphic JSON array of `DrawableSnapshot`: plain visible objects, actors,
   or `Alive`s with health/faction/combat stats) over that same well-known
   port. The client sends its orders back over it as `COMMAND <json>` datagrams
   plus a raw `PING`, and a bare `KA` keepalive on an idle interval to hold its
   NAT mapping open.

The client only needs position, size, colour, texture and angle to draw, so it
unwraps every `STATE` entry to its drawable core; callers that want the richer
state (an `Alive`'s health, shown in the selection inspector) pattern-match the
snapshot instead.

#### Commands

Orders use GameTools 5.0.0's `ClientCommand` protocol: one `COMMAND` verb whose
payload is a polymorphic JSON object (a `type` discriminator, same shape as
`STATE` / `INPUT`), encoded and decoded by a `ClientCommandCodec` that both this
client and the server build the same way (the six standard `gametools.*`
commands, no app module). The client emits four of them:

```
COMMAND {"type":"gametools.moveTo","actor":7,"x":120.0,"y":-40.0}
COMMAND {"type":"gametools.follow","actor":7,"target":13}
COMMAND {"type":"gametools.attack","attacker":7,"target":13}
COMMAND {"type":"gametools.stop","actor":7}
```

Each `actor` / `attacker` / `target` is the stable entity id GameTools stamps on
every `DrawableSnapshot` (`id`) — the server resolves it with `World.byId`, so a
queued order stays bound to the unit the player meant even as the `STATE` list
reorders (deaths, spawns). `id` is an `EntityId` value class that serializes as a
bare `Long`, so the same value drops straight from a snapshot into a command.
The client works in the raw `Long` throughout and only wraps it at the codec
boundary (`networking/ClientCommands.kt`).

A selection is tracked by that same id, not by list position. A server older
than GameTools 3.1 sends no ids (selection is disabled against it); one older
than 5.0.0 has no `COMMAND` verb, so orders are silently dropped rather than
mis-targeted — the two repos ship in lockstep.

### Rendering notes

- The UI lives in fixed screen space (window pixels, origin top-left) and never
  pans or zooms with the camera, so `UiRenderer` owns its own quad and text
  shaders rather than sharing the actor pipeline.
- Text is drawn with `STBEasyFont` — no font file or glyph atlas; the geometry
  is generated on the CPU each frame.
- All OpenGL calls assume a current context on the calling (main) thread.
- The selection highlight is four thin quads (`SelectionOutline` computes the
  world-pixel geometry) drawn with the actor pipeline and the white fallback
  texture, so it tracks the selected unit through pan and zoom and vanishes the
  frame the unit leaves the world state.

## Testing

`./gradlew test` runs the JUnit 5 suite with [MockK](https://mockk.io/) for the
few doubles that are needed. Coverage is concentrated on the pure logic that can
run without a GPU or a network: `NdcConverter`, `Picking`, `ProtocolParsing`,
`EntityLookup` / `ClientCommands` (id resolution and the `COMMAND` wire form),
the `ui` toolkit (`UiTest`, `ButtonTest`, `StatBarTest`, `PortraitTest`,
`ViewportTest`), and `DrawableSnapshots`. Code that requires a live GL/GLFW
context (`Window`, most of `UiRenderer`) is not unit-tested.

## Project layout

```
build.gradle.kts            Gradle build (Kotlin DSL), dependencies, run/test config
settings.gradle.kts
src/main/kotlin/             application and library code
src/main/resources/
  ├─ sounds/                 MP3 sound effects, looked up by bare file name
  └─ textures/               PNG/JPG/GIF textures, looked up by bare file name
src/test/kotlin/             unit tests
```
