package dev.chrc.config;

import dev.chrc.scanner.GemstoneType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ChrcConfig {
    public boolean enabled = true;
    public int scanRadius = 3;
    public int structureScanRadius = 6;

    // Detached-camera movement speed in blocks per client tick.
    public float freecamSpeed = 0.45f;

    // Chat / mining ability notifications. All are opt-in and disabled by default.
    public boolean hidePristineMessages = false;
    public boolean hidePickaxeAbilityUsedMessages = false;
    public boolean hideAbilityExpiredMessages = false;
    public boolean showAbilityStatusTitles = false;

    // Crystal Hollows goblin knockback helper. Disabled and unbound by default.
    public boolean goblinKnockbackEnabled = false;
    // GLFW keyboard key code; -1 is GLFW_KEY_UNKNOWN / Not Bound.
    public int goblinKnockbackMacroKey = -1;

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
        if (!Float.isFinite(freecamSpeed)) freecamSpeed = 0.45f;
        freecamSpeed = Math.clamp(freecamSpeed, 0.05f, 5.0f);
        if (goblinKnockbackMacroKey < -1 || goblinKnockbackMacroKey > 348) goblinKnockbackMacroKey = -1;
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
