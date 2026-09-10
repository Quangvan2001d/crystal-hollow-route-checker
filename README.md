# CHRC 1.0.0 — Minecraft 26.1.2 / Fabric

## Usage Guide

1. Import waypoints from Skyblocker and number them in route order.
2. Type `/chrc start`.
3. Move closer to the waypoint currently being displayed.

## 1.0 current-target waypoint

- Author metadata: `quangvan`.
- While a route check is running, CHRC renders exactly one world waypoint: the first route entry whose `scanned` flag is still false.
- The marker is kept as the same runtime object while the pending route entry is unchanged; each client tick only checks whether the pending entry changed.
- After that waypoint completes its scan, the marker advances to the next unscanned route entry.
- Rendering uses Skyblocker's primitive collector when Skyblocker is installed: filled box + outline + beacon-style marker + name, with through-walls rendering enabled.
- If Skyblocker's render API is unavailable, scanning/HUD/commands continue to work and only the world marker is skipped.

Crystal Hollow Route Checker (CHRC) is a client-side route checker that imports Skyblocker waypoint data and scans loaded client block data for selected gemstones.

## Main behavior
- `/chrc` opens settings.
- `/chrc start` starts a fresh route check if at least one waypoint exists.
- `/chrc stop` manually stops the active check and hides the unscanned HUD.
- A normal Minecraft Controls keybind named **Toggle Route Check** is registered under **CHRC** and defaults to **Not Bound**.
- Default scan radius is **3**, producing a 7×7×7 cube.
- The scan cube is centered at **(waypoint X, waypoint Y + 2, waypoint Z)**.
- Waypoints are scanned strictly in route order: #1, then #2, then #3, etc.
- CHRC never creates the scan cube for the next waypoint until the current waypoint's chunk is loaded.
- Each waypoint is scanned once per run.
- Selected gemstone blocks counted by an earlier waypoint are stored in a runtime ignore set. Later waypoints cannot count the same coordinates again.
- If all matching gemstone blocks in a later area are already ignored, its selected gemstone count is 0 and the waypoint can become FALSE.
- Automatic completion and chat summary happen only after every waypoint has actually been scanned.

## Build
Requires Java 25.

```bat
gradlew.bat clean build
```

## Keybind crash fix

- Registers the CHRC key mapping eagerly from `CHRCClient.onInitializeClient()` instead of relying on lazy static class initialization from the first client tick.
- Keeps the default toggle key as **Not Bound**.
- Prevents `IllegalStateException: GameOptions has already been initialised` on Minecraft 26.1.2 / Fabric.

## Waypoint renderer access fix

- Fixes `IllegalAccessException` when CHRC registers its Skyblocker render callback on Java 25.
- The previous code reflected `register()` from Fabric's package-private `ArrayBackedEvent` implementation.
- CHRC now resolves `register()` from Fabric's public `Event` API class, so the waypoint renderer can hook before `/chrc start`.

## Waypoint marker fix
- Creates/synchronizes waypoint #1 immediately when a scan starts.
- Uses Skyblocker's real `NamedWaypoint` renderer at runtime instead of duplicating primitive calls.
- Keeps exactly one through-walls marker and only replaces it when the first unscanned route entry changes.
- Render target is synced before scanner progress each client tick, preventing waypoint #1 from being skipped visually when it is already loaded.
- Prints a visible CHRC warning and console error if the optional Skyblocker rendering hook cannot be established.




## Structure check

- CHRC scans structure blocks with the same cube-style logic used for gemstone scanning.
- Structure scan radius is configurable in `/chrc` → **General**, from **1 to 10 blocks**.
- Default structure scan radius is **6 blocks**, producing a **13 x 13 x 13** cube.
- The structure cube uses the same **waypoint Y + 2** scan center.
- CHRC waits until every chunk touched by this structure cube is loaded before scanning it.
- If any configured structure block is found, the waypoint is forced to **FALSE**.
- Each detected structure block and its coordinates are written to the Minecraft console / `latest.log`.
- The exact original waypoint block is skipped so a player-placed Etherwarp cobblestone does not invalidate the waypoint by itself.
- Gemstone scanning and route-order gemstone ignore logic still run normally.
