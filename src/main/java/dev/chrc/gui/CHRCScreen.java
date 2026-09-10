package dev.chrc.gui;

import dev.chrc.config.ConfigManager;
import dev.chrc.route.ChrcRoute;
import dev.chrc.route.RouteManager;
import dev.chrc.route.SkyblockerWaypointImporter;
import dev.chrc.scanner.GemstoneType;
import dev.chrc.scanner.ScanManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;

public final class CHRCScreen extends Screen {
    private static final int PANEL_MAX_WIDTH = 640;
    private static final int HEADER_HEIGHT = 64;
    private static final int FOOTER_HEIGHT = 44;

    private final Screen parent;
    private Tab activeTab = Tab.GENERAL;
    private String statusMessage = "";
    private int statusColor = CommonColors.GRAY;

    public CHRCScreen(Screen parent) {
        super(Component.literal("Crystal Hollow Route Checker"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        int panelWidth = Math.min(PANEL_MAX_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.fill(left, 10, left + panelWidth, height - 10, 0xB8000000);
            graphics.fill(left, 10, left + panelWidth, 11, 0xFF555555);
            graphics.fill(left, height - 11, left + panelWidth, height - 10, 0xFF555555);
            graphics.fill(left, 10, left + 1, height - 10, 0xFF555555);
            graphics.fill(left + panelWidth - 1, 10, left + panelWidth, height - 10, 0xFF555555);
        });

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.centeredText(font, Component.literal("CHRC").withStyle(ChatFormatting.BOLD), width / 2, 18, CommonColors.WHITE);
            graphics.centeredText(font, Component.literal("Crystal Hollow Route Checker").withStyle(ChatFormatting.GRAY), width / 2, 31, CommonColors.LIGHT_GRAY);
        });

        int tabWidth = Math.max(72, Math.min(120, (panelWidth - 24) / Tab.values().length));
        int tabsTotal = tabWidth * Tab.values().length;
        int tabX = width / 2 - tabsTotal / 2;
        for (Tab tab : Tab.values()) {
            Component label = Component.literal((tab == activeTab ? "• " : "") + tab.label);
            addRenderableWidget(Button.builder(label, button -> {
                activeTab = tab;
                statusMessage = "";
                rebuildWidgets();
            }).pos(tabX, 43).size(tabWidth, 20).build());
            tabX += tabWidth;
        }

        switch (activeTab) {
            case GENERAL -> initGeneral(left, panelWidth);
            case GEMSTONES -> initGemstones(left, panelWidth);
            case ROUTE -> initRoute(left, panelWidth);
            case HUD -> initHud(left, panelWidth);
            case RESULTS -> initResults(left, panelWidth);
        }

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .pos(left + 8, height - 36).size(100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset Settings"), button -> {
                    ConfigManager.reset();
                    ScanManager.reset();
                    statusMessage = "Settings reset. Ruby is selected by default.";
                    statusColor = CommonColors.LIGHT_GRAY;
                    rebuildWidgets();
                })
                .pos(left + panelWidth - 108, height - 36).size(100, 20).build());

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            if (!statusMessage.isEmpty()) {
                graphics.centeredText(font, Component.literal(statusMessage), width / 2, height - 32, statusColor);
            }
        });
    }

    private void initGeneral(int left, int panelWidth) {
        int contentLeft = left + 22;
        int top = HEADER_HEIGHT + 18;

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.text(font, Component.literal("General").withStyle(ChatFormatting.BOLD), contentLeft, top, CommonColors.WHITE);

            int radius = ConfigManager.get().scanRadius;
            long side = radius * 2L + 1L;
            long volume = side * side * side;
            graphics.text(font, Component.literal("Gemstone scan radius (cube centered at waypoint Y + 2)"), contentLeft, top + 42, CommonColors.LIGHT_GRAY);
            graphics.text(font, Component.literal("Gemstone area: " + side + " × " + side + " × " + side + " = " + volume + " blocks"), contentLeft + 82, top + 58, CommonColors.GRAY);

            int structureRadius = ConfigManager.get().structureScanRadius;
            long structureSide = structureRadius * 2L + 1L;
            long structureVolume = structureSide * structureSide * structureSide;
            graphics.text(font, Component.literal("Structure scan radius (1–10 blocks)"), contentLeft, top + 88, CommonColors.LIGHT_GRAY);
            graphics.text(font, Component.literal("Structure area: " + structureSide + " × " + structureSide + " × " + structureSide + " = " + structureVolume + " blocks"), contentLeft + 82, top + 104, CommonColors.GRAY);

            graphics.text(font, Component.literal("Strict order: #1 → #2 → #3. Earlier gemstone blocks are ignored later."), contentLeft, top + 136, CommonColors.GRAY);
        });

        addRenderableWidget(Checkbox.builder(Component.literal("Enable CHRC"), font)
                .selected(ConfigManager.get().enabled)
                .onValueChange((checkbox, checked) -> {
                    ConfigManager.get().enabled = checked;
                    ConfigManager.save();
                })
                .pos(contentLeft, top + 18)
                .build());

        EditBox radius = new EditBox(font, 70, 20, Component.literal("Gemstone Scan Radius"));
        radius.setPosition(contentLeft, top + 54);
        radius.setValue(Integer.toString(ConfigManager.get().scanRadius));
        final boolean[] restoringRadius = {false};
        radius.setResponder(value -> {
            if (restoringRadius[0] || value.isEmpty()) return;

            if (!isIntegerInRange(value, 0, 32)) {
                restoringRadius[0] = true;
                String validValue = Integer.toString(ConfigManager.get().scanRadius);
                radius.setValue(validValue);
                radius.setCursorPosition(validValue.length());
                restoringRadius[0] = false;
                return;
            }

            int parsed = Integer.parseInt(value);
            if (parsed != ConfigManager.get().scanRadius) {
                ConfigManager.get().scanRadius = parsed;
                ConfigManager.save();
                ScanManager.reset();
            }
        });
        addRenderableWidget(radius);

        EditBox structureRadius = new EditBox(font, 70, 20, Component.literal("Structure Scan Radius"));
        structureRadius.setPosition(contentLeft, top + 100);
        structureRadius.setValue(Integer.toString(ConfigManager.get().structureScanRadius));
        final boolean[] restoringStructureRadius = {false};
        structureRadius.setResponder(value -> {
            if (restoringStructureRadius[0] || value.isEmpty()) return;

            if (!isIntegerInRange(value, 1, 10)) {
                restoringStructureRadius[0] = true;
                String validValue = Integer.toString(ConfigManager.get().structureScanRadius);
                structureRadius.setValue(validValue);
                structureRadius.setCursorPosition(validValue.length());
                restoringStructureRadius[0] = false;
                return;
            }

            int parsed = Integer.parseInt(value);
            if (parsed != ConfigManager.get().structureScanRadius) {
                ConfigManager.get().structureScanRadius = parsed;
                ConfigManager.save();
                ScanManager.reset();
            }
        });
        addRenderableWidget(structureRadius);
    }

    private void initGemstones(int left, int panelWidth) {
        int contentLeft = left + 22;
        int top = HEADER_HEIGHT + 18;

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.text(font, Component.literal("Gemstones").withStyle(ChatFormatting.BOLD), contentLeft, top, CommonColors.WHITE);
            graphics.text(font, Component.literal("TRUE requires at least 1 block of every selected gemstone."), contentLeft, top + 18, CommonColors.GRAY);
            graphics.text(font, Component.literal("At least one gemstone must always stay selected."), contentLeft, top + 32, CommonColors.GRAY);
        });

        int y = top + 54;
        for (GemstoneType type : GemstoneType.values()) {
            boolean selected = ConfigManager.get().isSelected(type);
            Checkbox checkbox = Checkbox.builder(Component.literal(type.displayName()).withStyle(type.color()), font)
                    .selected(selected)
                    .onValueChange((box, checked) -> {
                        boolean wasSelected = ConfigManager.get().isSelected(type);
                        ConfigManager.get().setSelected(type, checked);
                        ConfigManager.save();
                        ScanManager.reset();
                        if (wasSelected && !checked && ConfigManager.get().isSelected(type)) {
                            statusMessage = "At least one gemstone must stay selected.";
                            statusColor = 0xFFFF5555;
                            minecraft.execute(this::rebuildWidgets);
                        }
                    })
                    .pos(contentLeft, y)
                    .build();
            addRenderableWidget(checkbox);
            y += 24;
        }
    }

    private void initRoute(int left, int panelWidth) {
        int contentLeft = left + 18;
        int top = HEADER_HEIGHT + 14;
        ChrcRoute route = RouteManager.get();

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.text(font, Component.literal("Route").withStyle(ChatFormatting.BOLD), contentLeft, top, CommonColors.WHITE);
            graphics.text(font, Component.literal("Waypoints: " + route.waypoints.size()), contentLeft, top + 26, CommonColors.GRAY);
        });

        EditBox routeName = new EditBox(font, Math.max(80, Math.min(240, panelWidth - 260)), 20, Component.literal("Route Name"));
        routeName.setPosition(contentLeft + 55, top - 5);
        routeName.setValue(route.name);
        routeName.setResponder(value -> {
            if (!value.isBlank()) {
                route.name = value;
                RouteManager.save();
            }
        });
        addRenderableWidget(routeName);

        int buttonY = top + 18;
        addRenderableWidget(Button.builder(Component.literal("Import Waypoints"), button -> importWaypoints())
                .pos(left + panelWidth - 330, buttonY).size(120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+ Add Waypoint"), button -> {
                    RouteManager.addWaypoint();
                    ScanManager.routeChanged();
                    refresh();
                })
                .pos(left + panelWidth - 205, buttonY).size(105, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Start Check"), button -> {
                    ConfigManager.save();
                    RouteManager.save();
                    if (ScanManager.start()) {
                        statusMessage = "Route check started.";
                        statusColor = 0xFF55FFFF;
                    } else {
                        statusMessage = "Add or import at least one waypoint first.";
                        statusColor = 0xFFFF5555;
                    }
                })
                .pos(left + panelWidth - 95, buttonY).size(82, 20).build());

        int listY = top + 48;
        int listHeight = Math.max(40, height - listY - FOOTER_HEIGHT - 14);
        addRenderableWidget(new WaypointListWidget(minecraft, this, panelWidth - 16, listHeight, listY, 28));
    }


    private void initHud(int left, int panelWidth) {
        int contentLeft = left + 22;
        int top = HEADER_HEIGHT + 18;

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.text(font, Component.literal("Unscanned Waypoints HUD").withStyle(ChatFormatting.BOLD), contentLeft, top, CommonColors.WHITE);
            graphics.text(font, Component.literal("Shown at the top of the screen while a route check is running."), contentLeft, top + 18, CommonColors.GRAY);
            graphics.text(font, Component.literal("It lists every waypoint that has not been scanned yet, with its load status."), contentLeft, top + 32, CommonColors.GRAY);
            graphics.text(font, Component.literal("X = -1 centers the table horizontally."), contentLeft, top + 154, CommonColors.GRAY);
        });

        addRenderableWidget(Checkbox.builder(Component.literal("Show unscanned-waypoint table"), font)
                .selected(ConfigManager.get().unloadedWaypointsHudEnabled)
                .onValueChange((checkbox, checked) -> {
                    ConfigManager.get().unloadedWaypointsHudEnabled = checked;
                    ConfigManager.save();
                })
                .pos(contentLeft, top + 52)
                .build());

        int labelX = contentLeft;
        int boxX = contentLeft + 150;

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            graphics.text(font, Component.literal("X position"), labelX, top + 84, CommonColors.LIGHT_GRAY);
            graphics.text(font, Component.literal("Y position"), labelX, top + 108, CommonColors.LIGHT_GRAY);
            graphics.text(font, Component.literal("Scale (%)"), labelX, top + 132, CommonColors.LIGHT_GRAY);
            graphics.text(font, Component.literal("Max visible rows"), labelX + 250, top + 84, CommonColors.LIGHT_GRAY);
        });

        EditBox hudX = integerSettingBox("HUD X", ConfigManager.get().unloadedWaypointsHudX, -1, 10000, value -> ConfigManager.get().unloadedWaypointsHudX = value);
        hudX.setPosition(boxX, top + 78);
        addRenderableWidget(hudX);

        EditBox hudY = integerSettingBox("HUD Y", ConfigManager.get().unloadedWaypointsHudY, 0, 10000, value -> ConfigManager.get().unloadedWaypointsHudY = value);
        hudY.setPosition(boxX, top + 102);
        addRenderableWidget(hudY);

        EditBox scale = integerSettingBox("HUD Scale", ConfigManager.get().unloadedWaypointsHudScalePercent, 50, 200, value -> ConfigManager.get().unloadedWaypointsHudScalePercent = value);
        scale.setPosition(boxX, top + 126);
        addRenderableWidget(scale);

        EditBox rows = integerSettingBox("HUD Rows", ConfigManager.get().unloadedWaypointsHudMaxRows, 1, 30, value -> ConfigManager.get().unloadedWaypointsHudMaxRows = value);
        rows.setPosition(contentLeft + 370, top + 78);
        addRenderableWidget(rows);

        addRenderableWidget(Button.builder(Component.literal("Reset HUD"), button -> {
                    ConfigManager.get().unloadedWaypointsHudEnabled = true;
                    ConfigManager.get().unloadedWaypointsHudX = -1;
                    ConfigManager.get().unloadedWaypointsHudY = 8;
                    ConfigManager.get().unloadedWaypointsHudScalePercent = 100;
                    ConfigManager.get().unloadedWaypointsHudMaxRows = 8;
                    ConfigManager.save();
                    statusMessage = "Unscanned HUD reset to top-center defaults.";
                    statusColor = CommonColors.LIGHT_GRAY;
                    rebuildWidgets();
                })
                .pos(contentLeft + 370, top + 102).size(100, 20).build());
    }

    private EditBox integerSettingBox(String label, int initial, int min, int max, java.util.function.IntConsumer updater) {
        EditBox box = new EditBox(font, 72, 20, Component.literal(label));
        box.setValue(Integer.toString(initial));
        final int[] lastValid = {initial};
        final boolean[] restoring = {false};
        box.setResponder(value -> {
            if (restoring[0] || value.isEmpty() || value.equals("-")) return;
            if (!isSignedIntegerInRange(value, min, max)) {
                restoring[0] = true;
                String validValue = Integer.toString(lastValid[0]);
                box.setValue(validValue);
                box.setCursorPosition(validValue.length());
                restoring[0] = false;
                return;
            }
            int parsed = Integer.parseInt(value);
            lastValid[0] = parsed;
            updater.accept(parsed);
            ConfigManager.save();
        });
        return box;
    }

    private static boolean isSignedIntegerInRange(String value, int min, int max) {
        if (value == null || value.isEmpty() || value.equals("-")) return true;
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) return true;
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void initResults(int left, int panelWidth) {
        int contentLeft = left + 18;
        int top = HEADER_HEIGHT + 14;

        addRenderableOnly((graphics, mouseX, mouseY, delta) -> {
            String state = ScanManager.isRunning() ? "RUNNING" : "IDLE";
            graphics.text(font, Component.literal("Results").withStyle(ChatFormatting.BOLD), contentLeft, top, CommonColors.WHITE);
            graphics.text(font, Component.literal("Status: " + state + "  |  Checked: " + ScanManager.scannedCount() + "/" + RouteManager.get().waypoints.size()), contentLeft, top + 18, CommonColors.GRAY);

            Component summary = Component.literal("TRUE: " + ScanManager.trueCount()).withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("   FALSE: " + ScanManager.falseCount()).withStyle(ChatFormatting.RED));
            graphics.text(font, summary, contentLeft, top + 34, CommonColors.WHITE);
        });

        addRenderableWidget(Button.builder(Component.literal("Reset Results"), button -> {
                    ScanManager.reset();
                    statusMessage = "Results cleared.";
                    statusColor = CommonColors.LIGHT_GRAY;
                })
                .pos(left + panelWidth - 113, top + 8).size(95, 20).build());

        int listY = top + 56;
        int listHeight = Math.max(40, height - listY - FOOTER_HEIGHT - 14);
        addRenderableWidget(new ResultsListWidget(minecraft, panelWidth - 16, listHeight, listY, 24));
    }

    private void importWaypoints() {
        SkyblockerWaypointImporter.ImportResult result = SkyblockerWaypointImporter.fromClipboard(minecraft.keyboardHandler.getClipboard());
        if (!result.success()) {
            statusMessage = result.error();
            statusColor = 0xFFFF5555;
            return;
        }

        RouteManager.replace(result.route());
        ScanManager.routeChanged();
        int count = result.route().waypoints.size();
        statusMessage = "Imported " + count + " Skyblocker waypoint" + (count == 1 ? "" : "s") + ".";
        statusColor = 0xFF55FF55;
        refresh();
    }

    private static boolean isIntegerInRange(String value, int min, int max) {
        if (value.isEmpty()) return true;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void refresh() {
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        ConfigManager.save();
        RouteManager.save();
        minecraft.setScreen(parent);
    }

    private enum Tab {
        GENERAL("General"),
        GEMSTONES("Gemstones"),
        ROUTE("Route"),
        HUD("HUD"),
        RESULTS("Results");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }
}
