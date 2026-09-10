package dev.chrc.scanner;

import dev.chrc.config.ConfigManager;
import dev.chrc.route.ChrcWaypoint;
import dev.chrc.route.RouteManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class ScanManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CHRC");
    private static final Map<String, ScanResult> RESULTS = new LinkedHashMap<>();
    private static final Map<String, GemstoneScanner.ScanArea> SCAN_AREAS = new LinkedHashMap<>();
    private static final Map<String, GemstoneScanner.ScanArea> STRUCTURE_SCAN_AREAS = new LinkedHashMap<>();
    private static final Map<String, WaypointScanState> STATES = new LinkedHashMap<>();

    /**
     * Gemstone coordinates already owned by an earlier waypoint in this run.
     * This is intentionally runtime-only and is cleared on every new Start.
     */
    private static final Set<BlockPos> IGNORED_GEMSTONE_BLOCKS = new HashSet<>();

    private static boolean running;
    private static boolean completionMessageSent;

    private ScanManager() {}

    public static boolean start() {
        if (RouteManager.get().waypoints.isEmpty()) return false;

        RESULTS.clear();
        SCAN_AREAS.clear();
        STRUCTURE_SCAN_AREAS.clear();
        STATES.clear();
        IGNORED_GEMSTONE_BLOCKS.clear();
        completionMessageSent = false;

        List<ChrcWaypoint> waypoints = RouteManager.get().waypoints;
        for (int i = 0; i < waypoints.size(); i++) {
            ChrcWaypoint waypoint = waypoints.get(i);
            waypoint.resetScanState();
            STATES.put(waypoint.id, i == 0
                    ? WaypointScanState.NOT_LOADED
                    : WaypointScanState.WAITING_FOR_PREVIOUS);
        }

        running = true;
        return true;
    }

    public static void reset() {
        RESULTS.clear();
        SCAN_AREAS.clear();
        STRUCTURE_SCAN_AREAS.clear();
        STATES.clear();
        IGNORED_GEMSTONE_BLOCKS.clear();
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) waypoint.resetScanState();
        running = false;
        completionMessageSent = false;
    }

    /** Manual stop. Keeps already collected results and sends no completion summary. */
    public static void stop() {
        running = false;
    }

    public static void tick(Minecraft client) {
        if (!running || !ConfigManager.get().enabled || client.level == null || client.player == null) return;

        List<ChrcWaypoint> waypoints = RouteManager.get().waypoints;
        int nextIndex = firstUnscannedIndex(waypoints);
        if (nextIndex < 0) {
            finishIfComplete();
            return;
        }

        // Strict route order: only the first unscanned route entry is allowed to
        // progress. A loaded waypoint #8 cannot scan before pending waypoint #1.
        for (int i = nextIndex + 1; i < waypoints.size(); i++) {
            ChrcWaypoint later = waypoints.get(i);
            if (!later.scanned) STATES.put(later.id, WaypointScanState.WAITING_FOR_PREVIOUS);
        }

        ChrcWaypoint waypoint = waypoints.get(nextIndex);

        // Gate 1: do not even create the scan cube until this waypoint's chunk
        // has actually been delivered to the client.
        if (!GemstoneScanner.isWaypointLoaded(client.level, waypoint)) {
            STATES.put(waypoint.id, WaypointScanState.NOT_LOADED);
            SCAN_AREAS.remove(waypoint.id);
            STRUCTURE_SCAN_AREAS.remove(waypoint.id);
            return;
        }

        int radius = ConfigManager.get().scanRadius;
        GemstoneScanner.ScanArea area = SCAN_AREAS.get(waypoint.id);
        if (area == null) {
            area = GemstoneScanner.createScanArea(waypoint, radius);
            SCAN_AREAS.put(waypoint.id, area);
        }

        if (!GemstoneScanner.isAreaLoaded(client.level, area)) {
            STATES.put(waypoint.id, WaypointScanState.WAITING_FOR_SCAN_AREA);
            return;
        }

        // Structure scanning uses the same cube-style traversal as gemstone
        // scanning, but with its own fixed 6-block radius. Create this cube only
        // after the waypoint itself is loaded, and wait until every touched
        // chunk is available before reading any structure block from it.
        GemstoneScanner.ScanArea structureArea = STRUCTURE_SCAN_AREAS.get(waypoint.id);
        if (structureArea == null) {
            structureArea = GemstoneScanner.createScanArea(
                    waypoint,
                    StructureBlockScanner.STRUCTURE_SCAN_RADIUS
            );
            STRUCTURE_SCAN_AREAS.put(waypoint.id, structureArea);
        }

        if (!GemstoneScanner.isAreaLoaded(client.level, structureArea)) {
            STATES.put(waypoint.id, WaypointScanState.WAITING_FOR_SCAN_AREA);
            return;
        }

        STATES.put(waypoint.id, WaypointScanState.READY);

        // The original waypoint block itself is excluded so a player-placed
        // Etherwarp cobblestone does not automatically invalidate the waypoint.
        List<StructureBlockScanner.Detection> structureDetections =
                StructureBlockScanner.scanArea(client.level, structureArea, waypoint);

        // Still perform the gemstone scan even if a structure block is found.
        // This preserves route-order ignore semantics: gemstone coordinates in
        // waypoint #1 remain claimed and cannot be counted again by #2+.
        GemstoneScanner.ScanOutcome outcome = GemstoneScanner.scan(
                client.level,
                area,
                ConfigManager.get().selectedGemstones,
                IGNORED_GEMSTONE_BLOCKS
        );

        ScanResult finalResult = outcome.result();
        if (!structureDetections.isEmpty()) {
            // Any matching structure block in the scan cube forces this waypoint
            // FALSE, even when all selected gemstone requirements would otherwise pass.
            finalResult = new ScanResult(finalResult.counts(), false);

            for (StructureBlockScanner.Detection detection : structureDetections) {
                BlockPos detectedPos = detection.pos();
                LOGGER.info(
                        "[CHRC] Structure block detected at waypoint #{} '{}' [{} {} {}]: {} at [{} {} {}]",
                        nextIndex + 1,
                        waypoint.name,
                        waypoint.x, waypoint.y, waypoint.z,
                        StructureBlockScanner.readableBlockName(detection.state()),
                        detectedPos.getX(), detectedPos.getY(), detectedPos.getZ()
                );
            }
        }

        RESULTS.put(waypoint.id, finalResult);
        // Claim every newly counted selected gemstone coordinate so no later
        // waypoint can count the same physical gemstone block again.
        IGNORED_GEMSTONE_BLOCKS.addAll(outcome.newlyClaimed());
        waypoint.scanned = true;
        STATES.put(waypoint.id, WaypointScanState.SCANNED);

        finishIfComplete();
    }

    private static int firstUnscannedIndex(List<ChrcWaypoint> waypoints) {
        for (int i = 0; i < waypoints.size(); i++) {
            if (!waypoints.get(i).scanned) return i;
        }
        return -1;
    }

    private static void finishIfComplete() {
        if (!completionMessageSent && allWaypointsScanned()) {
            completionMessageSent = true;
            running = false;
            sendCompletionSummary();
        }
    }

    private static boolean allWaypointsScanned() {
        List<ChrcWaypoint> waypoints = RouteManager.get().waypoints;
        if (waypoints.isEmpty()) return false;
        for (ChrcWaypoint waypoint : waypoints) {
            if (!waypoint.scanned || !RESULTS.containsKey(waypoint.id)) return false;
        }
        return true;
    }

    private static void sendCompletionSummary() {
        int trueCount = trueCount();
        int falseCount = falseCount();
        int total = RouteManager.get().waypoints.size();

        Component message = Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Scan Complete — ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(trueCount + " TRUE").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(falseCount + " FALSE").withStyle(ChatFormatting.RED))
                .append(Component.literal(" | Total: " + total).withStyle(ChatFormatting.GRAY));
        send(message);
    }

    public static ScanResult resultFor(ChrcWaypoint waypoint) {
        return RESULTS.get(waypoint.id);
    }

    public static WaypointScanState stateFor(ChrcWaypoint waypoint) {
        if (waypoint.scanned) return WaypointScanState.SCANNED;
        return STATES.getOrDefault(waypoint.id, WaypointScanState.WAITING_FOR_PREVIOUS);
    }

    public static int scannedCount() {
        int count = 0;
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) if (waypoint.scanned) count++;
        return count;
    }

    public static int trueCount() {
        return (int) RESULTS.values().stream().filter(ScanResult::valid).count();
    }

    public static int falseCount() {
        return (int) RESULTS.values().stream().filter(result -> !result.valid()).count();
    }

    public static int unscannedCount() {
        return RouteManager.get().waypoints.size() - scannedCount();
    }

    /** Returns the first route entry that has not completed its one-time scan. */
    public static ChrcWaypoint currentPendingWaypoint() {
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) {
            if (!waypoint.scanned) return waypoint;
        }
        return null;
    }

    public static boolean isRunning() {
        return running;
    }

    public static int ignoredGemstoneBlockCount() {
        return IGNORED_GEMSTONE_BLOCKS.size();
    }

    public static boolean hasScanArea(ChrcWaypoint waypoint) {
        return SCAN_AREAS.containsKey(waypoint.id);
    }

    /** All route entries whose explicit scanned flag is still false. */
    public static List<ChrcWaypoint> unscannedWaypoints() {
        List<ChrcWaypoint> unscanned = new ArrayList<>();
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) {
            if (!waypoint.scanned) unscanned.add(waypoint);
        }
        return unscanned;
    }

    /** Compatibility helper for UI code. */
    public static List<ChrcWaypoint> unloadedWaypoints(ClientLevel level) {
        List<ChrcWaypoint> unloaded = new ArrayList<>();
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) {
            if (!waypoint.scanned && !GemstoneScanner.isWaypointLoaded(level, waypoint)) unloaded.add(waypoint);
        }
        return unloaded;
    }

    public static void routeChanged() {
        reset();
    }

    private static void send(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) client.gui.getChat().addClientSystemMessage(component);
    }
}
