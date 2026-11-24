package dev.chrc.route;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.chrc.config.ConfigManager;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RouteManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CHRC/Route");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ConfigManager.DIRECTORY.resolve("route.json");
    private static ChrcRoute route = new ChrcRoute();

    private RouteManager() {}

    public static ChrcRoute get() {
        return route;
    }

    public static void load() {
        try {
            Files.createDirectories(ConfigManager.DIRECTORY);
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    ChrcRoute loaded = GSON.fromJson(reader, ChrcRoute.class);
                    if (loaded != null) route = loaded;
                }
            }
            route.normalize();
        } catch (Exception e) {
            LOGGER.error("Failed to load CHRC route", e);
            route = new ChrcRoute();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(ConfigManager.DIRECTORY);
            route.normalize();
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(route, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save CHRC route", e);
        }
    }

    public static void replace(ChrcRoute imported) {
        route = imported == null ? new ChrcRoute() : imported;
        route.normalize();
        save();
    }

    public static ChrcWaypoint addWaypoint() {
        Minecraft client = Minecraft.getInstance();
        int x = 0, y = 0, z = 0;
        if (client.player != null) {
            var pos = client.player.blockPosition();
            x = pos.getX();
            y = pos.getY();
            z = pos.getZ();
        }
        ChrcWaypoint waypoint = new ChrcWaypoint("Waypoint " + (route.waypoints.size() + 1), x, y, z);
        route.waypoints.add(waypoint);
        save();
        return waypoint;
    }

    public static void removeWaypoint(ChrcWaypoint waypoint) {
        route.waypoints.remove(waypoint);
        save();
    }

    public static void clear() {
        route = new ChrcRoute();
        save();
    }
}
