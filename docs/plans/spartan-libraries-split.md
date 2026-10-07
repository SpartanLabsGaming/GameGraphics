# Split into SpartanGraphics, SpartanUI and the game client

**Status:** planned. Implementation starts when you give the go-ahead.
**Spans:**
- `SpartanLaboratories/SpartanGraphics` (new)
- `SpartanLaboratories/SpartanUI` (new)
- `SpartanLabsGaming/GameGraphics` (this repo). It becomes the game client and is renamed `MyGameClient`.

**Related:**
- [SpartanLaboratories/GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7): make GeneralTools multiplatform
- [SpartanLabsGaming/MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132): a GameTools client, counterpart to `GameServer`
- [SpartanLabsGaming/MyGameTools#124](https://github.com/SpartanLabsGaming/MyGameTools/issues/124): webtools-udp 2.0.0, a new wire format in GameTools 6.0.0

## Problem

This repo is a rough-draft client for GameTools (`SpartanLabsGaming/MyGameTools`). One JVM module mixes:
- a reusable UI toolkit,
- an OpenGL renderer,
- GLFW windowing and input,
- UDP networking,
- audio,
- RTS game logic.

None of it can be reused by another game, and none of it can move to Android or web. Specifically:
- **The UI's input type comes from the networking artifact.** `Element.onMouseAction` takes GameTools' `MouseAction`, which ships in `gametools-net`.
- **The "generic" UI contains GLFW codes.** `Button.key` and `KeyAction.key` are GLFW key codes, and `Button` / `Viewport` hard-code GLFW mouse-button numbers.
- **`Element` is `sealed`,** so no one outside this module can add an element type.
- **`Window` is 820 lines doing several jobs.** It owns the window, the GL context, the actor pipeline, the camera, input translation, scene routing, picking, click markers and the selection outline.
- **The public API is JVM-only.** It exposes GeneralTools and GameTools types, which are JVM-only jars. gametools-core also brings in WebTools, the UDP library.

## Goal

1. **SpartanGraphics:** a Kotlin Multiplatform graphics library with no knowledge of GameTools or of any game.
2. **SpartanUI:** a Kotlin Multiplatform UI library built on SpartanGraphics, with no knowledge of its users.
3. **This repo:** the GameTools RTS client, rebuilt on both libraries. It doubles as their live test.

The work lands as the series of PRs below. Breaking the current game along the way is acceptable if it leads to a better-planned result.

## Repositories, platforms and publishing

- **Repositories:**
  - `SpartanLaboratories/SpartanGraphics` and `SpartanLaboratories/SpartanUI` hold the libraries.
  - This repo stays in SpartanLabsGaming as the game client. It's renamed `MyGameClient` to pair with `MyGameServer` and `MyGameTools`. You rename it in GitHub settings, and old URLs redirect.
- **Packages:** `com.spartanlabs.graphics` and `com.spartanlabs.ui`.
- **Platforms:** Windows now, then Android, then web, then others. The libraries are Kotlin Multiplatform. Each library has one Gradle module and artifact per device family (`-desktop` now, `-android` and `-web` later), rather than platform source sets inside one module, so an app pulls in only the backend it uses.
- **Desktop scope:** only Windows is officially supported. The desktop module still handles the [macOS and Linux considerations](#macos-and-linux-considerations), and CI compiles on all three.
- **Publishing:**
  - Maven Central, under `io.github.spartanlaboratories`.
  - UI and graphics are versioned separately, using semantic versions starting at `0.1.0`.
  - Apache 2.0, like GeneralTools, unless the new repos were created with a different licence.
  - Released by hand, as GeneralTools is.
- **Working across repos:** unreleased library changes reach the game client through a Gradle composite build. Its `settings.gradle.kts` includes `../SpartanGraphics` and `../SpartanUI` with `includeBuild` when those checkouts exist; otherwise it uses published versions.

## Target architecture

```
SpartanLaboratories/SpartanGraphics            io.github.spartanlaboratories, com.spartanlabs.graphics
  graphics-core     KMP   basic types, camera, shapes, outlines, picking, abstract input, platform contracts
  graphics-desktop  JVM   LWJGL: GLFW window, OpenGL 3.3 renderer, textures, text, input, frames
  assets-core       KMP   AssetSource (name to bytes), shared by graphics and audio
  audio-core        KMP   sound contract
  audio-desktop     JVM   JLayer MP3 playback

SpartanLaboratories/SpartanUI                  io.github.spartanlaboratories, com.spartanlabs.ui
  ui-core           KMP   elements, layout, dispatch, triggers, callbacks, key bindings   api(graphics-core)
  (no platform modules for now: all platform work is in SpartanGraphics)

SpartanLabsGaming/MyGameClient (this repo)     the GameTools RTS client, JVM 23
  app               Main, game loop, snapshot adapter, game UI, game rules,
                    interim networking, edge panning, click markers, assets
```

```
game client ──> ui-core ──api──> graphics-core ──> assets-core
     │────────> graphics-desktop ──> graphics-core
     │────────> audio-desktop ──> audio-core ──> assets-core
     └────────> gametools ──> GeneralTools, WebTools
```

- **Library dependencies:** neither library depends on GameTools, GeneralTools or WebTools.
- **UI on graphics:** UI's basic elements are built on graphics shapes, and its built-in elements are built from the basic ones.
- **Where GameTools appears:** the game client is the only place where GameTools types meet library types.

### graphics-core (KMP)

- **Basic types:** `Color`, `Point`, `Size` and `Rect`, as contract interfaces with a default immutable implementation each.
  - GeneralTools and GameTools are JVM-only, so the libraries can't use their types; the game client adapts them instead.
  - Once GeneralTools is multiplatform ([#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7)) and GameTools adopts it, those types can be used directly.
- **Camera:**
  - Holds position, zoom and viewport size.
  - Converts between world and screen coordinates.
  - Reports the visible world region. The library renders only what the camera can see.
  - Exposes movement operations: pan by an amount, zoom, centre on a world point.
  - Ports today's `Camera` / `NdcConverter` and fixes their wrong `networking` package.
- **Shapes:** what every platform renderer has to draw. They sit in two layers:
  - The **world layer** is drawn through the camera.
  - The **screen layer** uses window pixels and holds the UI.

  The set is closed: users build anything from these, but can't add a fourth.
  - **Rectangle:** a solid colour, or a texture tinted by that colour, with rotation and alpha. Today's actors, panels, bar fills, portraits and click markers are all this.
  - **Text:** a string with colour and alignment. v1 uses a fixed-size bitmap font.
  - **Outlined rectangle:** a border of a given thickness. It's the primitive outlines are drawn with.

  A world shape can carry a tag supplied by the caller, which picking returns.
- **Outlines:** any shape can opt in to an outline. Graphics provides all of it, including a built-in default colour and shape; see [Outlines](#outlines).
- **Picking:** finds the top-most tagged world shape under a screen point. It ports `Picking` and works on shapes instead of `VisibleObjectSnapshot`.
- **Abstract input:**
  - Pointer position, buttons and scroll, plus keys, all with the library's own ids (`Key.A`; `PointerButton.Primary` / `Secondary` / `Middle`).
  - Enough for a game to build edge panning.
  - UI-specific input (hit-testing, hover, triggers, shortcuts) is layered on top of this in ui-core.
- **Platform contracts:**
  - `Surface` (size, resize, close request). Each platform creates its own window or surface.
  - `Renderer` (draw one frame of shapes and their outlines).
  - Textures.
  - Text measurement.
  - Frame scheduling and clock. Each platform decides when frames happen and calls the game once per frame. The game's own fixed-timestep loop (or GameTools') runs inside that call.
  - Input source, which delivers the abstract input above.
  - `AssetSource`, from `assets-core`.

### graphics-desktop (JVM, LWJGL)

- **Window:** the GLFW window and GL context, taken from `Window.initWindow` / `initOpenGl`.
- **Renderer:** one OpenGL 3.3 renderer for every shape and outline. It merges today's two pipelines (the actor drawing in `Window` and the quad and text drawing in `UiRenderer`).
- **Textures and text:** `TextureCache` and `Shaders` move here as-is. v1 text ports the STBEasyFont bitmap font; real fonts come later.
- **Assets:** an `AssetSource` that loads from the classpath.
- **Frames and input:** a desktop frame loop on `glfwGetTime`, and GLFW input translated into the abstract input.
- **Cross-platform handling:**
  - Window size (for input) and framebuffer size (for drawing) are kept separate.
  - GLFW's windowed full-screen mode replaces moving a borderless window onto the monitor.
  - On macOS the app starts on the first thread.
- **LWJGL native libraries:** the library declares only the LWJGL Java APIs, and the application adds the natives for its OS. Today the build picks natives from the build machine's OS, which is right for an app but wrong for a library.

### assets-core and audio (KMP / JVM)

- **`assets-core`:** holds `AssetSource`, which turns an asset name into bytes.
  - Assets are referenced by name (`"zombie.png"`), as today.
  - Each platform supplies the loader: the classpath on desktop, later app assets on Android and HTTP on web.
  - Shared by graphics and audio.
- **`audio-core` and `audio-desktop`:**
  - Live in the SpartanGraphics repo but depend on no graphics module.
  - `audio-desktop` ports today's JLayer `SoundPlayer` and loads through `AssetSource`.

### ui-core (KMP)

- **Behaviour is written once here.** Platforms only implement graphics' small set of services, so SpartanUI has no platform modules for now.
- **Basic elements:** each adds a position in its parent, hit-testing and callbacks to a shape. The set is closed; custom elements are built from it.
  - `Box` is a rectangle with a colour.
  - `Image` is a rectangle with a texture.
  - `Text` is a text shape.
  - `Group` holds children, with an optional background and opt-in clipping and scrolling.

  Every basic element exposes its shape's opt-in outline, which is how borders are drawn.
- **Built-in elements:** made from the basic ones.
  - `Label` is a `Box` with `Text`.
  - `Panel` is a `Group` with a background.
  - `Button` is a `Box` with `Text`, plus hover and trigger states and a `shortcut` shorthand (see [Keyboard shortcuts](#keyboard-shortcuts)).
  - `StatBar` is a `Group` of two `Box`es.
  - `Image` (the element) replaces `Portrait`.
- **Positioning:**
  - Positions are fractions of the parent, as today.
  - Sizes can be locked to an aspect ratio. Each element chooses which side wins, defaulting to fit inside and centre.
  - Each element turns itself into screen-layer shapes; that replaces `flatten`.
- **Dispatch:**
  - The front-most element takes a pointer event, unless it opts in to letting it through.
  - Keys go through the key binding tables. Unbound keys go to the unhandled-input sink, and keys are no longer broadcast to every element.
  - Hover is kept; touch platforms simply never fire it.
  - Raw input becomes triggers: elements react to `onTrigger`, not `onClick`. An element's guard condition is checked first and can veto the trigger.
  - A generic sink for unhandled input replaces today's hard-coded `Viewport` special case. The game's viewport becomes an ordinary user of that sink.
- **Callbacks:**
  - Any number per event. Each is told which element fired and what triggered it.
  - Every callback always runs; none can stop the others.
  - People building an element implement its listener, which defines how the element behaves. People using an element attach lambdas.
  - Adding a callback returns a handle that removes it.
- **Threading:** children can be added and removed at runtime, but the element tree is only changed on the render thread.
- **Scenes:** `Scene` and `Stage` become real classes instead of typealiases for `ArrayList` and `HashMap`.
- **No GameTools types.** `Portrait`'s snapshot binding moves to the game client.

### Game client (this repo)

- **Role:** the real, server-connected client, and the libraries' live test. There's no separate demo app.
- **Stays:** `Main` (composition root), the game loop, `Viewport` / `GameView` / `ClickState`, the inspector and menu built from SpartanUI elements, and `EntityLookup`.
- **Assets:** all textures and sounds move here and are loaded through the desktop `AssetSource`.
- **Networking:** GameTools is growing its own networking, so the client half of the protocol belongs there ([MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132)). Until it ships, `NetworkClient` / `ProtocolParsing` / `ClientCommands` stay here.
- **Snapshot adapter:**
  - Converts `DrawableSnapshot` into graphics shapes, using today's `drawableCore()`.
  - Includes adapters from GeneralTools types to the library's basic types. They live here, not in any library, and go away when GeneralTools#7 lands.
  - Opts the selected unit's shape in to the default outline.
- **Uses the library:** edge panning is built on graphics' camera and input. Click markers are ordinary world-layer rectangles. `M` / `A` / `S` are plain actions in the game scene's key table.

## Design notes

### Where the library/game line falls

- **Picking is in graphics-core.** Picking is rendering run backwards. Only the renderer's model knows what is drawn where, including rotation, draw order and camera. The library returns the tag; what a pick means is up to the game.
- **Panning is split three ways:**
  - graphics-core provides the camera and the operations that move it.
  - graphics-core's input provides the cursor position needed to detect the edges.
  - The game client decides that the cursor near an edge pans the camera; that's an RTS convention.
- **Outlines are entirely graphics.** The game only decides which unit is selected and opts its shape in.
- **Click markers are in the game client.** Spawning, fading and the arrow texture are game choices. They're drawn with ordinary world-layer rectangles whose alpha fades.
- **Each platform owns the frame.** On web the browser decides when a frame runs, and on Android the GL view does. So the platform calls `onFrame(time)`, and the game's fixed-timestep loop runs inside it. The loop is still game- or GameTools-driven.
- **Audio has separate artifacts in the SpartanGraphics repo:**
  - It has the same desktop/Android/web split and asset-loading needs as graphics, so it fits that repo.
  - No rendering code touches it, so it isn't part of the graphics modules.
  - Because the artifacts are independent, moving audio to its own repo later wouldn't affect anyone using it.

### Outlines

Outlining is an opt-in capability of graphics-core. Any shape, in the world or screen layer, can carry an outline, and nothing is outlined unless asked.

```kotlin
// The built-in default: today's selection look
unitShape.outline = Outline.Default

// Or a custom style
panelShape.outline = Outline(color = Color.WHITE, margin = 0.0, thickness = 1.0)
```

- **Default style:** today's selection look, so a game gets a working outline with no configuration.
  - Colour: bright green (`0.25, 1.0, 0.35`).
  - Shape: an axis-aligned rectangle around the shape's bounds.
  - Position: 6 px outside the bounds.
  - Thickness: 3 px.
- **Style:**
  - Colour, shape, margin and thickness are all part of the style, and each can be overridden.
  - v1 ships one outline shape, the axis-aligned rectangle. Others (a rectangle that rotates with the object, an ellipse) can be added later without changing the API.
- **Units:** margin and thickness use the shape's own units: world pixels in the world layer, so the outline scales with zoom, and screen pixels in the screen layer.
- **Drawing:**
  - Graphics computes the geometry (today's `SelectionOutline` maths) and draws it on top of its shape with the outlined-rectangle primitive.
  - Because the outline belongs to the shape, it follows pan, zoom and movement for free, and disappears with the shape.
  - A shape with zero or negative size draws no outline, as today.
- **Users:**
  - The game client opts the selected unit in.
  - UI elements use the same capability for borders, and later for focus rings.

### Keyboard shortcuts

All keyboard shortcuts go through one binding table. A `Button` can still declare its key where it's built, through a shorthand that registers into the table.

```kotlin
// Table: bind a key to an element, or to a plain action
gameScene.keys.bind(Key.B, beepButton)
val moveKey = gameScene.keys.bind(Key.M) { viewport.arm(ClickState.MOVE) }
moveKey.remove()

// Shorthand: registers into the table of the scene the button is attached to
val beep = Button("BEEP", shortcut = Key.B)
```

**Why a table:**
- Every shortcut is in one list, which a future rebinding screen, saved keymaps and a help overlay can read.
- Binding a key twice in one table is caught.
- Shortcuts that aren't buttons (`M`, `A`, `S`, a future camera-follow key) use the same mechanism as buttons.
- Each scene gets its own keymap.

**Why the shorthand:** the key stays declared next to the button, and the button can show its own hint ("BEEP (B)").

**Details:**
- **Scope:** each `Scene` has a table, and the `Stage` has one for keys that work in every scene. If both bind the same key, the scene's binding wins.
- **What a binding fires:**
  - An element binding goes through the element's normal trigger path, so the guard still applies. Press and release are both forwarded, so a held key shows the pressed look.
  - An action binding just runs.
- **Handles:** `bind` returns a handle that removes the binding.
- **Conflicts:** binding a key that's already bound in the same table returns a `Result` failure.
- **Lifecycle:** a binding to a detached or hidden element is skipped. The shorthand registers when the button is attached to a scene and unregisters when it's removed. Shorthand bindings take part in conflict checks like any other binding.
- **Rebinding:** a binding's key can be changed in place, which is what a future settings screen needs. Saving keymaps is game work.

### macOS and Linux considerations

LWJGL covers all three OSes (x64 and arm64, including Apple Silicon). The Kotlin code is the same everywhere; only the native jars differ, and the app picks those. Beyond that:

- **macOS, high-DPI:** on Retina screens the framebuffer is twice the window's coordinate size. Today's `Window` uses one size for both drawing and the cursor, so clicks would land in the wrong place. The desktop module keeps the two separate, which is a few lines and harmless on Windows.
- **macOS, main thread:** GLFW must run on the first thread (`-XstartOnFirstThread`). The app's launcher already handles this.
- **macOS, OpenGL:** Apple has deprecated OpenGL and frozen it at 4.1. Our 3.3 core works today, but a future macOS could need a Metal (or MoltenVK) backend.
- **Linux, Wayland:** apps can't position their own windows there, so the current trick of moving a borderless window onto the primary monitor becomes GLFW's windowed full-screen mode.
- **Testing:** CI compiles on all three, but no CI runner can open a window. Releases are run by hand on Windows only; macOS and Linux are "should work, untested."

## Code mapping

| Today | Goes to |
|---|---|
| `graphics/NdcConverter.kt` (`Camera`, `NdcConverter`) | graphics-core `Camera` |
| `graphics/Picking.kt` | graphics-core, operating on shapes |
| `graphics/SelectionOutline.kt` | graphics-core outlines (geometry) |
| `graphics/Shaders.kt`, `graphics/TextureCache.kt` | graphics-desktop |
| `graphics/Window.kt` | <ul><li>Window, context, input and frames go to graphics-desktop.</li><li>Actor and outline drawing go to the graphics-desktop renderer.</li><li>The selection colour, margin and thickness become graphics' default outline style.</li><li>Camera state goes to graphics-core `Camera`.</li><li>Scene routing goes to ui-core.</li><li>Edge panning and click markers go to the game client.</li></ul> |
| `graphics/UiRenderer.kt` | Merged into the graphics-desktop renderer; ui-core emits shapes |
| `graphics/ui/UI.kt` | ui-core; `sealed` is replaced by basic plus built-in elements, and the `Viewport` special case by the unhandled-input sink |
| `graphics/ui/Button.kt` | ui-core: `onClick` becomes `onTrigger`, and the GLFW key becomes the `shortcut` shorthand over the key table |
| `graphics/ui/KeyAction.kt` | Replaced by graphics-core abstract input |
| `graphics/ui/Portrait.kt` | ui-core `Image`; snapshot binding goes to the game client |
| `graphics/ui/Viewport.kt`, `graphics/ui/GameView.kt` | Game client; `M` / `A` / `S` become key-table actions |
| `networking/NetworkClient.kt`, `ProtocolParsing.kt`, `ClientCommands.kt` | Game client until GameTools ships a client |
| `networking/DrawableSnapshots.kt` | Game client snapshot adapter |
| `networking/EntityLookup.kt` | Game client |
| `audio/SoundPlayer.kt` | audio-desktop |
| `Main.kt`, `src/main/resources/**` | Game client |

| Test today | Goes to |
|---|---|
| `NdcConverterTest` | graphics-core (`CameraTest`) |
| `PickingTest` | graphics-core |
| `SelectionOutlineTest` | graphics-core (`OutlineTest`, plus default-style tests) |
| `UiTest` | ui-core, split into one test class per topic (layout, dispatch, hover) |
| `ButtonTest`, `StatBarTest` | ui-core |
| `PortraitTest` | ui-core `ImageTest` + a game-client binding test |
| `ViewportTest`, `EntityLookupTest`, `DrawableSnapshotsTest` | Game client |
| `ClientCommandsTest`, `ProtocolParsingTest` | Game client (deleted when GameTools ships a client) |
| `TextureResourcesTest`, `SoundResourcesTest`, `TestSnapshots` | Game client |

## Build conventions

Each repo:
- **Gradle setup:**
  - Gradle 9 wrapper.
  - A version catalog (`gradle/libs.versions.toml`), with Kotlin pinned to the latest stable release at G1.
  - A `build-logic` included build with convention plugins for a KMP library module, a JVM library module, and publishing.
- **KMP targets:** library modules start with `jvm()` plus one non-JVM target, with no web backend yet. The extra target makes any JVM-only API in common code fail to compile. G1 picks `js` or `wasmJs`, whichever every chosen dependency supports. Android is added in the Android phase.
- **JVM bytecode:** libraries target 21. The game client stays on 23 because GameTools requires it.
- **Logging:** kotlin-logging, which logs through slf4j on the JVM, so logback still works there. This replaces the "libraries use slf4j" rule, since slf4j is JVM-only.
- **Tests:** shared tests use `kotlin.test` with hand-written fakes. JUnit 5 and MockK are still used in JVM-only tests.
- **Artifacts:** `graphics-core`, `graphics-desktop`, `assets-core`, `audio-core`, `audio-desktop` and `ui-core`.
- **Publishing:** copies GeneralTools' setup: the `com.vanniktech.maven.publish` plugin with Central Portal and signing, released by hand with no publish workflow.
- **CI:** GitHub Actions. A `windows-latest` job runs the full build and tests; `macos-latest` and `ubuntu-latest` jobs compile only.
- **Coding rules:** `.aiassistant/rules` is copied into each new repo, with the logging and testing rules adjusted as above.
- **Branches:** one per PR, named `<type>/<pr-id>-<slug>` (e.g. `feature/g1-build-skeleton`). This matches the `feature/…` and `chore/…` branches these repos already use.

## PR sequence

Rules for every PR:
- It compiles and its tests pass.
- It follows the coding rules: KDoc, `Result` for expected failures, structured logging, one test class per file, import grouping.
- Between Phase 3 PRs, game features may be missing.

### Phase 0: groundwork

| Item | Content | Status |
|---|---|---|
| **P0** | This plan, on `claude/eloquent-babbage-69uh9q` in this repo | Written; no PR opened |
| Issue | [GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7): make GeneralTools multiplatform | Filed |
| Issue | [MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132): a GameTools client, counterpart to `GameServer` | Filed |
| **You** | Create `SpartanLaboratories/SpartanGraphics` and `SpartanLaboratories/SpartanUI` | Done |
| **You** | Rename this repo to `MyGameClient` in GitHub settings | Any time |

### Phase 1: SpartanGraphics

| PR | Content | Depends on |
|---|---|---|
| **G1** | Skeleton: wrapper, catalog, `build-logic`, empty `graphics-core` (KMP) and `graphics-desktop` (JVM), CI, publishing config, coding rules | — |
| **G2** | Basic types (contracts + default implementations) and `Camera` (world/screen conversion, zoom, pan, centre on, visible region); `CameraTest` ported from `NdcConverterTest` | G1 |
| **G3** | Shapes, world and screen layers, culling, picking, opt-in outlines with the default style; `PickingTest` and `SelectionOutlineTest` ported | G2 |
| **G4** | Abstract input (key ids, pointer info) and platform contracts: surface, renderer, textures, text measurement, frames and clock, input source; new `assets-core` module with `AssetSource` | G3 |
| **G5** | `graphics-desktop`: GLFW window, single GL 3.3 renderer (shapes and outlines), textures, STBEasyFont text, classpath assets, frame loop, GLFW input, the macOS/Linux handling. Can't be unit-tested without a GPU; verified through C1. | G4 |
| **G6** | `audio-core` + `audio-desktop`, loading through `assets-core` | G4 |
| **G7** | Release `0.1.0` (manual, by you) | G1–G6 |

### Phase 2: SpartanUI

| PR | Content | Depends on |
|---|---|---|
| **U1** | Skeleton, same conventions, `ui-core` with `api(graphics-core)` through the composite build | G1 |
| **U2** | Element tree: basic elements on shapes (with their opt-in outlines), `Group` with runtime add/remove and opt-in clipping, fractional positioning, aspect lock, conversion to shapes; layout tests from `UiTest` ported | U1, G3 |
| **U3** | Dispatch and callbacks: pointer routing, opt-in pass-through, hover, triggers, guard veto, multiple callbacks with context and removable handles, the unhandled-input sink, scene and stage key binding tables; dispatch tests ported | U2, G4 |
| **U4** | Built-in elements: `Label`, `Panel`, `Button` (with the `shortcut` shorthand), `StatBar`, `Image`; `ButtonTest`, `StatBarTest`, `PortraitTest` ported | U3 |
| **U5** | Opt-in scrolling for `Group` | U2 |
| **U6** | Release `0.1.0` (manual, by you) | U1–U5, G7 |

### Phase 3: game client (this repo)

| PR | Content | Depends on |
|---|---|---|
| **C1** | Remove all library code from this repo. Depend on graphics-desktop and ui-core through the composite build. Snapshot adapter plus GeneralTools adapters. A minimal `Main` opens a window and draws the world from `STATE` on the platform frame driver. Networking untouched. | G5 |
| **C2** | Game UI on SpartanUI: viewport as the unhandled-input sink, `GameView`, `ClickState`, inspector and menu scenes, `M` / `A` / `S` / `B` through the key tables; `ViewportTest` ported | C1, U4 |
| **C3** | Game visuals: edge panning on the library camera and input, click markers, and the selected unit opted in to the default outline. Update or retire `camera-follow-selection.md`, which is written against `Window`. | C1 |
| **C4** | Audio through `audio-desktop` | C1, G6 |
| **C5** | README and docs rewrite; mark superseded plans | C1–C4 |
| **Later** | Replace `NetworkClient` with GameTools' client when it ships | [MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132) |

The game client isn't fully playable again until C2–C3. Its old feature set (selection, orders, inspector, markers, outline) is restored by C3, and sound by C4.

## Not in this plan

These were deferred, or belong to another repo. Each needs its own plan when its time comes.

- **Android and web backends** (`*-android`, `*-web` modules), and running the game client on Android.
- **Official macOS and Linux support.**
- **Layout features:** layout containers, fixed-pixel sizes (only if a user needs them), focus and gamepad navigation.
- **Drawing features:** real fonts; shapes beyond the three (lines, circles, polygons, rounded corners, gradients, 9-slice, custom shaders); outline shapes beyond the axis-aligned rectangle.
- **A rebinding screen and saved keymaps.** The key table supports both; the screens are game work.
- **Upstream work:** the GameTools client itself ([MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132)), and multiplatform GeneralTools ([GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7)).

## Risks

- **Composite builds with KMP:** Gradle substitutes included KMP builds in modern Gradle and Kotlin, but this path has had rough edges. U1 and C1 prove it early, before anything depends on it.
- **No GPU in CI or in cloud sessions:**
  - `graphics-desktop` can be compiled but not run there, as is true of `Window` today.
  - The pure logic (camera, picking, outlines, layout, dispatch, key tables) stays in common code and gets full unit tests.
  - The renderer is verified by running the game client by hand.
- **JDK 23 for the game client:** GameTools needs a JVM 23 runtime. This cloud environment has only JDK 21, and the toolchain download is blocked, so the game client can't build here until JDK 23 is provided (setup script or environment). The libraries on 21 aren't affected.
- **No playable build from C1 to C3:** accepted.
- **API churn across repos:** the composite build makes iterating cheap, and nothing is released until G7 and U6.
- **GeneralTools#7 will change the adapters:** that's contained to the game client by design.
- **GameTools 6.0.0 changes the wire format** ([MyGameTools#124](https://github.com/SpartanLabsGaming/MyGameTools/issues/124): framed `webtools-udp` 2.0.0). The interim `NetworkClient` breaks then. The options are:
  - #132 ships alongside #124, which is what the issue suggests;
  - the game client stays on GameTools 5.x until #132 ships;
  - `NetworkClient` gets ported onto `MultiConnectionUDPClient` as a stopgap.
- **macOS OpenGL deprecation:** not a problem while only Windows is supported, but official macOS support could one day need a second rendering backend.

## Effect on existing plan docs

- `camera-follow-selection.md` (proposed) targets `Window` / `panOffset`. C3 either implements it on `Camera` (bound through the key table) or marks it superseded.
- `in-world-selection-highlight.md` and `phase-1-select-by-entity-id.md` (implemented) stay as history. Their behaviour is carried by C2–C3, with the outline now coming from graphics' default style.
