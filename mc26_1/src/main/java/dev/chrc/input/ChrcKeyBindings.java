package dev.chrc.input;

import dev.chrc.config.ConfigManager;
import dev.chrc.route.RouteManager;
import dev.chrc.render.CurrentTargetWaypointRenderer;
import dev.chrc.scanner.ScanManager;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Registers CHRC in Minecraft's normal Controls / Key Binds screen. */
public final class ChrcKeyBindings {
    private static KeyMapping toggle;
    private static KeyMapping freecamToggle;
    private static KeyMapping waypointsToggle;
    private static boolean initialized;

    private ChrcKeyBindings() {}

    /**
     * Must be called from CHRCClient#onInitializeClient().
     *
     * Minecraft 26.1.x/Fabric requires custom key mappings to be registered before
     * Minecraft.options (GameOptions) finishes initialising. Do not move this
     * registration into a tick callback or another lazily loaded code path.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("chrc", "main")
        );

        // GLFW_KEY_UNKNOWN is displayed by Minecraft as "Not Bound".
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chrc.toggle",
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));

        // Same default as Aether. Users can rebind this normally in Minecraft Controls.
        freecamToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chrc.freecam",
                GLFW.GLFW_KEY_F6,
                category
        ));

        // Off by default. Users can bind this in Minecraft Controls -> Key Binds -> CHRC.
        waypointsToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.chrc.waypoints",
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));
    }

    public static void tick(Minecraft client) {
        // Defensive guard: init() should always have run from the client initializer.
        if (toggle == null || freecamToggle == null || waypointsToggle == null) return;


        while (waypointsToggle.consumeClick()) {
            if (CurrentTargetWaypointRenderer.areAllWaypointsVisible()) {
                CurrentTargetWaypointRenderer.setAllWaypointsVisible(false, client);
                send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("All waypoints hidden.").withStyle(ChatFormatting.YELLOW)));
                continue;
            }

            if (RouteManager.get().waypoints.isEmpty()) {
                send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Cannot show waypoints: no route is loaded.").withStyle(ChatFormatting.RED)));
                continue;
            }

            if (!CurrentTargetWaypointRenderer.isSkyblockerRendererAvailable()) {
                send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Waypoint renderer unavailable: " + CurrentTargetWaypointRenderer.getInitError())
                                .withStyle(ChatFormatting.YELLOW)));
                continue;
            }

            CurrentTargetWaypointRenderer.setAllWaypointsVisible(true, client);
            send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("Showing all " + RouteManager.get().waypoints.size() + " waypoints through blocks.")
                            .withStyle(ChatFormatting.GREEN)));
        }

        while (toggle.consumeClick()) {
            if (ScanManager.isRunning()) {
                ScanManager.stop();
                CurrentTargetWaypointRenderer.clear();
                send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Route check stopped.").withStyle(ChatFormatting.YELLOW)));
                continue;
            }

            if (RouteManager.get().waypoints.isEmpty()) {
                send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Cannot start: no waypoints loaded.").withStyle(ChatFormatting.RED)));
                continue;
            }

            if (!ConfigManager.get().enabled) {
                ConfigManager.get().enabled = true;
                ConfigManager.save();
            }

            if (ScanManager.start()) {
                CurrentTargetWaypointRenderer.onScanStarted(client);
                if (!CurrentTargetWaypointRenderer.isSkyblockerRendererAvailable()) {
                    send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("Waypoint renderer unavailable: " + CurrentTargetWaypointRenderer.getInitError())
                                    .withStyle(ChatFormatting.YELLOW)));
                }
                send(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Route check started — " + RouteManager.get().waypoints.size() + " waypoints.")
                                .withStyle(ChatFormatting.GREEN)));
            }
        }
    }

    public static KeyMapping getFreecamToggle() {
        return freecamToggle;
    }

    public static KeyMapping getWaypointsToggle() {
        return waypointsToggle;
    }

    private static void send(Minecraft client, Component message) {
        if (client.gui != null) client.gui.getChat().addClientSystemMessage(message);
    }
}
