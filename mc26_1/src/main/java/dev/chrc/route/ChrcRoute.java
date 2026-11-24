package dev.chrc.route;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ChrcRoute {
    public String name = "CHRC Route";
    public List<ChrcWaypoint> waypoints = new ArrayList<>();

    public void normalize() {
        if (name == null || name.isBlank()) name = "CHRC Route";
        if (waypoints == null) waypoints = new ArrayList<>();
        waypoints.removeIf(wp -> wp == null);

        // Results are keyed by waypoint id, so also repair duplicate ids from
        // hand-edited/old route files instead of letting two entries share one result.
        Set<String> ids = new HashSet<>();
        for (ChrcWaypoint waypoint : waypoints) {
            waypoint.normalize();
            if (!ids.add(waypoint.id)) {
                do {
                    waypoint.id = UUID.randomUUID().toString();
                } while (!ids.add(waypoint.id));
            }
        }
    }
}
