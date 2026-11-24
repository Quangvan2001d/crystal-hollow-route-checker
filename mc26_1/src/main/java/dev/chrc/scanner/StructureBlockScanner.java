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
 * centered at waypoint Y + 2, with a configurable structure radius.
 * The original waypoint block itself is skipped because route waypoints are
 * commonly placed on a player-created cobblestone Etherwarp landing block.
 */
public final class StructureBlockScanner {
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

            // Additional Crystal Hollows structure materials.
            Blocks.GRAVEL,
            Blocks.DIRT,
            Blocks.POPPY,
            Blocks.GRAY_WOOL,
            Blocks.LIGHT_GRAY_WOOL,

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

    /**
     * Extra structure block ids kept as strings so the checker remains tolerant
     * of mapping/version differences for decorative Crystal Hollows blocks.
     *
     * Leaves are handled separately by the *_leaves suffix below.
     */
    private static final Set<String> ADDITIONAL_STRUCTURE_BLOCK_IDS = Set.of(
            // Vegetation seen inside structures.
            "grass_block",
            "short_grass",
            "tall_grass",

            // Dark smooth/decorative blocks used by Crystal Hollows structures.
            "gray_concrete",
            "black_concrete",
            "gray_terracotta",
            "black_terracotta",
            "cyan_terracotta",
            "black_wool",

            // Dark stone families.
            "deepslate",
            "cobbled_deepslate",
            "polished_deepslate",
            "deepslate_bricks",
            "cracked_deepslate_bricks",
            "deepslate_tiles",
            "cracked_deepslate_tiles",
            "chiseled_deepslate",
            "blackstone",
            "gilded_blackstone",
            "polished_blackstone",
            "polished_blackstone_bricks",
            "cracked_polished_blackstone_bricks",
            "chiseled_polished_blackstone",
            "smooth_basalt"
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
                    if (isStructureBlock(state)) {
                        detections.add(new Detection(pos.immutable(), state));
                    }
                }
            }
        }

        return detections;
    }

    private static boolean isStructureBlock(BlockState state) {
        if (STRUCTURE_BLOCKS.contains(state.getBlock())) return true;

        String id = readableBlockName(state);

        // Covers oak/spruce/birch/jungle/acacia/dark-oak/mangrove/cherry/etc.
        // without needing a version-specific constant for every leaf type.
        if (id.endsWith("_leaves")) return true;

        return ADDITIONAL_STRUCTURE_BLOCK_IDS.contains(id);
    }

    /** Stable, readable name for latest.log/console without registry reflection. */
    public static String readableBlockName(BlockState state) {
        String id = state.getBlock().getDescriptionId();
        if (id == null || id.isBlank()) return String.valueOf(state.getBlock());
        return id.startsWith("block.minecraft.") ? id.substring("block.minecraft.".length()) : id;
    }

    public record Detection(BlockPos pos, BlockState state) {}
}
