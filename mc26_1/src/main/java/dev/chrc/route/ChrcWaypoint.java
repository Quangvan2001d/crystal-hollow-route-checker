package dev.chrc.route;

import java.util.UUID;
import net.minecraft.core.BlockPos;

public final class ChrcWaypoint {
    public String id = UUID.randomUUID().toString();
    public String name = "Waypoint";
    public int x;
    public int y;
    public int z;

    /** Runtime-only flag for the active route check. Gson ignores transient fields. */
    public transient boolean scanned = false;

    public ChrcWaypoint() {}

    public ChrcWaypoint(String name, int x, int y, int z) {
        this.name = name == null || name.isBlank() ? "Waypoint" : name;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public void resetScanState() {
        scanned = false;
    }

    public void normalize() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null || name.isBlank()) name = "Waypoint";
    }
}
