package dev.chrc.world;

import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.world.level.ChunkPos;

/**
 * Tracks the chunks for which the client has received a real chunk-load event.
 *
 * CHRC deliberately uses these events instead of treating an arbitrary
 * ClientLevel lookup as proof that a far-away chunk is available. On a server
 * with a limited view distance, a waypoint is therefore left pending until its
 * chunk is actually delivered to the client.
 */
public final class LoadedChunkTracker {
    private static final Set<Long> LOADED = new HashSet<>();

    private LoadedChunkTracker() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> LOADED.clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> LOADED.clear());

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            ChunkPos pos = chunk.getPos();
            LOADED.add(key(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            ChunkPos pos = chunk.getPos();
            LOADED.remove(key(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4));
        });
    }

    public static boolean isLoaded(int chunkX, int chunkZ) {
        return LOADED.contains(key(chunkX, chunkZ));
    }

    public static int loadedCount() {
        return LOADED.size();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
