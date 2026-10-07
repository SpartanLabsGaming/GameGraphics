# Split into SpartanGraphics, SpartanUI and the game client

**Status:** proposed. Some questions are still open (see [Open questions](#open-questions)); each PR below lists the ones that block it.
**Spans:** `SpartanLaboratories/SpartanGraphics` (new), `SpartanLaboratories/SpartanUI` (new),
`SpartanLabsGaming/GameGraphics` (this repo, becomes the game client only)
**Related:** [SpartanLaboratories/GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7)
(make GeneralTools multiplatform)

Each item is labelled as one of three kinds:
- **Decided:** you answered it in the planning interview.
- **Proposed:** my engineering call. Veto anything you disagree with.
- **Open:** I need your answer.

## Problem

This repo is a rough-draft client for GameTools (`SpartanLabsGaming/MyGameTools`). One JVM module mixes:
- a reusable UI toolkit,
- an OpenGL renderer,
- GLFW windowing and input,
- UDP networking,
- audio,
- RTS game logic.

None of it can be reused by another game. None of it can move to Android or web. Specifically:
- **The UI's input type comes from the networking artifact.** `Element.onMouseAction` takes GameTools' `MouseAction`, which ships in `gametools-net`.
- **The "generic" UI contains GLFW codes.** `Button.key` and `KeyAction.key` are GLFW key codes, and `Button` / `Viewport` hard-code GLFW mouse-button numbers.
- **`Element` is `sealed`,** so no one outside this module can add an element type.
- **`Window` is 820 lines doing several jobs.** It owns the window, the GL context, the actor pipeline, the camera, input translation, scene routing, picking, click markers and the selection outline.
- **The public API is JVM-only.** It exposes GeneralTools and GameTools types, which are JVM-only jars. gametools-core also brings in WebTools, the UDP library.

## Goal

1. **SpartanGraphics:** a Kotlin Multiplatform graphics library with no knowledge of GameTools or of any game.
2. **SpartanUI:** a Kotlin Multiplatform UI library built on SpartanGraphics, with no knowledge of its users.
3. **This repo:** the GameTools RTS client, rebuilt on both libraries. It doubles as their live test.

Breaking the current game while this happens is acceptable (decision 18).

## Decisions so far

| # | Decision |
|---|---|
| D1 | **Separate repositories:** `SpartanLaboratories/SpartanGraphics` and `SpartanLaboratories/SpartanUI` are new; this repo stays in SpartanLabsGaming as the game client. |
| D2 | **Publishing:** UI and graphics are versioned separately and published to Maven Central under `io.github.spartanlaboratories`. |
| D3 | **Platforms:** Windows now, then Android, then web, then others. Libraries are Kotlin Multiplatform. |
| D4 | **Platform split:** platform modules are split by device family (`desktop`, `android`, `web`), one Gradle module and artifact per platform, not source sets inside one module. |
| D5 | **Packages:** `com.spartanlabs.graphics` and `com.spartanlabs.ui`. |
| D6 | **Element behaviour:** written once in ui-core. Platforms implement only a small set of services. |
| D7 | **Elements:** a small set of basic elements that custom elements are built from. The common elements ship built from the basic ones. |
| D8 | **Input:** raw input is translated into abstract input events. The UI turns those into triggers (`onTrigger`, not `onClick`). |
| D9 | **Callbacks:** any number per event. Each one is told which element fired and what triggered it. A listener interface covers core behaviour and lambdas cover extras (exact split is open, O4). |
| D10 | **Event handling:** the front-most element takes a pointer event by default, and an element can opt in to let it through. Every callback on an event always runs; none can stop the others. Vetoing is a single guard condition on the element, checked before triggering. |
| D11 | **Sizing:** positions are fractions of the parent, and sizes can be locked to an aspect ratio. Fixed-pixel sizes come later, if a user needs them. Clipping and scrolling are opt-in. Children can be added and removed at runtime. Layout containers come later. |
| D12 | **GameTools snapshots:** graphics has its own drawing model. An adapter maps GameTools snapshots onto it. |
| D13 | **UI depends on graphics.** UI's basic elements are built on graphics shapes, and the built-in elements are built from the basic ones. |
| D14 | **Library or game:** the library renders the part of the world visible through a camera (position and zoom). The game loop belongs to the game or to GameTools. Window creation is per-platform. |
| D15 | **Networking** goes to GameTools, which is already growing more networking and preset commands. |
| D16 | **The game client** stays the real, server-connected client. There is no separate demo app. |
| D17 | **Assets:** they move to the game client and are still referenced by name. Loading goes through an asset-loading interface. (My reading of "yes, stay, asset loading"; see O15.) |
| D18 | **Breakage:** breaking the current project is acceptable if it leads to a better-planned result. |
| D19 | **Delivery:** this plan first, then a series of PRs. |
| D20 | **Cross-repo development:** a Gradle composite build (`includeBuild` of local checkouts) for unreleased library changes. |
| D21 | **JVM-only types:** contract interfaces for now, declared in graphics-core and ui-core, with GeneralTools and GameTools types wrapped in adapters. Longer term, GeneralTools goes multiplatform ([#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7)) and GameTools adopts it. |

## Target architecture

```
SpartanLaboratories/SpartanGraphics            io.github.spartanlaboratories, com.spartanlabs.graphics
  graphics-core     KMP   types, camera, shapes, picking, platform contracts
  graphics-desktop  JVM   LWJGL: GLFW window, OpenGL 3.3 renderer, textures, text, input, frames
  audio-core        KMP   sound contract                       (proposed, O16)
  audio-desktop     JVM   JLayer MP3 playback                  (proposed, O16)

SpartanLaboratories/SpartanUI                  io.github.spartanlaboratories, com.spartanlabs.ui
  ui-core           KMP   elements, layout, input dispatch, triggers, callbacks   api(graphics-core)
  ui-desktop        JVM   only if something desktop-specific turns up (O8)

SpartanLabsGaming/GameGraphics (this repo)     the GameTools RTS client, JVM 23
  app               Main, game loop, snapshot adapter, game UI, game rules,
                    interim networking, edge-pan / markers / outline, assets
```

```
GameGraphics app ──> ui-core ──api──> graphics-core
       │──────────> graphics-desktop ──> graphics-core
       │──────────> audio-desktop ──> audio-core
       └──────────> gametools ──> GeneralTools, WebTools
```

Neither library depends on GameTools, GeneralTools or WebTools. The game client is the only place where GameTools types meet library types.

### graphics-core (KMP)

- **Basic types (D21):** `Color`, `Point`, `Size` and `Rect` contract interfaces, each with a default immutable implementation, so the library can build its own values.
- **Camera (D14):**
  - Holds position, zoom and viewport size.
  - Converts between world and screen coordinates.
  - Reports the visible world region, which drives culling.
  - Ports today's `Camera` / `NdcConverter` and fixes their wrong `networking` package.
- **Shapes:** what every platform renderer has to draw. They sit in two layers:
  - The **world layer** is drawn through the camera.
  - The **screen layer** uses window pixels and holds the UI.

  The proposed set (O2) is:
  - a solid rectangle;
  - a textured rectangle (tint, rotation);
  - text;
  - an outlined rectangle.

  A world shape can carry a tag supplied by the caller, which picking returns.
- **Picking:** finds the top-most tagged world shape under a screen point. It ports `Picking` and works on shapes instead of `VisibleObjectSnapshot`.
- **Platform contracts:**
  - `Surface` (size, resize, close request)
  - `Renderer` (draw one frame of shapes)
  - textures
  - text measurement
  - `AssetSource` (name to bytes, D17)
  - frame scheduling and clock (O10)
  - abstract input events, if O8 lands that way

### graphics-desktop (JVM, LWJGL)

- **Window:** the GLFW window and GL context, taken from `Window.initWindow` / `initOpenGl`.
- **Renderer:** one OpenGL 3.3 renderer for every shape. It merges today's two pipelines (the actor drawing in `Window` and the quad and text drawing in `UiRenderer`).
- **Textures and text:** `TextureCache` and `Shaders` move here as-is. Text uses STBEasyFont for v1 (O13).
- **Assets:** an `AssetSource` that loads from the classpath.
- **Frames and input:** a desktop frame loop on `glfwGetTime`, and GLFW input translated into the abstract input events.
- **LWJGL native libraries:** the library declares only the LWJGL Java APIs. The application adds the natives for its OS. Today the build picks natives from the build machine's OS, which is right for an app but wrong for a library.

### ui-core (KMP)

- **Basic elements (D7, D13):** thin elements over graphics shapes, plus a `Group` element that holds children.
  - `Group` children can be added and removed at runtime.
  - Clipping and scrolling are opt-in.
  - The basic set is closed; custom elements are built from it (O2).
- **Built-in elements:** made from the basic ones. They are `Label`, `Panel`, `Button`, `StatBar` and `Image` (which replaces `Portrait`).
- **Positioning (D11):** positions are fractions of the parent, as today. Sizes can be locked to an aspect ratio; the rule for which side wins is O6. Each element turns itself into screen-layer shapes; that replaces `flatten`.
- **Dispatch (D8, D10):**
  - The front-most element takes a pointer event unless it opts to let it through.
  - Keys go to every element, as today.
  - Hover and triggers work as in D8. The guard condition can veto a trigger.
  - A generic sink for unhandled input replaces today's hard-coded `Viewport` special case. The game's viewport becomes an ordinary user of that sink.
- **Callbacks (D9):** any number per event, each told the element and the trigger source. The listener-vs-lambda split is O4; removing callbacks is O5.
- **Scenes:** `Scene` and `Stage` become real classes instead of typealiases for `ArrayList` and `HashMap`.
- **No GameTools types.** `Portrait`'s snapshot binding moves to the game client.

### Game client (this repo)

- **Stays:** `Main` (composition root), the game loop (D14), `Viewport` / `GameView` / `ClickState`, the inspector and menu built from SpartanUI elements, `EntityLookup`, and all textures and sounds.
- **Interim:** `NetworkClient` / `ProtocolParsing` / `ClientCommands` stay here until GameTools ships a client (D15, O18).
- **Snapshot adapter (D12, D21):** converts `DrawableSnapshot` into graphics shapes, using today's `drawableCore()` and GeneralTools-to-contract adapters (O9).
- **Uses the library:** edge panning, click markers and the selection outline are game features built on graphics-core's camera and shapes (O17).

### Recommendations you asked for

**Library or game for the rest of D14 (O17):**
- **Picking: graphics-core.** Picking is rendering run backwards. Only the renderer's model knows what is drawn where, including rotation, draw order and camera. The library returns the tag; what a pick means is up to the game.
- **Edge panning: game client.** It's an RTS input convention. The library exposes camera operations (pan, zoom, centre on, convert screen to world), and the game decides when to use them. It can become an optional library helper if a second game wants it.
- **Selection outline: split.** The geometry becomes graphics-core's outlined-rectangle shape (porting `SelectionOutline`). Which unit to outline, and in what colour, stays in the game.
- **Click markers: game client.** Spawning, fading and the arrow texture are game choices. They're drawn with ordinary world-layer textured rectangles whose alpha fades.
- **Frame clock: platform modules.** On web the browser decides when a frame runs, and on Android the GL view does. So the platform module owns frame scheduling and calls `onFrame(time)`. The game's fixed-timestep loop runs inside that callback, so it stays game- or GameTools-driven (O10).

**Audio (O16):** separate `audio-core` (KMP contract) and `audio-desktop` (port of the JLayer `SoundPlayer`) artifacts. Host them in the SpartanGraphics repo, but with no dependency on any graphics module.
- **Why the same repo:** audio has the same desktop, Android and web split and the same asset-loading needs.
- **Why separate artifacts:** no rendering code touches it.
- **Moving later is cheap:** because the artifacts are independent, moving audio to its own repo wouldn't affect anyone using it.
- **Shared asset loading:** `AssetSource` then needs to be shared between audio and graphics, so it moves into a tiny shared module in the same repo (proposed).

## Code mapping

| Today | Goes to |
|---|---|
| `graphics/NdcConverter.kt` (`Camera`, `NdcConverter`) | graphics-core `Camera` |
| `graphics/Picking.kt` | graphics-core, operating on shapes |
| `graphics/SelectionOutline.kt` | Geometry goes to graphics-core's outlined rectangle; "outline the selection" goes to the game client |
| `graphics/Shaders.kt`, `graphics/TextureCache.kt` | graphics-desktop |
| `graphics/Window.kt` | Window, context, input and frames go to graphics-desktop. Actor drawing goes to the graphics-desktop renderer. Camera state goes to graphics-core `Camera`. Scene routing goes to ui-core. Edge-pan, markers and outline go to the game client. |
| `graphics/UiRenderer.kt` | Merged into the graphics-desktop renderer; ui-core emits shapes |
| `graphics/ui/UI.kt` | ui-core; `sealed` is replaced by basic plus built-in elements, and the `Viewport` special case by the unhandled-input sink |
| `graphics/ui/Button.kt` | ui-core: `onClick` becomes `onTrigger`, and GLFW codes become abstract ids |
| `graphics/ui/KeyAction.kt` | Replaced by abstract input events (where they live is O8) |
| `graphics/ui/Portrait.kt` | ui-core `Image`; snapshot binding goes to the game client |
| `graphics/ui/Viewport.kt`, `graphics/ui/GameView.kt` | Game client |
| `networking/NetworkClient.kt`, `ProtocolParsing.kt`, `ClientCommands.kt` | Game client until GameTools ships a client |
| `networking/DrawableSnapshots.kt` | Game client snapshot adapter |
| `networking/EntityLookup.kt` | Game client |
| `audio/SoundPlayer.kt` | audio-desktop (O16) |
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
  - A version catalog (`gradle/libs.versions.toml`).
  - A `build-logic` included build with convention plugins for a KMP library module, a JVM library module, and publishing.
- **KMP targets:** library modules start with `jvm()` plus one non-JVM target (`js` or `wasmJs`), with no web backend yet. The extra target makes any JVM-only API in common code fail to compile. Android is added in the Android phase.
- **JVM bytecode:** libraries target 21 (O14). The game client stays on 23 because GameTools requires it.
- **Publishing:** copies GeneralTools' setup. It uses the `com.vanniktech.maven.publish` plugin with Central Portal and signing, and the maintainer releases by hand; there's no publish workflow.
- **CI:** a GitHub Actions build and test job. A `windows-latest` job compiles the desktop modules if desktop support is Windows-only (O1).
- **Coding rules:** `.aiassistant/rules` is copied into each new repo. Two rules collide with multiplatform (O11, O12).

The game client's `settings.gradle.kts` includes `../SpartanGraphics` and `../SpartanUI` with `includeBuild` when those checkouts exist. Otherwise it falls back to published versions (D20).

## PR sequence

Rule for every PR: it compiles and its tests pass. Between Phase 3 PRs, game features may be missing (D18).

### Phase 0: groundwork

| PR / action | Content | Blocked by |
|---|---|---|
| **P0** (this repo) | This plan | — |
| Issue | [GeneralTools#7](https://github.com/SpartanLaboratories/GeneralTools/issues/7): make GeneralTools multiplatform (filed) | — |
| Issue | MyGameTools: a client-side networking counterpart to `GameServer`, if you want it filed | O18 |
| **You** | Create `SpartanLaboratories/SpartanGraphics` and `SpartanLaboratories/SpartanUI` and attach them to a session (this session can't create repos in that org) | — |

### Phase 1: SpartanGraphics

| PR | Content | Blocked by |
|---|---|---|
| **G1** | Skeleton: wrapper, catalog, `build-logic`, empty `graphics-core` (KMP) and `graphics-desktop` (JVM), CI, publishing config, coding rules | O11, O12, O14 |
| **G2** | Basic types (contracts + default implementations) and `Camera` (world/screen conversion, zoom, pan, visible region); `CameraTest` ported from `NdcConverterTest` | — |
| **G3** | Shapes, world and screen layers, culling, picking, outline geometry; `PickingTest` and `SelectionOutlineTest` ported | O2 |
| **G4** | Platform contracts: surface, renderer, textures, text measurement, `AssetSource`, frames and clock, input events | O8, O10 |
| **G5** | `graphics-desktop`: GLFW window, single GL 3.3 renderer, textures, STBEasyFont text, classpath assets, frame loop, GLFW input. Can't be unit-tested without a GPU; verified through C1. | O1, O13 |
| **G6** | `audio-core` + `audio-desktop` (and the shared `AssetSource` module) | O16 |
| **G7** | Release `0.1.0` (manual, by you) | G1–G6 |

### Phase 2: SpartanUI

| PR | Content | Blocked by |
|---|---|---|
| **U1** | Skeleton, same conventions, `ui-core` with `api(graphics-core)` through the composite build | G1 |
| **U2** | Element tree: basic elements on shapes, `Group` with runtime add/remove and opt-in clipping, fractional positioning, aspect lock, conversion to shapes; layout tests from `UiTest` ported | O2, O6, O7 |
| **U3** | Dispatch and callbacks: pointer and key routing, opt-in pass-through, hover, triggers, guard veto, multiple callbacks with context, the unhandled-input sink; dispatch tests ported | O3, O5, O8 |
| **U4** | Built-in elements: `Label`, `Panel`, `Button`, `StatBar`, `Image`; `ButtonTest`, `StatBarTest`, `PortraitTest` ported | O4 |
| **U5** | Opt-in scrolling for `Group` | U2 |
| **U6** | Release `0.1.0` | U1–U5, G7 |

### Phase 3: game client (this repo)

| PR | Content | Blocked by |
|---|---|---|
| **C1** | Remove all library code from this repo. Depend on graphics-desktop and ui-core through the composite build. Snapshot adapter plus GeneralTools adapters. A minimal `Main` opens a window and draws the world from `STATE` on the platform frame driver. Networking untouched. | G5, O9 |
| **C2** | Game UI on SpartanUI: viewport as the unhandled-input sink, `GameView`, `ClickState`, inspector and menu scenes, keys as abstract ids; `ViewportTest` ported | U4 |
| **C3** | Game visuals on the library camera and shapes: edge pan, click markers, selection outline. Update or retire `camera-follow-selection.md`, which is written against `Window`. | C1 |
| **C4** | Audio through `audio-desktop` | G6 |
| **C5** | README and docs rewrite; mark superseded plans | C1–C4 |
| **Later** | Replace `NetworkClient` with GameTools' client when it ships | O18 |

The game client isn't fully playable again until C2–C3. Its old feature set (selection, orders, inspector, markers, outline) is restored by C3, and sound by C4.

## Open questions

| # | Question | My lean | Blocks |
|---|---|---|---|
| O1 | **Desktop scope.** The LWJGL backend runs on Windows, macOS and Linux with the same code. Support all three, or officially Windows only? | All three, since only the natives differ | G1 CI, G5 |
| O2 | **Shape set and basic elements.** Is the proposed shape set right (solid rect, textured rect with tint and rotation, text, outlined rect)? Should the set stay closed, with custom elements built only by composition? Are the v1 built-in elements right (`Label`, `Panel`, `Button`, `StatBar`, `Image`)? | Yes, closed; lines, circles and 9-slice later | G3, U2 |
| O3 | **Input details.** Own key ids (`Key.A`) and pointer buttons (Primary, Secondary, Middle)? Keyboard shortcuts on `Button`, or in an app-owned binding table? Keep hover, which touch never fires? Focus or gamepad navigation in v1, later, or never? | Own ids; shortcut on `Button`; keep hover; focus later | U3 |
| O4 | **`ButtonListener` vs. lambdas.** Two readings: (a) the listener is internal and users attach only lambdas; (b) both are public, with one listener for the main job plus extra lambdas. Which? | — | U4 |
| O5 | **Removing callbacks.** Should adding a callback return a handle that removes it? | Yes, since children come and go at runtime | U3 |
| O6 | **Aspect-lock rule.** When the box has the wrong shape: fit inside and centre; width sets height; height sets width; or each element chooses, with a default? | Each element chooses; default is fit inside and centre | U2 |
| O7 | **Threads.** Is the element tree changed only on the render thread, or from any thread (e.g. networking)? | Render thread only; others post changes to it | U2 |
| O8 | **Where input events live.** D8 put abstract input in ui-core, but D13 means graphics can't depend on UI, and the window (in graphics-desktop) is where raw input arrives. Options: (a) ui-core defines the events, and a ui-desktop module translates GLFW; a game without SpartanUI then gets no input abstraction. (b) graphics-core defines raw pointer and key events as part of the window contract, and ui-core does all the interpretation (hit-testing, hover, triggers, shortcuts). | (b). It also means SpartanUI needs no platform modules for now | G4, U3 |
| O9 | **Where the adapters live.** In round 2 I said "the desktop modules", but that would make graphics-desktop depend on GameTools and WebTools. Alternative: the GameTools snapshot adapter and the GeneralTools adapters live in the game client, and become an artifact only if a second user appears. They go away when GeneralTools#7 lands. | Game client | C1 |
| O10 | **Who owns the frame.** Web and Android call the app once per frame; a game can't run its own `while` loop there. Should the platform own frame scheduling (`onFrame(time)`), with the game's fixed-timestep loop running inside it? | Yes | G4 |
| O11 | **Logging rule vs. multiplatform.** Your rules say "libraries use slf4j", but slf4j is JVM-only. Use a multiplatform facade such as kotlin-logging, which logs through slf4j on the JVM so logback keeps working? GeneralTools#7 has the same question. | kotlin-logging | G1 |
| O12 | **Testing rule vs. multiplatform.** JUnit 5 and MockK are JVM-only. Common tests would use `kotlin.test` with hand-written fakes; MockK stays allowed in JVM-only tests. OK? | Yes | G1 |
| O13 | **Text v1.** Port STBEasyFont (fixed-size bitmap font, desktop only) behind the text contract, with real fonts later? | Yes | G5 |
| O14 | **Library JVM target.** 21 (LTS; this cloud environment has JDK 21) or 23 to match GameTools? | 21 | G1 |
| O15 | **Assets.** Is D17 right: names stay, resolved through `AssetSource` (desktop: classpath; Android: app assets; web: HTTP)? | — | G4 |
| O16 | **Audio.** Is the recommendation above right (separate audio artifacts in the SpartanGraphics repo, plus a shared `AssetSource` module)? | — | G6, C4 |
| O17 | **Library or game.** Are the recommendations for picking, edge pan, outline, markers and the frame clock right? | — | G3, C3 |
| O18 | **GameTools client.** Should I file an issue in MyGameTools for a client counterpart to `GameServer`? Until it ships, does `NetworkClient` stay in the game client? | File it; yes, it stays | Later |
| O19 | **Repo name.** This repo will hold only the game client. Keep the name `GameGraphics`, or rename? | Your call | C5 |

## Risks

- **Composite builds with KMP.** Gradle substitutes included KMP builds in modern Gradle and Kotlin, but this path has had rough edges. U1 and C1 prove it early, before anything depends on it.
- **No GPU in CI or in cloud sessions.** `graphics-desktop` can be compiled but not run there, as `Window` is today. The pure logic (camera, picking, outline, layout, dispatch) stays in common code and gets full unit tests. The renderer is verified by running the game client by hand.
- **JDK 23 for the game client.** GameTools needs a JVM 23 runtime. This cloud environment has only JDK 21, and the toolchain download is blocked, so the game client can't build here until JDK 23 is provided (setup script or environment). The libraries on 21 aren't affected.
- **No playable build from C1 to C3.** Accepted (D18).
- **API churn across repos.** The composite build makes iterating cheap, and nothing is released until U6 and G7.
- **GeneralTools#7 will change the adapters.** That's contained to the game client by design (O9).

## Effect on existing plan docs

- `camera-follow-selection.md` (proposed) targets `Window` / `panOffset`. C3 either implements it on `Camera` or marks it superseded.
- `in-world-selection-highlight.md` and `phase-1-select-by-entity-id.md` (implemented) stay as history. Their behaviour is carried by C2–C3.
