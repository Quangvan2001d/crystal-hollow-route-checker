package dev.chrc.scanner;

import dev.chrc.route.ChrcWaypoint;
import dev.chrc.world.LoadedChunkTracker;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class GemstoneScanner {
    /**
     * Waypoints are normally the cobblestone block used as an Etherwarp landing
     * point. The player's head is roughly two blocks above that block, so CHRC
     * centers the scan cube at waypoint Y + 2.
     */
    public static final int SCAN_CENTER_Y_OFFSET = 2;

    private GemstoneScanner() {}

    /**
     * A waypoint becomes eligible only after Fabric reports that its center
     * chunk was actually loaded on this client.
     */
    public static boolean isWaypointLoaded(ClientLevel level, ChrcWaypoint waypoint) {
        int chunkX = waypoint.x >> 4;
        int chunkZ = waypoint.z >> 4;
        return LoadedChunkTracker.isLoaded(chunkX, chunkZ) && level.hasChunk(chunkX, chunkZ);
    }

    /**
     * Creates a cube centered on (waypoint.x, waypoint.y + 2, waypoint.z).
     * Radius still means the number of blocks expanded in every direction from
     * that shifted center. For example radius 3 scans a 7x7x7 cube.
     */
    public static ScanArea createScanArea(ChrcWaypoint waypoint, int radius) {
        int centerY = waypoint.y + SCAN_CENTER_Y_OFFSET;
        return new ScanArea(
                waypoint.x - radius,
                waypoint.x + radius,
                centerY - radius,
                centerY + radius,
                waypoint.z - radius,
                waypoint.z + radius
        );
    }

    /**
     * Every chunk touched by the cube must have a real client chunk-load event
     * and still be present in ClientLevel. Otherwise this waypoint stays pending.
     */
    public static boolean isAreaLoaded(ClientLevel level, ScanArea area) {
        int minChunkX = area.minX >> 4;
        int maxChunkX = area.maxX >> 4;
        int minChunkZ = area.minZ >> 4;
        int maxChunkZ = area.maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!LoadedChunkTracker.isLoaded(chunkX, chunkZ) || !level.hasChunk(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    /**
     * Scans one waypoint while excluding gemstone coordinates claimed by an
     * earlier waypoint in the same run. Only the currently selected gemstone
     * types are counted and claimed for the ignore set.
     *
     * @param ignoredGemstoneBlocks coordinates already claimed by earlier route entries
     */
    public static ScanOutcome scan(
            ClientLevel level,
            ScanArea area,
            List<GemstoneType> selected,
            Set<BlockPos> ignoredGemstoneBlocks
    ) {
        EnumMap<GemstoneType, Integer> counts = new EnumMap<>(GemstoneType.class);
        for (GemstoneType type : GemstoneType.values()) counts.put(type, 0);

        Set<BlockPos> newlyClaimed = new HashSet<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = area.minX; x <= area.maxX; x++) {
            for (int y = area.minY; y <= area.maxY; y++) {
                for (int z = area.minZ; z <= area.maxZ; z++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    for (GemstoneType type : selected) {
                        if (!type.matches(state)) continue;

                        BlockPos immutable = pos.immutable();
                        // If waypoint #1 already claimed this exact gemstone block,
                        // waypoint #2+ must pretend it is not there.
                        if (ignoredGemstoneBlocks.contains(immutable)) break;

                        counts.put(type, counts.get(type) + 1);
                        newlyClaimed.add(immutable);
                        break;
                    }
                }
            }
        }

        boolean valid = !selected.isEmpty();
        for (GemstoneType type : selected) {
            if (counts.getOrDefault(type, 0) < 1) {
                valid = false;
                break;
            }
        }

        return new ScanOutcome(new ScanResult(counts, valid), newlyClaimed);
    }

    public record ScanOutcome(ScanResult result, Set<BlockPos> newlyClaimed) {}

    public static final class ScanArea {
        public final int minX;
        public final int maxX;
        public final int minY;
        public final int maxY;
        public final int minZ;
        public final int maxZ;

        private ScanArea(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }
}
