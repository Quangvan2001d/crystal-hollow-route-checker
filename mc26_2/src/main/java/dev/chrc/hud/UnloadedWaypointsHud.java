package dev.chrc.hud;

import dev.chrc.config.ChrcConfig;
import dev.chrc.config.ConfigManager;
import dev.chrc.route.ChrcWaypoint;
import dev.chrc.route.RouteManager;
import dev.chrc.scanner.ScanManager;
import dev.chrc.scanner.WaypointScanState;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;

/** HUD table for every waypoint that has not been scanned in the active run. */
public final class UnloadedWaypointsHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("chrc", "unscanned_waypoints");

    private UnloadedWaypointsHud() {}

    public static void init() {
        // This is the same HUD registry path used by Skyblocker 6.5.3 on 26.1.2.
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, ID, UnloadedWaypointsHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        ChrcConfig config = ConfigManager.get();
        if (!config.enabled || !config.unloadedWaypointsHudEnabled || !ScanManager.isRunning()) return;
        if (client.level == null || client.player == null || RouteManager.get().waypoints.isEmpty()) return;

        List<ChrcWaypoint> unscanned = ScanManager.unscannedWaypoints();
        if (unscanned.isEmpty()) return;

        Font font = client.font;
        int visibleRows = Math.min(config.unloadedWaypointsHudMaxRows, unscanned.size());
        List<Component> lines = new ArrayList<>(visibleRows);
        Component title = Component.literal("CHRC - Unscanned (" + unscanned.size() + ")")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        int panelWidth = font.width(title) + 16;

        for (int i = 0; i < visibleRows; i++) {
            ChrcWaypoint waypoint = unscanned.get(i);
            int routeIndex = RouteManager.get().waypoints.indexOf(waypoint) + 1;
            WaypointScanState state = ScanManager.stateFor(waypoint);

            Component line = Component.literal("#" + routeIndex + " ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(waypoint.name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  [" + waypoint.x + ", " + waypoint.y + ", " + waypoint.z + "]").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("  " + statusText(state)).withStyle(statusColor(state)));
            lines.add(line);
            panelWidth = Math.max(panelWidth, font.width(line) + 16);
        }

        boolean hasMore = unscanned.size() > visibleRows;
        Component moreLine = hasMore
                ? Component.literal("+ " + (unscanned.size() - visibleRows) + " more waypoint(s)").withStyle(ChatFormatting.GRAY)
                : null;
        if (moreLine != null) panelWidth = Math.max(panelWidth, font.width(moreLine) + 16);

        Component progressLine = Component.literal("Checked: " + ScanManager.scannedCount() + "/" + RouteManager.get().waypoints.size())
                .withStyle(ChatFormatting.GRAY);
        panelWidth = Math.max(panelWidth, font.width(progressLine) + 16);

        float scale = config.unloadedWaypointsHudScalePercent / 100.0F;
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int logicalMaxWidth = Math.max(120, (int) (screenWidth / scale) - 8);
        panelWidth = Math.min(panelWidth, logicalMaxWidth);

        int rowHeight = font.lineHeight + 3;
        int panelHeight = 33 + visibleRows * rowHeight + (hasMore ? rowHeight : 0) + 5;
        int scaledPanelWidth = Math.round(panelWidth * scale);
        int actualX = config.unloadedWaypointsHudX < 0
                ? (screenWidth - scaledPanelWidth) / 2
                : config.unloadedWaypointsHudX;
        int actualY = config.unloadedWaypointsHudY;

        int x = Math.round(actualX / scale);
        int y = Math.round(actualY / scale);

        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);

        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xC8000000);
        graphics.fill(x, y, x + panelWidth, y + 1, 0xFF55FFFF);
        graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, 0xFF555555);
        graphics.fill(x, y, x + 1, y + panelHeight, 0xFF555555);
        graphics.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, 0xFF555555);

        graphics.centeredText(font, title, x + panelWidth / 2, y + 6, CommonColors.WHITE);
        graphics.text(font, progressLine, x + 8, y + 19, CommonColors.GRAY);

        int textY = y + 33;
        for (Component line : lines) {
            graphics.text(font, line, x + 8, textY, CommonColors.WHITE);
            textY += rowHeight;
        }
        if (moreLine != null) graphics.text(font, moreLine, x + 8, textY, CommonColors.GRAY);

        graphics.pose().popMatrix();
    }

    private static String statusText(WaypointScanState state) {
        return switch (state) {
            case WAITING_FOR_PREVIOUS -> "WAITING PREVIOUS";
            case NOT_LOADED -> "NOT LOADED";
            case WAITING_FOR_SCAN_AREA -> "WAITING AREA";
            case READY -> "READY";
            case SCANNED -> "SCANNED";
        };
    }

    private static ChatFormatting statusColor(WaypointScanState state) {
        return switch (state) {
            case WAITING_FOR_PREVIOUS -> ChatFormatting.GRAY;
            case NOT_LOADED -> ChatFormatting.RED;
            case WAITING_FOR_SCAN_AREA -> ChatFormatting.YELLOW;
            case READY -> ChatFormatting.AQUA;
            case SCANNED -> ChatFormatting.GREEN;
        };
    }
}
