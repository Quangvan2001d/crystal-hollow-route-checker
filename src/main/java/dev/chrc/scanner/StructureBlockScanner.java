package dev.chrc.scanner;

import dev.chrc.route.ChrcWaypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Structure guard for route waypoints.
 *
 * Structure blocks are scanned with the same cube algorithm as gemstones,
 * centered at waypoint Y + 2, but with a fixed structure radius of 6 blocks.
 * The original waypoint block itself is skipped because route waypoints are
 * commonly placed on a player-created cobblestone Etherwarp landing block.
 */
public final class StructureBlockScanner {
    public static final int STRUCTURE_SCAN_RADIUS = 6;

    /**
     * Centralized Crystal Hollows structure palette so it can be adjusted
     * without touching route/scan state logic.
     */
    private static final Set<Block> STRUCTURE_BLOCKS = Set.of(
            Blocks.COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE,
            Blocks.COBBLESTONE_SLAB,
            Blocks.COBBLESTONE_STAIRS,
            Blocks.COBBLESTONE_WALL,
            Blocks.MOSSY_COBBLESTONE_SLAB,
            Blocks.MOSSY_COBBLESTONE_STAIRS,
            Blocks.MOSSY_COBBLESTONE_WALL,

            Blocks.STONE_BRICKS,
            Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS,
            Blocks.CHISELED_STONE_BRICKS,
            Blocks.STONE_BRICK_SLAB,
            Blocks.STONE_BRICK_STAIRS,
            Blocks.STONE_BRICK_WALL,
            Blocks.MOSSY_STONE_BRICK_SLAB,
            Blocks.MOSSY_STONE_BRICK_STAIRS,
            Blocks.MOSSY_STONE_BRICK_WALL,

            Blocks.BRICKS,
            Blocks.BRICK_SLAB,
            Blocks.BRICK_STAIRS,
            Blocks.BRICK_WALL,

            Blocks.NETHER_BRICKS,
            Blocks.NETHER_BRICK_SLAB,
            Blocks.NETHER_BRICK_STAIRS,
            Blocks.NETHER_BRICK_FENCE,
            Blocks.NETHER_BRICK_WALL,
            Blocks.RED_NETHER_BRICKS,
            Blocks.RED_NETHER_BRICK_SLAB,
            Blocks.RED_NETHER_BRICK_STAIRS,
            Blocks.RED_NETHER_BRICK_WALL,

            Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_ANDESITE_SLAB,
            Blocks.POLISHED_ANDESITE_STAIRS,
            Blocks.POLISHED_DIORITE,
            Blocks.POLISHED_DIORITE_SLAB,
            Blocks.POLISHED_DIORITE_STAIRS,
            Blocks.POLISHED_GRANITE,
            Blocks.POLISHED_GRANITE_SLAB,
            Blocks.POLISHED_GRANITE_STAIRS,

            Blocks.OAK_PLANKS,
            Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_PLANKS,
            Blocks.JUNGLE_PLANKS,
            Blocks.ACACIA_PLANKS,
            Blocks.DARK_OAK_PLANKS,
            Blocks.OAK_LOG,
            Blocks.SPRUCE_LOG,
            Blocks.BIRCH_LOG,
            Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG,
            Blocks.DARK_OAK_LOG,
            Blocks.OAK_FENCE,
            Blocks.SPRUCE_FENCE,
            Blocks.BIRCH_FENCE,
            Blocks.JUNGLE_FENCE,
            Blocks.ACACIA_FENCE,
            Blocks.DARK_OAK_FENCE,

            Blocks.IRON_BARS,
            Blocks.LANTERN,
            Blocks.SOUL_LANTERN,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE
    );

    private StructureBlockScanner() {}

    /**
     * Scans an already-loaded cube using the same traversal style as GemstoneScanner.
     * The exact original waypoint coordinate is excluded to avoid treating the
     * player's own Etherwarp cobblestone landing block as a structure hit.
     */
    public static List<Detection> scanArea(
            ClientLevel level,
            GemstoneScanner.ScanArea area,
            ChrcWaypoint waypoint
    ) {
        List<Detection> detections = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = area.minX; x <= area.maxX; x++) {
            for (int y = area.minY; y <= area.maxY; y++) {
                for (int z = area.minZ; z <= area.maxZ; z++) {
                    // Do not flag the route's own landing block.
                    if (x == waypoint.x && y == waypoint.y && z == waypoint.z) continue;

                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (STRUCTURE_BLOCKS.contains(state.getBlock())) {
                        detections.add(new Detection(pos.immutable(), state));
                    }
                }
            }
        }

        return detections;
    }

    /** Stable, readable name for latest.log/console without registry reflection. */
    public static String readableBlockName(BlockState state) {
        String id = state.getBlock().getDescriptionId();
        if (id == null || id.isBlank()) return String.valueOf(state.getBlock());
        return id.startsWith("block.minecraft.") ? id.substring("block.minecraft.".length()) : id;
    }

    public record Detection(BlockPos pos, BlockState state) {}
}
