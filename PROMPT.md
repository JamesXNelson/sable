# Sable #1376 / #1387 regression and recovery work

## Purpose

Use this branch as a focused investigation and implementation workspace for two likely-related Sable bugs:

- #1376 — `Sub-level assembly attempted inside plot of already removed sub-level`
  https://github.com/ryanhcode/sable/issues/1376
- #1387 — related/mutually-dependent sublevels can survive on disk but become permanently unloadable
  https://github.com/ryanhcode/sable/issues/1387

Do not begin by blaming Waystones, Aeronautics, or the removed portal mod. The real save has useful interaction history, but the strongest evidence points at Sable lifecycle invariants.

Base revision used when preparing this prompt/test:

`6966d2928340de7631abcecf8549904b877df0a8` (`2.0.5`)

## Real-world environment

- Minecraft 1.21.1
- NeoForge 21.1.249
- Sable 2.0.5
- Sable Companion 1.6.0
- Create 6.0.10
- Create Aeronautics 1.3.1 bundled
- Create Gadgets & Gizmos 1.1.3
- Create Propulsion: Simulated 1.1.5
- Waystones 21.1.41
- WaystonesSable 1.0.7
- Java 21
- single-player integrated server

## Real-world structure history

The important surviving build is a large flying castle.

A random vanilla village hut was assembled separately into its own Sable sublevel **at the village**. Later, that already-assembled hut sublevel was moved/placed inside the castle.

The hut was **not assembled while inside the castle**.

The player remembers breaking/deleting a Waystone immediately before one later visible failure. Treat this as a trigger candidate, not proven root cause:

- Minecraft does not log an ordinary block break.
- The retained Sable crash stack contains no Waystones frame.
- Breaking the block may simply have caused heat-map/connectivity processing against already-inconsistent Sable state.

There is also a pre-existing-state caveat: the user had experimented with an Immersive-Aeronautics/portal integration the previous night, and that experiment crashed the world. The portal mod was subsequently removed. The earliest retained Aug 30 logs already show a `non-existent sub-level`, so the world may have contained stale Sable state before the later hut/Waystone incident. Do not assume the portal mod caused that state unless an earlier log/reproduction proves it.

## Strongest runtime evidence: #1376

Two retained server crash reports (09:34:21 and 10:03:05) show:

```text
java.lang.RuntimeException: Sub-level assembly attempted inside plot of already removed sub-level
    at dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(SubLevelAssemblyHelper.java:85)
    at dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager.split(SubLevelHeatMapManager.java:242)
    at dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager.step(SubLevelHeatMapManager.java:118)
    at dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager.tick(SubLevelHeatMapManager.java:85)
    at dev.ryanhcode.sable.sublevel.ServerSubLevel.tick(ServerSubLevel.java:235)
```

The current code makes a small pure-Sable reproduction plausible:

1. A `ServerSubLevel` can be `markRemoved()` while its `LevelPlot` is still registered in the container until `processSubLevelRemovals()`.
2. `ActiveSableCompanion.getContaining(...)` returns the sublevel owning a registered plot without filtering `isRemoved()`.
3. `SubLevelContainer.tick()` ticks all sublevels **before** processing removals.
4. `ServerSubLevel.tick()` can run its heat-map after a sublevel is marked removed.
5. `SubLevelHeatMapManager.split()` calls `SubLevelAssemblyHelper.assembleBlocks(...)` on positions inside that plot.
6. `assembleBlocks(...)` finds the still-registered containing sublevel, observes `isRemoved()`, and throws the exact #1376 exception.

A focused GameTest is included on this branch:

`neoforge/src/main/java/dev/ryanhcode/sable/neoforge/gametest/RemovedSubLevelSplitTest.java`

It deliberately creates two disconnected blocks inside a sublevel, marks the sublevel removed, and directly ticks it before the container can process removal. On buggy 2.0.5, the pending heat-map split should attempt assembly inside the removed plot and fail with the #1376 invariant. The test catches that runtime exception and reports a normal GameTest failure instead of crashing the GameTest server.

The test is intentionally **pure Sable**: no Aeronautics, Waystones, portal mod, or persistence corruption is required.

See `RUN-REGRESSION.md` for exact commands and expected output.

## Strongest storage evidence: #1387

After the real crash, the castle could be enumerated by:

`/sable storage find_all_sub_levels`

but could not be targeted by normal live-sublevel selectors, `/sable teleport`, or `/sable info`.

A subsequent restart logged:

```text
Couldn't find sub-level at index 0 in storage file for chunk [9, 65]

Due to a failed storage sub-level data load, we can't add a holding sub-level
for pointer local->[storageIndex=0, subLevelIndex=0].
This will cause issues later down the line.

Sub-level dependency does not exist in chunk. Something has gone terribly wrong.
```

Offline inspection of the damaged `.slvlr` / `.slvls` files found:

- castle UUID: `845625ba-98c8-461a-93b5-da56312c9109`
- castle block payload intact and parseable
- castle holding-chunk pointer list: `[0, 1]`
- pointer/slot `1`: intact castle
- pointer/slot `0`: empty/dead
- castle `loading_dependencies`: `89ff0293-8292-4e32-8b0c-dbcd19584fad`
- that UUID existed elsewhere and decoded as the small village-hut-like sublevel
- mutual loading-dependency state existed between them

A minimal offline repair:

1. removed the dead pointer,
2. removed the stale dependency from the castle,
3. zeroed the castle's saved velocity,

and the castle loaded normally again without changing its block payload.

This suggests a possible lifecycle chain:

`split/remove inconsistency`
→ `#1376 crash`
→ cleanup/persistence interrupted or inconsistent
→ dead pointer and/or stale dependency survives
→ `#1387` storage-only survivor

Do not assume that exact chain until tests prove it, but preserve the invariant that a runtime failure must never leave storage unrecoverable.

## Run the first regression

Windows:

```powershell
.\gradlew.bat neoforge:runGameTest --stacktrace
```

Linux/macOS:

```bash
./gradlew neoforge:runGameTest --stacktrace
```

Expected on the unmodified 2.0.5 implementation: the new required GameTest fails with:

```text
Regression #1376: removed sublevel attempted heat-map split assembly
```

Expected after a correct production fix: the new test passes.

## Likely first production fix to investigate

Do not mechanically implement this without reviewing lifecycle semantics, but the code strongly suggests a guard is missing around removed sublevels.

Inspect especially:

- `ServerSubLevel.tick()`
- `SubLevelContainer.tick()`
- `SubLevelContainer.processSubLevelRemovals()`
- `SubLevelHeatMapManager.tick()/split()`
- `ActiveSableCompanion.getContaining(...)`
- `SubLevelAssemblyHelper.assembleBlocks(...)`

Questions:

1. Should `SubLevelContainer.tick()` skip sublevels already marked removed?
2. Should `ServerSubLevel.tick()` return immediately if it is already removed, including if `super.tick()` causes it to become removed?
3. Should heat-map splitting itself refuse to run on a removed owner?
4. Should `getContaining(...)` ever return an already-removed sublevel?
5. Should `assembleBlocks(...)` gracefully treat a removed containing plot as absent, or is throwing useful to detect a violated invariant?
6. Which location provides the safest fix without hiding other lifecycle bugs?

Prefer fixing the lifecycle invariant before weakening the exception in `assembleBlocks()`.

## Next regression: crash-safe relation/storage cleanup

Once #1376 is reproduced and fixed, add a test for the persistent side:

1. create related parent/child sublevels using the real relation mechanism;
2. remove/detach one member through normal runtime code;
3. assert surviving members no longer retain invalid loading dependencies;
4. persist/unload the entire dependency group;
5. reload with no member pre-instantiated;
6. assert all valid surviving members bootstrap;
7. assert no `.slvlr` holding entry points at an empty `.slvls` slot.

If possible, exercise an interrupted/error path between removal and persistence so the storage layer can prove it remains consistent even if runtime processing fails.

## `/sable storage repair`

A recovery command would have saved this player's intact castle without binary editing.

Design it conservatively.

Suggested default:

`/sable storage repair` = dry-run only.

Report at least:

- holding pointers whose target `.slvls` slot is empty/invalid
- stored UUIDs with missing `loading_dependencies`
- circular dependency groups
- stored-but-not-live sublevels
- missing/incorrect holding-chunk registrations when detectable
- extreme/suspicious saved linear or angular velocity

Possible explicit repair operations:

- remove provably dead pointers
- rebuild an unambiguous holding registration
- clear a selected missing dependency by UUID
- zero/clamp velocity as an explicit recovery operation
- storage-addressable `info` and `teleport` by UUID

Safety requirements:

- never delete block payloads as an incidental repair
- dry-run before mutation
- explicit apply/confirmation semantics
- idempotence: a second repair run makes no further changes
- produce enough diagnostics to back up / manually recover before destructive action

## Acceptance criteria

At minimum:

- the new #1376 GameTest fails on buggy behavior and passes after the fix;
- an already-removed sublevel cannot execute a split that assembles into its own removed plot;
- child removal cannot leave a surviving parent with a missing dependency;
- no holding pointer survives if its target storage slot no longer exists;
- a fully unloaded valid dependency group can load again;
- intact storage-only sublevels have an in-game recovery/inspection path;
- recovery tooling does not touch unrelated healthy sublevels.

## Upstream references

- #1376: https://github.com/ryanhcode/sable/issues/1376
- #1387: https://github.com/ryanhcode/sable/issues/1387
- Sable: https://github.com/ryanhcode/sable

This investigation came from a real damaged save and was performed collaboratively by the player and ChatGPT (GPT-5.6 Pro). The offline castle repair was verified in-game.
