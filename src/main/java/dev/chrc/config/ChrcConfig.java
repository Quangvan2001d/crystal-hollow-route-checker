package dev.chrc.config;

import dev.chrc.scanner.GemstoneType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ChrcConfig {
    public boolean enabled = true;
    public int scanRadius = 3;
    public int structureScanRadius = 2;
    public List<GemstoneType> selectedGemstones = new ArrayList<>(List.of(GemstoneType.RUBY));

    // In-game table that shows route waypoints whose center chunk is not loaded yet.
    public boolean unloadedWaypointsHudEnabled = true;
    // -1 means horizontally centered. Other values are GUI pixel coordinates.
    public int unloadedWaypointsHudX = -1;
    public int unloadedWaypointsHudY = 8;
    public int unloadedWaypointsHudScalePercent = 100;
    public int unloadedWaypointsHudMaxRows = 8;

    public void normalize() {
        scanRadius = Math.clamp(scanRadius, 0, 32);
        structureScanRadius = Math.clamp(structureScanRadius, 1, 10);
        if (selectedGemstones == null) selectedGemstones = new ArrayList<>();
        selectedGemstones = new ArrayList<>(new LinkedHashSet<>(selectedGemstones));
        if (selectedGemstones.isEmpty()) selectedGemstones.add(GemstoneType.RUBY);

        unloadedWaypointsHudX = Math.clamp(unloadedWaypointsHudX, -1, 10000);
        unloadedWaypointsHudY = Math.clamp(unloadedWaypointsHudY, 0, 10000);
        unloadedWaypointsHudScalePercent = Math.clamp(unloadedWaypointsHudScalePercent, 50, 200);
        unloadedWaypointsHudMaxRows = Math.clamp(unloadedWaypointsHudMaxRows, 1, 30);
    }

    public boolean isSelected(GemstoneType type) {
        return selectedGemstones.contains(type);
    }

    public void setSelected(GemstoneType type, boolean selected) {
        if (selected) {
            if (!selectedGemstones.contains(type)) selectedGemstones.add(type);
            return;
        }
        if (selectedGemstones.size() <= 1 && selectedGemstones.contains(type)) return;
        selectedGemstones.remove(type);
        if (selectedGemstones.isEmpty()) selectedGemstones.add(GemstoneType.RUBY);
    }
}
