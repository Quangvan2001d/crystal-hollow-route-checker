package dev.chrc.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class SkyblockerWaypointImporter {
    public static final String PREFIX = "[Skyblocker-Waypoint-Data-V1]";

    private SkyblockerWaypointImporter() {}

    public static ImportResult fromClipboard(String raw) {
        if (raw == null) return ImportResult.error("Clipboard is empty.");
        String input = raw.trim();
        if (!input.startsWith(PREFIX)) return ImportResult.error("Clipboard is not Skyblocker waypoint data.");

        try {
            String payload = input.substring(PREFIX.length()).trim();
            byte[] compressed = Base64.getDecoder().decode(payload);
            String json;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }

            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) return ImportResult.error("Skyblocker waypoint data is not a waypoint-group list.");

            List<GroupData> importedGroups = new ArrayList<>();
            for (JsonElement groupElement : root.getAsJsonArray()) {
                if (!groupElement.isJsonObject()) continue;
                JsonObject group = groupElement.getAsJsonObject();
                // CHRC accepts every valid Skyblocker waypoint group. The island
                // field is metadata only; scanning still depends on the waypoint
                // chunk actually being loaded by the Minecraft client.
                String groupName = getString(group, "name", "CHRC Route");
                JsonArray waypointArray = group.has("waypoints") && group.get("waypoints").isJsonArray()
                        ? group.getAsJsonArray("waypoints") : new JsonArray();
                List<ChrcWaypoint> waypoints = new ArrayList<>();
                for (JsonElement waypointElement : waypointArray) {
                    ChrcWaypoint waypoint = parseWaypoint(waypointElement);
                    if (waypoint != null) waypoints.add(waypoint);
                }
                if (!waypoints.isEmpty()) importedGroups.add(new GroupData(groupName, waypoints));
            }

            if (importedGroups.isEmpty()) return ImportResult.error("No valid Skyblocker waypoints were found.");

            ChrcRoute route = new ChrcRoute();
            route.name = importedGroups.size() == 1 ? importedGroups.getFirst().name : "Imported Skyblocker Route";
            for (GroupData group : importedGroups) route.waypoints.addAll(group.waypoints);
            route.normalize();
            return ImportResult.success(route, importedGroups.size());
        } catch (Exception e) {
            return ImportResult.error("Invalid Skyblocker waypoint data: " + e.getClass().getSimpleName());
        }
    }

    private static ChrcWaypoint parseWaypoint(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        if (!object.has("pos")) return null;
        int[] pos = parsePos(object.get("pos"));
        if (pos == null) return null;
        String name = parseComponentName(object.get("name"));
        return new ChrcWaypoint(name, pos[0], pos[1], pos[2]);
    }

    private static int[] parsePos(JsonElement pos) {
        if (pos == null) return null;
        if (pos.isJsonArray()) {
            JsonArray array = pos.getAsJsonArray();
            if (array.size() < 3) return null;
            return new int[]{array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
        }
        if (pos.isJsonObject()) {
            JsonObject object = pos.getAsJsonObject();
            if (object.has("x") && object.has("y") && object.has("z")) {
                return new int[]{object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt()};
            }
        }
        return null;
    }

    private static String parseComponentName(JsonElement name) {
        if (name == null || name.isJsonNull()) return "Waypoint";
        if (name.isJsonPrimitive()) return name.getAsString();
        if (name.isJsonObject()) {
            JsonObject object = name.getAsJsonObject();
            if (object.has("text") && object.get("text").isJsonPrimitive()) return object.get("text").getAsString();
            if (object.has("translate") && object.get("translate").isJsonPrimitive()) return object.get("translate").getAsString();
        }
        return name.toString();
    }

    private static String getString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record GroupData(String name, List<ChrcWaypoint> waypoints) {}

    public record ImportResult(boolean success, ChrcRoute route, int groups, String error) {
        public static ImportResult success(ChrcRoute route, int groups) {
            return new ImportResult(true, route, groups, null);
        }

        public static ImportResult error(String message) {
            return new ImportResult(false, null, 0, message);
        }
    }
}
