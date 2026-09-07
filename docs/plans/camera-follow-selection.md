# Camera follow / center on selection

**Status:** proposed
**Depends on:** Phase 1 (select-by-entity-id) — reads `viewport.selectedEntityId`
**Shares infrastructure with:** `in-world-selection-highlight.md` — both add
`Window.selectionSource: () -> Long?`; whichever lands first, the other reuses it

## Problem

The only camera controls are edge-panning (cursor near a window edge) and scroll-zoom.
There is no way to jump the view to a unit. On a large map a selected unit is often off
screen or near an edge, and the player has to edge-pan there by hand every time — the
selection inspector shows its stats but you can't *see* it.

## Goal

A key that recenters the camera on the selected unit, with an optional mode that keeps it
centered as the unit moves. Purely client-side camera state — no command path, nothing
GameTools issue #31 touches.

## How the camera works today

`Window` holds `panOffsetX` / `panOffsetY` (`Float`, world pixels) and `zoomFactor`.
`NdcConverter.offset` renders an actor centred exactly when
`panOffset == (actor.location.x, actor.location.y)` — zoom is applied *after* the
`world - panOffset` subtraction, so centering on a unit is just
`panOffset = unit world position`, independent of the zoom level.
`applyEdgePanning()` mutates `panOffset` by a fixed `EDGE_PAN_SPEED_PX_PER_FRAME` each
frame, called at the top of `render()`.

## Design

**`F` toggles follow mode.** On activation the camera eases to the selected unit and then
tracks it every frame while the mode stays on; edge-panning is suppressed for as long as
it is active. Toggling off restores edge-panning and leaves the camera where it is.

Per frame, in `render()` after `cores` is built:

- follow **on** and the selected unit is in `cores` → step `panOffset` toward that unit's
  world position (ease, not snap — see `CameraFollow.step`);
- follow **on** but nothing selected / the unit is gone → hold position (mode stays on,
  re-selecting resumes);
- follow **off** → `applyEdgePanning()` as now.

`Window` learns the selection through `selectionSource: () -> Long?` (wired in `Main` to
`viewport::selectedEntityId`); the unit's position comes from `lastSnapshots.byEntityId(id)`.

### New pure helper — `graphics/CameraFollow.kt`

Mirrors `Picking` / `SelectionOutline`: pure, GL-free, unit-testable.

```kotlin
internal object CameraFollow {
    /**
     * The next camera pan offset when easing from [current] (world px) toward [target]
     * (world px) by fraction [smoothing] per frame. Snaps exactly to [target] once within
     * [SNAP_EPSILON_PX] on both axes, so the camera settles instead of creeping forever.
     */
    fun step(current: Pair<Float, Float>, target: Pair<Double, Double>, smoothing: Float): Pair<Float, Float>

    const val SNAP_EPSILON_PX = 0.5
}
```

### File-by-file

| File | Change |
|---|---|
| `graphics/CameraFollow.kt` | **new** — the pure helper above |
| `graphics/Window.kt` | `var selectionSource: () -> Long? = { null }` + `onSelection(...)` (shared with the highlight plan — add once). New `@Volatile`-free main-thread `cameraFollowEnabled: Boolean`. In `handleKeyActionInternally`, on `PRESS` of `GLFW_KEY_F` flip `cameraFollowEnabled` (log the new state). In `render()`, replace the unconditional `applyEdgePanning()` with: if `cameraFollowEnabled` and the selected unit resolves in `cores`, `panOffset = CameraFollow.step((panOffsetX, panOffsetY), unit.location, CAMERA_FOLLOW_SMOOTHING)`; else `applyEdgePanning()`. New companion const `CAMERA_FOLLOW_SMOOTHING`. |
| `Main.kt` | In `main()` before `runLoop(...)`: `window.onSelection { viewport.selectedEntityId }` (same line the highlight plan adds). |
| `README.md` | Controls table: add `F — follow / center camera on the selected unit`. |

`GameView`, `Viewport`, `NetworkClient`, the UI classes — untouched.

### Optional extras (cheap, call in review)

- **`Space` = center once** — snap `panOffset` to the selected unit without entering follow
  mode. One extra key case + a `CameraFollow.step(..., smoothing = 1f)` call.
- **Edge input cancels follow** — if the cursor enters the edge-pan zone while following,
  turn follow off (feels more natural than "F suppresses edge pan until you press F again").
- **Reset view** — a key to set `panOffset = (0, 0)` and `zoomFactor = 1`.

## Behaviour

- Select a unit, press `F` → camera eases onto it and tracks it as it moves.
- Press `F` again → camera stops tracking, stays put, edge-panning works again.
- Selected unit dies while following → camera holds; select another → camera eases to it.
- Zoom in/out while following → stays centred (follow controls pan only).

## Test plan

Flat `src/test/kotlin/`, matching the suite.

| Level | Coverage |
|---|---|
| 1 — gating | `./gradlew build` green before commit |
| 2 / 4a | **`CameraFollowTest.kt` (new):** `step` moves a `smoothing` fraction of the way toward the target; repeated calls converge; snaps exactly to target within `SNAP_EPSILON_PX`; returns the target unchanged when already there; `smoothing = 1f` is an immediate snap; handles negative/large offsets. |
| 3 | n/a |
| 5 — UAT | Manual: `F` with a unit selected centres and tracks it; `F` again releases; edge-pan works again after release; follow with nothing selected is a no-op; zoom stays centred. **Ask before launching the app.** |

`NdcConverterTest`, `PickingTest`, `ViewportTest`, `EntityLookupTest` unchanged.

## Risks

- **Frame-rate dependence** — `CameraFollow.step` eases by a per-*frame* fraction, like the
  existing `EDGE_PAN_SPEED_PX_PER_FRAME`. Consistent with the current camera code but not
  time-correct; a proper fix (thread `frameTime` from `runLoop` through `render`) is a
  separate cleanup for *all* camera motion, out of scope here.
- **Shared `selectionSource` wiring** — if both this and the highlight plan are implemented,
  add the `Window` field + `Main` line once; note it in whichever PR lands second.
- **`Window` has no GL-free test seam** — mitigated by keeping the math in `CameraFollow`;
  the toggle and the `render()` branch stay thin and untested, like `applyEdgePanning`.

## Open decisions

1. **Follow key** — `F` (*recommended*), or `Space`. Escape is reserved (closes the window);
   `B` is the beep button.
2. **Manual-pan-cancels-follow** vs. **F-suppresses-edge-pan** (*recommended for v1*: the
   latter — simplest, and the only pan input today is edge-pan).
3. **Ease vs. snap on activation** — ease (*recommended*), snap, or ease with a separate
   `Space` one-shot snap.
4. **Follow persistence when the unit dies** — hold position with mode still on
   (*recommended*) vs. auto-disable follow.
5. **Include the "reset view" / "center once" extras** now or defer.

## Estimated size

1 new source file + `Window.kt` + one line in `Main.kt` + a README row, 1 new test file.
~2–3 hours including the manual UAT pass.
