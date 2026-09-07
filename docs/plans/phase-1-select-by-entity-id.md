# Phase 1 — Select and command units by stable entity id

**Status:** implemented, then superseded by the GameTools 5.0.0 `ClientCommand` adoption
(2026-09-07). The id→index translation described below (`indexOfEntityId`, index-based
`SET_DEST` / `ATTACK`) is **gone** — `NetworkClient` now sends `COMMAND <json>` datagrams
via `networking/ClientCommands.kt`, ids on the wire, in lockstep with MyGameServer#6.
Selection-by-id and the `EntityLookup.byEntityId` / `attackTarget` helpers are unchanged.
**Depends on:** GameTools 3.1.0 (superseded — now `gametools` 5.0.0)
**Follow-up:** done — Phase 2 (id-keyed commands) shipped as GameTools 5.0.0's first-class API.

**Decisions taken:** (1) pre-3.1.0 servers unsupported — a no-id pick logs and is treated as
a miss; (2) `attackTarget` helper extracted and tested; (3) header label reads `"Unit #<id>"`.
Manual UAT (level 5) still outstanding — needs a running server on GameTools 3.1.0+.

## Problem

Selection and command targeting are keyed by **position in the `STATE` list**:

- `Viewport.selectedActor: Int?` holds a list index, re-resolved every frame as
  `client.getWorldState().getOrNull(index)`.
- `GameView.moveActor(actorIndex)` / `attack(attackerIndex, …)` send that index straight
  to the server (`SET_DEST <index>`, `ATTACK <a> <t>`).

The server's `STATE` list is not stable: an entry's index shifts whenever a unit dies,
spawns, or the server reorders. The moment that happens:

- the inspector, portrait and health bar silently switch to a **different unit** while the
  header still reads "Actor #3";
- a queued right-click **move or attack lands on the wrong unit**;
- a selected unit that dies leaves the inspector showing whatever slid into its old slot,
  instead of clearing.

## What 3.1.0 gives us

`DrawableSnapshot` gained `val id: Long` — a stable, `World`-assigned identity carried on
every `STATE` entry (`VisibleObjectSnapshot`, `ActorSnapshot`, `AliveSnapshot`).
`DrawableSnapshot.UNIDENTIFIED` (`0L`) is the sentinel for an object with no `World`-assigned
id (an unowned object, or a server older than 3.1.0).

`drawableCore()` preserves the id: for an `ActorSnapshot`/`AliveSnapshot` the wrapped
`VisibleObjectSnapshot` describes the same entity and was built with the same
`entityId.raw`, so `snapshot.drawableCore().id` is the entity id for any top-level entry.
(Sub-objects — health bars, nameplates — get their own ids but are never picked.)

## Design

Cut the `GameView` boundary over to **`Long` entity ids**. Selection stores an id; the
inspector resolves it against the current state each frame; commands translate id → current
index at send time (the wire stays index-based until Phase 2).

### New pure helpers — `networking/EntityLookup.kt`

```kotlin
/** The STATE entry with this entity id, or null (also for UNIDENTIFIED, which is not a real id). */
internal fun List<DrawableSnapshot>.byEntityId(id: Long): DrawableSnapshot? =
    if (id == DrawableSnapshot.UNIDENTIFIED) null else firstOrNull { it.id == id }

/** This entity id's current position in the STATE list, or null if it is not present. */
internal fun List<DrawableSnapshot>.indexOfEntityId(id: Long): Int? =
    if (id == DrawableSnapshot.UNIDENTIFIED) null
    else indexOfFirst { it.id == id }.takeIf { it >= 0 }
```

Optionally also extract the attack-eligibility check now buried in `Main.gameView()`:

```kotlin
/** The attackable enemy Alive under a right-click, or null if the pick is not a valid target. */
internal fun List<DrawableSnapshot>.attackTarget(
    attackerId: Long, targetId: Long, playerName: String
): AliveSnapshot? {
    if (targetId == attackerId || targetId == DrawableSnapshot.UNIDENTIFIED) return null
    val target = byEntityId(targetId) as? AliveSnapshot ?: return null
    return target.takeIf { it.ownerName != playerName }
}
```

### File-by-file

| File | Change |
|---|---|
| `networking/EntityLookup.kt` | **new** — the helpers above |
| `graphics/Picking.kt` | **no change** — stays pure, index-returning |
| `graphics/Window.kt` | `pick(x, y): Int?` → `Long?`: map `Picking.pick(...)` index through `lastSnapshots[it].id`; return null (with a one-time debug log) when the hit's id is `UNIDENTIFIED`. Update KDoc. `lastSnapshots` is already `List<VisibleObjectSnapshot>`, which now carries `.id`. |
| `graphics/ui/GameView.kt` | `pickActor(): Long?`; `moveActor(entityId: Long, …)`; `attack(attackerEntityId: Long, …): Boolean`. Update KDoc (indices → stable ids). |
| `graphics/ui/Viewport.kt` | `selectedActor: Int?` → `selectedEntityId: Long?` (keep `private set`, keep "survives scene swaps"). `selectActorUnder` / `commandSelectedActor` / `moveSelectedActorTo` pass the `Long?` through. |
| `Main.kt` — `gameView()` | `pickActor` → `window.pick(...)`. `moveActor(entityId,…)`: `client.getWorldState().indexOfEntityId(entityId)` → if null, log "selected unit gone, move dropped" and return; else `setDestination(index,…)`. `attack(attackerEntityId,…)`: `targetId = window.pick(...) ?: return false`; call `getWorldState()` **once**; `state.attackTarget(attackerEntityId, targetId, PLAYER_NAME) ?: return false`; translate both ids via `state.indexOfEntityId(...)` (either null → `return false`); `client.attack(attackerIndex, targetIndex)`. |
| `Main.kt` — `buildStage` / `bottomInfoPanel` | `selectedRaw = { viewport.selectedEntityId?.let { client.getWorldState().byEntityId(it) } }`. `selectedIndex: () -> Int?` param → `selectedEntityId: () -> Long?`. Header label: `"Unit #$it"` (a stable id now, not a slot number — see open decisions). |
| `networking/NetworkClient.kt` | No behavioural change. Touch the `attack` KDoc note that says the indices are "positions in the last `STATE` list" — still true, but add that callers now derive them from an entity id per send. |

### Behaviour after the change

- Selected unit dies / leaves the world → `byEntityId` returns null → inspector reads
  "Nothing selected", health bar hides, portrait clears. **(the core fix)**
- List reorders under a live selection → inspector and commands stay locked to the same unit.
- Right-click move/attack after the selected unit is gone → `indexOfEntityId` is null → the
  request is dropped with a log line instead of hitting a stranger.
- Pick that lands on a unit with no stable id → treated as "nothing picked" plus a
  diagnostic log (see open decision 1).

## Test plan

Following the existing flat `src/test/kotlin/` layout (the repo does not use the
`testing.<level>` package split).

| Level | Coverage |
|---|---|
| 1 — gating | `./gradlew build` green before commit |
| 2 — component | **`EntityLookupTest.kt` (new):** `byEntityId` — hit, miss, `UNIDENTIFIED` → null, first-wins on a duplicate id; `indexOfEntityId` — same set; `attackTarget` — enemy Alive → returned, own unit → null, self → null, non-Alive → null, missing → null. Uses `visibleObjectSnapshot(id = …)` (the 3.1.0 helper param) plus small `ActorSnapshot`/`AliveSnapshot` builders. |
| 2 — component | **`ViewportTest.kt` (update):** `FakeGameView.pickResult` `Int?`→`Long?`; `moved`/`attacked` first field `Int`→`Long`; `selectedActor`→`selectedEntityId`; literals `3`/`7`→`3L`/`7L`. Behavior assertions unchanged. |
| 3 — integration | Not applicable (no new external interface; wire format unchanged). |
| 4a — deterministic | The `EntityLookup` helpers double as pure input→output cases; keep them in the level-2 file unless the split is adopted. |
| 5 — UAT | Manual, against a server on GameTools 3.1.0+: (a) select unit A, let unit B die, confirm the inspector stays on A; (b) select unit A, let A die, confirm the inspector clears; (c) select A, right-click-move after A dies, confirm no stray order. **Ask before launching the app for this** (per the "prompt before visual tests" note). |

`PickingTest`, `PortraitTest`, `StatBarTest`, `DrawableSnapshotsTest` need no changes.

## Risks

- **Pre-3.1.0 server.** If MyGameServer is not yet on GameTools 3.1.0, its `STATE` payload
  has no `id` field and every entry deserializes with `id == UNIDENTIFIED` → id-based
  selection cannot work. See open decision 1.
- **Translation race.** id → index is resolved at send time against `getWorldState()`;
  the server could still reorder between that read and processing the datagram. Strictly
  smaller than today's click-time capture, and fully closed only by Phase 2.
- **`Main` wiring stays hard to unit-test** — mitigated by pushing the logic into the pure
  `EntityLookup` helpers so the lambda in `gameView()` is a thin translation shell.

## Open decisions

1. **Drop pre-3.1.0 server support?** *Recommended: yes* — the index path is already broken
   at every version, Phase 2 needs a server change regardless, and a `UNIDENTIFIED` hit
   gets a clear "server needs GameTools 3.1.0+" log rather than a silent failure. Confirm
   what version MyGameServer runs before committing to this.
2. **Extract the attack-eligibility helper (`attackTarget`)**, or leave that check inline in
   `Main` and extract only the two lookup helpers? *Recommended: extract it* — it is the
   part with real branching and it is currently untested.
3. **Header label wording** — `"Actor #<id>"` (unchanged text, new meaning), `"Unit #<id>"`,
   or `"Entity <id>"`. *Recommended: `"Unit #<id>"`.*
4. **Branching** — Phase 1 assumes the 3.1.0 bump is committed first. Land it as its own
   commit, then branch `feature/select-by-entity-id` off `master`.

## Estimated size

~3 source files changed + 1 new, 1 test file changed + 1 new. Roughly half a day including
the manual UAT pass.
