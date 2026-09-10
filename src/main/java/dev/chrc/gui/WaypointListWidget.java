package dev.chrc.gui;

import dev.chrc.route.ChrcWaypoint;
import dev.chrc.route.RouteManager;
import dev.chrc.scanner.ScanManager;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

public final class WaypointListWidget extends ContainerObjectSelectionList<WaypointListWidget.Entry> {
    private final CHRCScreen screen;
    private final int listWidth;

    public WaypointListWidget(Minecraft client, CHRCScreen screen, int width, int height, int y, int itemHeight) {
        super(client, width, height, y, itemHeight);
        this.screen = screen;
        this.listWidth = width;
        for (ChrcWaypoint waypoint : RouteManager.get().waypoints) addEntry(new Entry(waypoint));
    }

    @Override
    public int getRowWidth() {
        return Math.min(540, Math.max(300, listWidth - 32));
    }

    public final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
        private final ChrcWaypoint waypoint;
        private final EditBox name;
        private final EditBox x;
        private final EditBox y;
        private final EditBox z;
        private final Button delete;
        private final List<AbstractWidget> widgets;

        private Entry(ChrcWaypoint waypoint) {
            this.waypoint = waypoint;

            name = new EditBox(Minecraft.getInstance().font, 150, 20, Component.literal("Name"));
            name.setValue(waypoint.name);
            name.setResponder(value -> {
                if (!value.isBlank() && !value.equals(waypoint.name)) {
                    waypoint.name = value;
                    RouteManager.save();
                }
            });

            x = coordinateBox("X", waypoint.x, value -> waypoint.x = value);
            y = coordinateBox("Y", waypoint.y, value -> waypoint.y = value);
            z = coordinateBox("Z", waypoint.z, value -> waypoint.z = value);

            delete = Button.builder(Component.literal("Delete"), button -> {
                RouteManager.removeWaypoint(waypoint);
                ScanManager.routeChanged();
                screen.refresh();
            }).size(58, 20).build();

            widgets = List.of(name, x, y, z, delete);
        }

        private EditBox coordinateBox(String label, int initial, Consumer<Integer> updater) {
            EditBox box = new EditBox(Minecraft.getInstance().font, 52, 20, Component.literal(label));
            box.setValue(Integer.toString(initial));
            // Vanilla EditBox in Minecraft 26.1.2 has no setFilter().
            // Keep the last valid integer locally and reject invalid edits in
            // the responder. A lone "-" is allowed while typing negatives.
            final int[] lastValid = {initial};
            final boolean[] restoring = {false};
            box.setResponder(value -> {
                if (restoring[0] || value.isEmpty() || value.equals("-")) return;

                if (!isPotentialInteger(value)) {
                    restoring[0] = true;
                    String validValue = Integer.toString(lastValid[0]);
                    box.setValue(validValue);
                    box.setCursorPosition(validValue.length());
                    restoring[0] = false;
                    return;
                }

                try {
                    int parsed = Integer.parseInt(value);
                    lastValid[0] = parsed;
                    updater.accept(parsed);
                    RouteManager.save();
                    ScanManager.routeChanged();
                } catch (NumberFormatException ignored) {
                    restoring[0] = true;
                    String validValue = Integer.toString(lastValid[0]);
                    box.setValue(validValue);
                    box.setCursorPosition(validValue.length());
                    restoring[0] = false;
                }
            });
            return box;
        }

        private static boolean isPotentialInteger(String value) {
            if (value.isEmpty() || value.equals("-")) return true;
            int start = value.charAt(0) == '-' ? 1 : 0;
            if (start == value.length()) return true;
            for (int i = start; i < value.length(); i++) {
                if (!Character.isDigit(value.charAt(i))) return false;
            }
            try {
                Integer.parseInt(value);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int left = getContentX();
            int centerY = getContentY() + (getHeight() - 20) / 2;

            graphics.text(Minecraft.getInstance().font, Component.literal("#" + (RouteManager.get().waypoints.indexOf(waypoint) + 1)), left, centerY + 6, CommonColors.GRAY);

            int px = left + 28;
            name.setPosition(px, centerY);
            px += 156;
            graphics.text(Minecraft.getInstance().font, Component.literal("X"), px, centerY + 6, CommonColors.LIGHT_GRAY);
            px += 10;
            x.setPosition(px, centerY);
            px += 58;
            graphics.text(Minecraft.getInstance().font, Component.literal("Y"), px, centerY + 6, CommonColors.LIGHT_GRAY);
            px += 10;
            y.setPosition(px, centerY);
            px += 58;
            graphics.text(Minecraft.getInstance().font, Component.literal("Z"), px, centerY + 6, CommonColors.LIGHT_GRAY);
            px += 10;
            z.setPosition(px, centerY);
            delete.setPosition(getContentRight() - delete.getWidth(), centerY);

            for (AbstractWidget widget : widgets) widget.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }
}
