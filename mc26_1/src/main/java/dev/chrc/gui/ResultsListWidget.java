package dev.chrc.gui;

import dev.chrc.config.ConfigManager;
import dev.chrc.route.ChrcWaypoint;
import dev.chrc.route.RouteManager;
import dev.chrc.scanner.GemstoneType;
import dev.chrc.scanner.ScanManager;
import dev.chrc.scanner.ScanResult;
import dev.chrc.scanner.WaypointScanState;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

public final class ResultsListWidget extends ContainerObjectSelectionList<ResultsListWidget.Entry> {
    private final int listWidth;
    public ResultsListWidget(Minecraft client, int width, int height, int y, int itemHeight) {
        super(client, width, height, y, itemHeight);
        this.listWidth = width;
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) addEntry(new Entry(waypoint));
    }

    @Override
    public int getRowWidth() {
        return Math.min(600, Math.max(320, listWidth - 32));
    }

    public final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        private final ChrcWaypoint waypoint;

        private Entry(ChrcWaypoint waypoint) {
            this.waypoint = waypoint;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY() + (getHeight() - Minecraft.getInstance().font.lineHeight) / 2;
            int number = RouteManager.get().waypoints.indexOf(waypoint) + 1;

            Component line = Component.literal("#" + number + " " + waypoint.name).withStyle(ChatFormatting.WHITE);
            ScanResult result = ScanManager.resultFor(waypoint);
            if (result == null) {
                if (ScanManager.isRunning()) {
                    WaypointScanState state = ScanManager.stateFor(waypoint);
                    switch (state) {
                        case WAITING_FOR_PREVIOUS -> line = line.copy().append(Component.literal("  |  WAITING FOR PREVIOUS WAYPOINT").withStyle(ChatFormatting.GRAY));
                        case NOT_LOADED -> line = line.copy().append(Component.literal("  |  NOT LOADED").withStyle(ChatFormatting.RED));
                        case WAITING_FOR_SCAN_AREA -> line = line.copy().append(Component.literal("  |  WAITING FOR SCAN AREA").withStyle(ChatFormatting.YELLOW));
                        case READY -> line = line.copy().append(Component.literal("  |  READY").withStyle(ChatFormatting.AQUA));
                        case SCANNED -> line = line.copy().append(Component.literal("  |  SCANNED").withStyle(ChatFormatting.GREEN));
                    }
                } else {
                    line = line.copy().append(Component.literal("  |  UNSCANNED").withStyle(ChatFormatting.GRAY));
                }
            } else {
                for (GemstoneType type : ConfigManager.get().selectedGemstones) {
                    line = line.copy()
                            .append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                            .append(Component.literal(type.displayName() + ": " + result.count(type)).withStyle(type.color()));
                }
                line = line.copy()
                        .append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.literal(result.valid() ? "TRUE" : "FALSE")
                                .withStyle(result.valid() ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            graphics.text(Minecraft.getInstance().font, line, x, y, CommonColors.WHITE);
        }
    }
}
