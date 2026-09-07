# In-world selection highlight

**Status:** implemented
**Depends on:** Phase 1 (select-by-entity-id) — uses `viewport.selectedEntityId` and
`List<DrawableSnapshot>.byEntityId`
**Related:** camera-follow-selection (separate plan, not yet written)

**Decisions taken:** full thin rectangle, axis-aligned, fixed bright green
(`Window.SELECTION_COLOR`), drawn on top of the actors. Margin 6 px / thickness 3 px
(world pixels, before zoom). White fallback texture via `TextureCache.handleFor(null)` —
no new asset. Degenerate (`<= 0`) unit size is skipped. Manual UAT (level 5) still
outstanding — needs a running server.

## Problem

The selection inspector (bottom panel: portrait, health bar, stats grid) tells you
everything about the selected unit *except which one it is*. In the game view there is no
indication at all — you left-click a unit and only a corner panel changes. With several
similar units on screen you have to infer the selection from position.

## Goal

Draw a marker on the selected unit in the world so the selection is visible where the
player is looking. Purely client-side rendering — no command path, nothing that GameTools
issue #31 (uniform client/server protocol) touches.

## Design

A thin rectangular **outline** just outside the selected unit's box, drawn with the same
camera transform as the actors so it tracks pan and zoom for free. Built from four tinted
quads (top/bottom/left/right edges) using `TextureCache`'s white fallback — no new texture
asset. Axis-aligned (does not rotate with the unit), which is the RTS convention and keeps
the geometry simple.

Resolved every frame from `viewport.selectedEntityId` against the current frame's snapshots:
if the id is absent — nothing selected, or the selected unit just died or left the world —
nothing is drawn. That "selected unit gone → marker vanishes" behaviour falls out of the
lookup for free.

### New pure helper — `graphics/SelectionOutline.kt`

Mirrors `Picking`: a pure `internal object`, no GL, fully unit-testable.

```kotlin
/** One edge of a selection outline, as a world-pixel rectangle centred at (cx, cy). */
internal data class OutlineEdge(val cx: Double, val cy: Double, val widthPx: Double, val heightPx: Double)

internal object SelectionOutline {
    /**
     * The four edge rectangles of an axis-aligned outline around a unit centred at
     * ([centerX], [centerY]) sized [unitWidth] x [unitHeight] (world px): a frame sitting
     * [marginPx] outside the unit box, each edge [thicknessPx] thick. Corners are covered
     * by both the horizontal and vertical edge (they overlap by [thicknessPx]).
     */
    fun edges(
        centerX: Double, centerY: Double,
        unitWidth: Double, unitHeight: Double,
        marginPx: Double, thicknessPx: Double,
    ): List<OutlineEdge>
}
```

Geometry: frame outer half-extents are `unitWidth/2 + margin` and `unitHeight/2 + margin`.
Top/bottom edges span the full frame width, `thickness` tall, offset `±(halfHeight + ...)`
in Y. Left/right edges span the full frame height, `thickness` wide, offset in X. Exact
offsets worked out so the outline's *inner* edge is `margin` from the unit box.

### File-by-file

| File | Change |
|---|---|
| `graphics/SelectionOutline.kt` | **new** — the pure helper above |
| `graphics/Window.kt` | `var selectionSource: () -> Long? = { null }` + `fun onSelection(source: () -> Long?)`. In `render()`, after the actor and click-marker passes and before the UI overlay: resolve `cores` by `selectionSource()` (via `byEntityId`), and if found call a new `drawSelectionOutline(core, camera)`. `drawSelectionOutline` is a trimmed `drawActor` (cf. `drawMarker`): for each `SelectionOutline.edges(...)` entry, set `uOffset` / `uHalfSize` from `NdcConverter`, bind the white texture, set `uColor` to `SELECTION_COLOR`, `uAngleRadians` 0, and `glDrawElements`. New companion consts `SELECTION_COLOR`, `SELECTION_MARGIN_PX`, `SELECTION_THICKNESS_PX`. |
| `Main.kt` | In `main()`, before `runLoop(...)`: `window.onSelection { viewport.selectedEntityId }`. |

`GameView`, `Viewport`, `NetworkClient`, the UI element classes — untouched.

### Behaviour

- Select a unit → an outline appears around it in the world, in addition to the panel.
- Pan / zoom → the outline stays locked to the unit (same transform as `drawActor`).
- Select another unit / click empty ground → the outline moves / disappears.
- Selected unit dies or leaves the world → the outline disappears the same frame.

## Test plan

Flat `src/test/kotlin/` layout, matching the rest of the suite.

| Level | Coverage |
|---|---|
| 1 — gating | `./gradlew build` green before commit |
| 2 — component / 4a — deterministic | **`SelectionOutlineTest.kt` (new):** `edges(...)` returns four rectangles; the frame is `margin` outside the unit box on every side; horizontal edges span the full outer width and vertical edges the full outer height; each edge is `thickness` thick on its short axis; the result is symmetric about the unit centre; a zero-size unit still yields a well-formed `2*margin`-ish frame (or is documented to be skipped). |
| 3 — integration | n/a — no external interface. |
| 5 — UAT | Manual: select a unit and confirm the outline is around the right one; pan/zoom and confirm it tracks; switch selection; let the selected unit die and confirm the outline vanishes. **Ask before launching the app** (per the "prompt before visual tests" note). |

`PickingTest`, `NdcConverterTest`, `ViewportTest`, `EntityLookupTest` need no changes.

## Risks

- **Sub-pixel shimmer** — a 2 px edge at low zoom can flicker. Mitigate with a small minimum
  NDC thickness, or accept it for v1.
- **Degenerate unit size** — a zero/near-zero `dimensions` snapshot gives a collapsed
  outline. Skip drawing when `width` or `height` is below a threshold.
- **`Window` has no test coverage** (GL context) — mitigated by putting all the arithmetic
  in `SelectionOutline`; `drawSelectionOutline` stays a thin, untested GL shim like
  `drawActor` / `drawMarker`.

## Open decisions

1. **Marker style** — full thin rectangle (v1, *recommended*: fewest quads, dead simple),
   corner brackets (more RTS-like, 8 short quads), or a ring/reticle texture asset (needs a
   PNG and looks wrong against the white fallback if missing). Brackets are a clean follow-up
   polish on top of the rectangle.
2. **Rotation** — axis-aligned (*recommended*, conventional) vs. rotates with the unit's
   `angle`/`turns`.
3. **Colour** — a fixed bright green (*recommended*), white, or tinted by the unit's
   faction/owner (ally vs enemy). Faction tint is nice but reaches into `AliveSnapshot`.
4. **Draw order** — on top of the actors (*recommended*: always visible) vs. behind them as
   a glow/halo (prettier, can be hidden by overlapping units).

## Follow-ups this enables

- **Hover highlight** — a fainter outline on the actor under the cursor (reuses
  `SelectionOutline` + the existing `Window.pick`), giving pre-click feedback.
- Corner-bracket / animated selection styling.

## Estimated size

1 new source file + `Window.kt` + one line in `Main.kt`, 1 new test file. ~2–3 hours
including the manual UAT pass.
