# Split into SpartanGraphics, SpartanUI and the game client

**Status:** planned. Every question from the planning interview is resolved. Implementation starts when you give the go-ahead.
**Spans:**
- `SpartanLaboratories/SpartanGraphics` (new)
- `SpartanLaboratories/SpartanUI` (new)
- `SpartanLabsGaming/GameGraphics` (this repo). It becomes the game client only and will be renamed `MyGameClient` (D34).

**Related:**
- [SpartanLaboratories/GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7): make GeneralTools multiplatform
- [SpartanLabsGaming/MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132): a GameTools client, counterpart to `GameServer`
- [SpartanLabsGaming/MyGameTools#124](https://github.com/SpartanLabsGaming/MyGameTools/issues/124): webtools-udp 2.0.0, a new wire format in GameTools 6.0.0

Items are labelled as one of two kinds:
- **Decided** (D1–D36): you answered it in the planning interview.
- **Proposed:** my engineering call. Veto anything you disagree with.

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

Breaking the current game while this happens is acceptable (D18).

## Decisions

| # | Decision |
|---|---|
| D1 | **Separate repositories:** `SpartanLaboratories/SpartanGraphics` and `SpartanLaboratories/SpartanUI` are new; this repo stays in SpartanLabsGaming as the game client. |
| D2 | **Publishing:** UI and graphics are versioned separately and published to Maven Central under `io.github.spartanlaboratories`. |
| D3 | **Platforms:** Windows now, then Android, then web, then others. Libraries are Kotlin Multiplatform. |
| D4 | **Platform split:** platform modules are split by device family (`desktop`, `android`, `web`), one Gradle module and artifact per platform, not source sets inside one module. |
| D5 | **Packages:** `com.spartanlabs.graphics` and `com.spartanlabs.ui`. |
| D6 | **Element behaviour:** written once in ui-core. Platforms implement only a small set of services. |
| D7 | **Elements:** a small set of basic elements that custom elements are built from. The common elements ship built from the basic ones. |
| D8 | **Input:** raw input is translated into abstract input events. The UI turns those into triggers (`onTrigger`, not `onClick`). Where each layer lives is D23. |
| D9 | **Callbacks:** any number per event. Each one is told which element fired and what triggered it. A listener covers core behaviour and lambdas cover extras (D33). |
| D10 | **Event handling:** the front-most element takes a pointer event by default, and an element can opt in to let it through. Every callback on an event always runs; none can stop the others. Vetoing is a single guard condition on the element, checked before triggering. |
| D11 | **Sizing:** positions are fractions of the parent, and sizes can be locked to an aspect ratio. Fixed-pixel sizes come later, if a user needs them. Clipping and scrolling are opt-in. Children can be added and removed at runtime. Layout containers come later. |
| D12 | **GameTools snapshots:** graphics has its own drawing model. An adapter maps GameTools snapshots onto it. |
| D13 | **UI depends on graphics.** UI's basic elements are built on graphics shapes, and the built-in elements are built from the basic ones. |
| D14 | **Library or game:** the library renders the part of the world visible through a camera (position and zoom). The game loop belongs to the game or to GameTools. Window creation is per-platform. |
| D15 | **Networking** goes to GameTools, which is already growing more networking and preset commands. |
| D16 | **The game client** stays the real, server-connected client. There is no separate demo app. |
| D17 | **Assets:** they move to the game client and are still referenced by name. Loading goes through an `AssetSource` interface. |
| D18 | **Breakage:** breaking the current project is acceptable if it leads to a better-planned result. |
| D19 | **Delivery:** this plan first, then a series of PRs. |
| D20 | **Cross-repo development:** a Gradle composite build (`includeBuild` of local checkouts) for unreleased library changes. |
| D21 | **JVM-only types:** contract interfaces for now, declared in graphics-core and ui-core, with GeneralTools and GameTools types wrapped in adapters. Longer term, GeneralTools goes multiplatform ([#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7)) and GameTools adopts it. |
| D22 | **Library or game, in detail:** <ul><li>Picking is in graphics-core.</li><li>The camera, and the ability to move it, are in graphics-core.</li><li>The input needed to detect panning (such as cursor position) comes from graphics-core.</li><li>The panning behaviour itself (edge panning) is in the game client.</li><li>The outline shape is in graphics-core; choosing what to outline is in the game.</li><li>Click markers are in the game client.</li></ul> |
| D23 | **Input layers:** abstract input lives in graphics-core. UI-specific input (hit-testing, hover, triggers, shortcuts, focus) is layered on top in ui-core. So SpartanUI needs no platform modules for now. |
| D24 | **Frames:** each platform module owns frame scheduling and the clock, and calls the game once per frame. The game's (or GameTools') fixed-timestep loop runs inside that call. |
| D25 | **Audio:** separate `audio-core` and `audio-desktop` artifacts, kept in the SpartanGraphics repo and independent of every graphics module. `AssetSource` lives in a small shared `assets-core` module that both audio and graphics use. |
| D26 | **Adapters:** the GameTools snapshot adapter and the GeneralTools adapters live in the game client, not in any library. They're removed once GeneralTools#7 lands. |
| D27 | **Element behaviour:** adding a callback returns a handle that removes it. Each element chooses its aspect-lock rule; the default is fit inside and centre. The element tree is changed only on the render thread. |
| D28 | **Multiplatform house rules:** <ul><li>Logging uses kotlin-logging, which logs through slf4j on the JVM.</li><li>Shared tests use `kotlin.test` with hand-written fakes; MockK is still allowed in JVM-only tests.</li><li>Text v1 ports the STBEasyFont bitmap font.</li><li>Libraries target JVM 21.</li></ul> |
| D29 | **GameTools client:** an issue is filed ([MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132)); `NetworkClient` stays in the game client until it ships. |
| D30 | **Desktop scope:** only Windows is officially supported. The desktop module still handles the [macOS and Linux considerations](#macos-and-linux-considerations-d30), and CI compiles on all three. |
| D31 | **Shapes and elements:** the closed set of three shapes (rectangle, text, outlined rectangle) and the basic and built-in elements listed under ui-core. |
| D32 | **Input details:** <ul><li>Abstract key ids and pointer information (position, buttons, scroll) are the library's own, in graphics-core.</li><li>Binding keys to UI elements happens in ui-core (D35).</li><li>Hover stays as a UI concept that touch platforms never fire.</li><li>Focus and gamepad navigation come later.</li></ul> |
| D33 | **Listener vs. lambdas:** people building an element implement its listener, which defines how the element behaves; people using an element attach lambdas. |
| D34 | **Repo name:** this repo becomes `MyGameClient`, pairing with `MyGameServer` and `MyGameTools`. You rename it in GitHub settings; old URLs redirect. |
| D35 | **Keyboard shortcuts:** a binding table, plus a `Button` shorthand that registers into the table. See [Keyboard shortcuts](#keyboard-shortcuts-d35). |
| D36 | **Branches:** I pick the names. One branch per PR, named `<type>/<pr-id>-<slug>`, e.g. `feature/g1-build-skeleton`, matching the `feature/…` and `chore/…` branches these repos already use. |

## Target architecture

```
SpartanLaboratories/SpartanGraphics            io.github.spartanlaboratories, com.spartanlabs.graphics
  graphics-core     KMP   basic types, camera, shapes, picking, abstract input, platform contracts
  graphics-desktop  JVM   LWJGL: GLFW window, OpenGL 3.3 renderer, textures, text, input, frames
  assets-core       KMP   AssetSource (name to bytes), shared by graphics and audio
  audio-core        KMP   sound contract
  audio-desktop     JVM   JLayer MP3 playback

SpartanLaboratories/SpartanUI                  io.github.spartanlaboratories, com.spartanlabs.ui
  ui-core           KMP   elements, layout, dispatch, triggers, callbacks, key bindings   api(graphics-core)
  (no platform modules for now: all platform work is in SpartanGraphics, D23)

SpartanLabsGaming/MyGameClient (this repo)     the GameTools RTS client, JVM 23
  app               Main, game loop, snapshot adapter, game UI, game rules,
                    interim networking, edge panning / markers / outline, assets
```

```
game client ──> ui-core ──api──> graphics-core ──> assets-core
     │────────> graphics-desktop ──> graphics-core
     │────────> audio-desktop ──> audio-core ──> assets-core
     └────────> gametools ──> GeneralTools, WebTools
```

Neither library depends on GameTools, GeneralTools or WebTools. The game client is the only place where GameTools types meet library types.

### graphics-core (KMP)

- **Basic types (D21):** `Color`, `Point`, `Size` and `Rect` contract interfaces, each with a default immutable implementation, so the library can build its own values.
- **Camera (D14, D22):**
  - Holds position, zoom and viewport size.
  - Converts between world and screen coordinates.
  - Reports the visible world region, which drives culling.
  - Exposes movement operations: pan by an amount, zoom, centre on a world point.
  - Ports today's `Camera` / `NdcConverter` and fixes their wrong `networking` package.
- **Shapes (D31):** what every platform renderer has to draw. They sit in two layers:
  - The **world layer** is drawn through the camera.
  - The **screen layer** uses window pixels and holds the UI.

  The set is closed: users build anything from these, but can't add a fourth.
  - **Rectangle:** a solid colour, or a texture tinted by that colour, with rotation and alpha. Today's actors, panels, bar fills, portraits and click markers are all this.
  - **Text:** a string with colour and alignment, in a fixed-size bitmap font in v1.
  - **Outlined rectangle:** a border of a given thickness. It covers the selection outline, UI borders and focus rings.

  A world shape can carry a tag supplied by the caller, which picking returns.
- **Picking (D22):** finds the top-most tagged world shape under a screen point. It ports `Picking` and works on shapes instead of `VisibleObjectSnapshot`.
- **Abstract input (D23, D32):** pointer position, buttons and scroll, and keys, all with the library's own ids (`Key.A`; `PointerButton.Primary` / `Secondary` / `Middle`). This is enough for a game to build edge panning, and it's what ui-core builds on.
- **Platform contracts:**
  - `Surface` (size, resize, close request)
  - `Renderer` (draw one frame of shapes)
  - textures
  - text measurement
  - frame scheduling and clock (D24)
  - input source (delivers the abstract input above)
  - `AssetSource` (D17), from `assets-core` (D25)

### graphics-desktop (JVM, LWJGL)

- **Window:** the GLFW window and GL context, taken from `Window.initWindow` / `initOpenGl`.
- **Renderer:** one OpenGL 3.3 renderer for every shape. It merges today's two pipelines (the actor drawing in `Window` and the quad and text drawing in `UiRenderer`).
- **Textures and text:** `TextureCache` and `Shaders` move here as-is. Text uses STBEasyFont for v1 (D28).
- **Assets:** an `AssetSource` that loads from the classpath.
- **Frames and input:** a desktop frame loop on `glfwGetTime`, and GLFW input translated into the abstract input.
- **Cross-platform considerations (D30):**
  - Window size (for input) and framebuffer size (for drawing) are kept separate.
  - GLFW's windowed full-screen mode replaces moving a borderless window onto the monitor.
  - On macOS the app starts on the first thread.
- **LWJGL native libraries:** the library declares only the LWJGL Java APIs. The application adds the natives for its OS. Today the build picks natives from the build machine's OS, which is right for an app but wrong for a library.

### ui-core (KMP)

- **Basic elements (D7, D13, D31):** each adds a position in its parent, hit-testing and callbacks to a shape. The set is closed; custom elements are built from it.
  - `Box` is a rectangle with a colour.
  - `Image` is a rectangle with a texture.
  - `Text` is a text shape.
  - `Group` holds children (added and removed at runtime), with an optional background and opt-in clipping and scrolling.
- **Built-in elements:** made from the basic ones.
  - `Label` is a `Box` with `Text`.
  - `Panel` is a `Group` with a background.
  - `Button` is a `Box` with `Text`, plus hover and trigger states, and the `shortcut` shorthand (D35).
  - `StatBar` is a `Group` of two `Box`es.
  - `Image` (the element) replaces `Portrait`.
- **Positioning (D11, D27):** positions are fractions of the parent, as today. Sizes can be locked to an aspect ratio; each element chooses which side wins, defaulting to fit inside and centre. Each element turns itself into screen-layer shapes; that replaces `flatten`.
- **Dispatch (D8, D10):**
  - The front-most element takes a pointer event unless it opts to let it through.
  - Keys go through the key binding tables (D35). Unbound keys go to the unhandled-input sink. Keys are no longer broadcast to every element.
  - Hover and triggers work as in D8. The guard condition can veto a trigger.
  - A generic sink for unhandled input replaces today's hard-coded `Viewport` special case. The game's viewport becomes an ordinary user of that sink.
- **Callbacks (D9, D27, D33):** any number per event, each told the element and the trigger source. People building an element implement its listener, and people using it attach lambdas. Adding a callback returns a handle that removes it.
- **Scenes:** `Scene` and `Stage` become real classes instead of typealiases for `ArrayList` and `HashMap`.
- **No GameTools types.** `Portrait`'s snapshot binding moves to the game client.

### Game client (this repo)

- **Stays:** `Main` (composition root), the game loop (D14), `Viewport` / `GameView` / `ClickState`, the inspector and menu built from SpartanUI elements, `EntityLookup`, and all textures and sounds.
- **Interim:** `NetworkClient` / `ProtocolParsing` / `ClientCommands` stay here until GameTools ships a client (D29).
- **Snapshot adapter (D12, D26):** converts `DrawableSnapshot` into graphics shapes, using today's `drawableCore()` and GeneralTools-to-contract adapters.
- **Uses the library (D22):** edge panning, click markers and the selection outline are game features built on graphics-core's camera, input and shapes. `M` / `A` / `S` are plain actions in the game scene's key table (D35).

## Design notes

### Why the library/game split falls where it does (D22, D24, D25)

- **Picking is in graphics-core.** Picking is rendering run backwards. Only the renderer's model knows what is drawn where, including rotation, draw order and camera. The library returns the tag; what a pick means is up to the game.
- **Panning is split three ways.**
  - graphics-core provides the camera and the operations that move it.
  - graphics-core's input provides the cursor position needed to detect the edges.
  - The game client decides that the cursor near an edge pans the camera; that's an RTS convention.
- **The selection outline is split.** The geometry becomes graphics-core's outlined-rectangle shape (porting `SelectionOutline`). Which unit to outline, and in what colour, stays in the game.
- **Click markers are in the game client.** Spawning, fading and the arrow texture are game choices. They're drawn with ordinary world-layer rectangles whose alpha fades.
- **Each platform owns the frame.** On web the browser decides when a frame runs, and on Android the GL view does. So the platform module owns scheduling and calls `onFrame(time)`. The game's fixed-timestep loop runs inside that call, so the loop stays game- or GameTools-driven.
- **Audio has separate artifacts in the SpartanGraphics repo.**
  - It has the same desktop/Android/web split and asset-loading needs as graphics, so it fits that repo.
  - No rendering code touches it, so it isn't part of the graphics modules.
  - Because the artifacts are independent, moving audio to its own repo later wouldn't affect anyone using it.

### Keyboard shortcuts (D35)

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

Details (proposed):
- **Scope:** each `Scene` has a table, and the `Stage` has one for keys that work in every scene. If both bind the same key, the scene's binding wins.
- **What a binding fires:**
  - An element binding goes through the element's normal trigger path, so the guard still applies. Press and release are both forwarded, so a held key shows the pressed look.
  - An action binding just runs.
- **Handles:** `bind` returns a handle that removes the binding (D27).
- **Conflicts:** binding a key that's already bound in the same table returns a `Result` failure (house error-handling rule).
- **Lifecycle:** a binding to a detached or hidden element is skipped. The shorthand registers when the button is attached to a scene and unregisters when it's removed. Shorthand bindings take part in conflict checks like any other binding.
- **Rebinding:** a binding's key can be changed in place, which is what a future settings screen needs. Saving keymaps is game work.

### macOS and Linux considerations (D30)

LWJGL covers all three OSes (x64 and arm64, including Apple Silicon). The Kotlin code is the same everywhere; only the native jars differ, and the app picks those. Beyond that:

- **macOS, high-DPI.** On Retina screens the framebuffer is twice the window's coordinate size. Today's `Window` uses one size for both drawing and the cursor, so clicks would land in the wrong place. The desktop module keeps the two separate, which is a few lines and harmless on Windows.
- **macOS, main thread.** GLFW must run on the first thread (`-XstartOnFirstThread`). The app's launcher already handles this.
- **macOS, OpenGL.** Apple has deprecated OpenGL and frozen it at 4.1. Our 3.3 core works today, but a future macOS could need a Metal (or MoltenVK) backend.
- **Linux, Wayland.** Apps can't position their own windows there, so the current trick of moving a borderless window onto the primary monitor becomes GLFW's windowed full-screen mode.
- **Testing.** CI compiles on all three, but no CI runner can open a window. Releases are run by hand on Windows only; macOS and Linux are "should work, untested."

## Code mapping

| Today | Goes to |
|---|---|
| `graphics/NdcConverter.kt` (`Camera`, `NdcConverter`) | graphics-core `Camera` |
| `graphics/Picking.kt` | graphics-core, operating on shapes |
| `graphics/SelectionOutline.kt` | Geometry goes to graphics-core's outlined rectangle; "outline the selection" goes to the game client |
| `graphics/Shaders.kt`, `graphics/TextureCache.kt` | graphics-desktop |
| `graphics/Window.kt` | Window, context, input and frames go to graphics-desktop. Actor drawing goes to the graphics-desktop renderer. Camera state goes to graphics-core `Camera`. Scene routing goes to ui-core. Edge panning, markers and outline go to the game client. |
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
| `PickingTest`, `SelectionOutlineTest` | graphics-core |
| `UiTest` | ui-core, split into one test class per topic (layout, dispatch, hover) |
| `ButtonTest`, `StatBarTest` | ui-core |
| `PortraitTest` | ui-core `ImageTest` + a game-client binding test |
| `ViewportTest`, `EntityLookupTest`, `DrawableSnapshotsTest` | Game client |
| `ClientCommandsTest`, `ProtocolParsingTest` | Game client (deleted when GameTools ships a client) |
| `TextureResourcesTest`, `SoundResourcesTest`, `TestSnapshots` | Game client |

## Build conventions (proposed)

Each repo:
- **Gradle setup:**
  - Gradle 9 wrapper.
  - A version catalog (`gradle/libs.versions.toml`), with Kotlin pinned to the latest stable release at G1.
  - A `build-logic` included build with convention plugins for a KMP library module, a JVM library module, and publishing.
- **KMP targets:** library modules start with `jvm()` plus one non-JVM target, with no web backend yet. The extra target makes any JVM-only API in common code fail to compile. G1 picks `js` or `wasmJs`, whichever every chosen dependency supports. Android is added in the Android phase.
- **JVM bytecode:** libraries target 21 (D28). The game client stays on 23 because GameTools requires it.
- **Artifacts:**
  - Names: `graphics-core`, `graphics-desktop`, `assets-core`, `audio-core`, `audio-desktop` and `ui-core`, all under `io.github.spartanlaboratories`.
  - Semantic versioning, starting at `0.1.0`.
  - Apache 2.0 licence, as GeneralTools uses, unless the new repos were created with a different one.
- **Publishing:** copies GeneralTools' setup. It uses the `com.vanniktech.maven.publish` plugin with Central Portal and signing, and the maintainer releases by hand; there's no publish workflow.
- **CI:** GitHub Actions. A `windows-latest` job runs the full build and tests; `macos-latest` and `ubuntu-latest` jobs compile only (D30).
- **Coding rules:** `.aiassistant/rules` is copied into each new repo. The logging and testing rules are adjusted for multiplatform as in D28.
- **Branches:** one per PR, per D36.

The game client's `settings.gradle.kts` includes `../SpartanGraphics` and `../SpartanUI` with `includeBuild` when those checkouts exist. Otherwise it falls back to published versions (D20).

## PR sequence

Rules for every PR:
- It compiles and its tests pass.
- It follows the coding rules: KDoc, `Result` for expected failures, structured logging, one test class per file, import grouping.
- Between Phase 3 PRs, game features may be missing (D18).

### Phase 0: groundwork

| Item | Content | Status |
|---|---|---|
| **P0** | This plan, on `claude/eloquent-babbage-69uh9q` in this repo | Written; no PR opened |
| Issue | [GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7): make GeneralTools multiplatform | Filed |
| Issue | [MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132): a GameTools client, counterpart to `GameServer` | Filed |
| **You** | Create `SpartanLaboratories/SpartanGraphics` and `SpartanLaboratories/SpartanUI` | Done |
| **You** | Rename this repo to `MyGameClient` in GitHub settings (D34) | Any time |

### Phase 1: SpartanGraphics

| PR | Content | Depends on |
|---|---|---|
| **G1** | Skeleton: wrapper, catalog, `build-logic`, empty `graphics-core` (KMP) and `graphics-desktop` (JVM), CI, publishing config, coding rules | — |
| **G2** | Basic types (contracts + default implementations) and `Camera` (world/screen conversion, zoom, pan, centre on, visible region); `CameraTest` ported from `NdcConverterTest` | G1 |
| **G3** | Shapes, world and screen layers, culling, picking, outline geometry; `PickingTest` and `SelectionOutlineTest` ported | G2 |
| **G4** | Abstract input (key ids, pointer info) and platform contracts: surface, renderer, textures, text measurement, frames and clock, input source; new `assets-core` module with `AssetSource` | G3 |
| **G5** | `graphics-desktop`: GLFW window, single GL 3.3 renderer, textures, STBEasyFont text, classpath assets, frame loop, GLFW input, the macOS/Linux considerations. Can't be unit-tested without a GPU; verified through C1. | G4 |
| **G6** | `audio-core` + `audio-desktop`, loading through `assets-core` | G4 |
| **G7** | Release `0.1.0` (manual, by you) | G1–G6 |

### Phase 2: SpartanUI

| PR | Content | Depends on |
|---|---|---|
| **U1** | Skeleton, same conventions, `ui-core` with `api(graphics-core)` through the composite build | G1 |
| **U2** | Element tree: basic elements on shapes, `Group` with runtime add/remove and opt-in clipping, fractional positioning, aspect lock, conversion to shapes; layout tests from `UiTest` ported | U1, G3 |
| **U3** | Dispatch and callbacks: pointer routing, opt-in pass-through, hover, triggers, guard veto, multiple callbacks with context and removable handles, the unhandled-input sink, scene and stage key binding tables; dispatch tests ported | U2, G4 |
| **U4** | Built-in elements: `Label`, `Panel`, `Button` (with the `shortcut` shorthand), `StatBar`, `Image`; `ButtonTest`, `StatBarTest`, `PortraitTest` ported | U3 |
| **U5** | Opt-in scrolling for `Group` | U2 |
| **U6** | Release `0.1.0` (manual, by you) | U1–U5, G7 |

### Phase 3: game client (this repo)

| PR | Content | Depends on |
|---|---|---|
| **C1** | Remove all library code from this repo. Depend on graphics-desktop and ui-core through the composite build. Snapshot adapter plus GeneralTools adapters. A minimal `Main` opens a window and draws the world from `STATE` on the platform frame driver. Networking untouched. | G5 |
| **C2** | Game UI on SpartanUI: viewport as the unhandled-input sink, `GameView`, `ClickState`, inspector and menu scenes, `M` / `A` / `S` / `B` through the key tables; `ViewportTest` ported | C1, U4 |
| **C3** | Game visuals on the library camera, input and shapes: edge panning, click markers, selection outline. Update or retire `camera-follow-selection.md`, which is written against `Window`. | C1 |
| **C4** | Audio through `audio-desktop` | C1, G6 |
| **C5** | README and docs rewrite; mark superseded plans | C1–C4 |
| **Later** | Replace `NetworkClient` with GameTools' client when it ships | [MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132) |

The game client isn't fully playable again until C2–C3. Its old feature set (selection, orders, inspector, markers, outline) is restored by C3, and sound by C4.

## Not in this plan

These were deferred in the interview or belong to another repo. Each needs its own plan when its time comes.

- **Android and web backends** (`*-android`, `*-web` modules) and running the game client on Android.
- **Official macOS and Linux support.**
- **Layout containers, fixed-pixel sizes, focus and gamepad navigation.**
- **Real fonts, and shapes beyond the three** (lines, circles, polygons, rounded corners, gradients, 9-slice, custom shaders).
- **A rebinding screen and saved keymaps.** The key table supports both; the screens are game work.
- **The GameTools client itself** ([MyGameTools#132](https://github.com/SpartanLabsGaming/MyGameTools/issues/132)) and **multiplatform GeneralTools** ([GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7)).

## Risks

- **Composite builds with KMP.** Gradle substitutes included KMP builds in modern Gradle and Kotlin, but this path has had rough edges. U1 and C1 prove it early, before anything depends on it.
- **No GPU in CI or in cloud sessions.** `graphics-desktop` can be compiled but not run there, as `Window` is today. The pure logic (camera, picking, outline, layout, dispatch, key tables) stays in common code and gets full unit tests. The renderer is verified by running the game client by hand.
- **JDK 23 for the game client.** GameTools needs a JVM 23 runtime. This cloud environment has only JDK 21, and the toolchain download is blocked, so the game client can't build here until JDK 23 is provided (setup script or environment). The libraries on 21 aren't affected.
- **No playable build from C1 to C3.** Accepted (D18).
- **API churn across repos.** The composite build makes iterating cheap, and nothing is released until G7 and U6.
- **GeneralTools#7 will change the adapters.** That's contained to the game client by design (D26).
- **GameTools 6.0.0 changes the wire format** ([MyGameTools#124](https://github.com/SpartanLabsGaming/MyGameTools/issues/124): framed `webtools-udp` 2.0.0). The interim `NetworkClient` breaks then. Options:
  - #132 ships alongside #124, which is what the issue suggests;
  - the game client stays on GameTools 5.x until #132 ships;
  - `NetworkClient` gets ported onto `MultiConnectionUDPClient` as a stopgap.
- **macOS OpenGL deprecation.** Not a problem while only Windows is supported, but official macOS support could one day need a second rendering backend.

## Effect on existing plan docs

- `camera-follow-selection.md` (proposed) targets `Window` / `panOffset`. C3 either implements it on `Camera` (bound through the key table) or marks it superseded.
- `in-world-selection-highlight.md` and `phase-1-select-by-entity-id.md` (implemented) stay as history. Their behaviour is carried by C2–C3.
