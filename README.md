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

## Guide

1. Use `/chrc` to import your waypoints.
   Make sure to number the waypoints in the correct order.
2. Run `/chrc start`.
3. Move close to each waypoint location.
4. CHRC will check whether your waypoint is affected by or overlaps with a structure.

