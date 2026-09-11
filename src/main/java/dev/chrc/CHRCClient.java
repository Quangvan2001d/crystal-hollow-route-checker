package dev.chrc;

import dev.chrc.config.ConfigManager;
import dev.chrc.gui.CHRCScreen;
import dev.chrc.freecam.FreecamManager;
import dev.chrc.hud.UnloadedWaypointsHud;
import dev.chrc.input.ChrcKeyBindings;
import dev.chrc.route.RouteManager;
import dev.chrc.render.CurrentTargetWaypointRenderer;
import dev.chrc.scanner.ScanManager;
import dev.chrc.world.LoadedChunkTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class CHRCClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register key mappings eagerly. Fabric 26.1.x rejects registrations after GameOptions is initialised.
        ChrcKeyBindings.init();
        FreecamManager.init();

        ConfigManager.load();
        RouteManager.load();
        LoadedChunkTracker.init();
        UnloadedWaypointsHud.init();
        CurrentTargetWaypointRenderer.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("chrc")
                        .executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> client.setScreen(new CHRCScreen(client.screen)));
                            return 1;
                        })
                        .then(ClientCommands.literal("start").executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            if (RouteManager.get().waypoints.isEmpty()) {
                                sendClientMessage(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                                        .append(Component.literal("Cannot start: no waypoints loaded.").withStyle(ChatFormatting.RED)));
                                return 0;
                            }

                            // /chrc start is an explicit request to run CHRC, so make sure
                            // the general enable toggle cannot silently prevent the scan.
                            if (!ConfigManager.get().enabled) {
                                ConfigManager.get().enabled = true;
                                ConfigManager.save();
                            }

                            ScanManager.start();
                            CurrentTargetWaypointRenderer.onScanStarted(client);
                            if (!CurrentTargetWaypointRenderer.isSkyblockerRendererAvailable()) {
                                sendClientMessage(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                                        .append(Component.literal("Waypoint renderer unavailable: " + CurrentTargetWaypointRenderer.getInitError())
                                                .withStyle(ChatFormatting.YELLOW)));
                            }
                            sendClientMessage(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal("Route check started — " + RouteManager.get().waypoints.size() + " waypoints.")
                                            .withStyle(ChatFormatting.GREEN)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            if (!ScanManager.isRunning()) {
                                sendClientMessage(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                                        .append(Component.literal("Route check is not running.").withStyle(ChatFormatting.GRAY)));
                                return 0;
                            }

                            ScanManager.stop();
                            CurrentTargetWaypointRenderer.clear();
                            sendClientMessage(client, Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                                    .append(Component.literal("Route check stopped.").withStyle(ChatFormatting.YELLOW)));
                            return 1;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(ChrcKeyBindings::tick);
        ClientTickEvents.END_CLIENT_TICK.register(FreecamManager::tick);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Sync the visible target before the scanner can complete it in this tick.
            CurrentTargetWaypointRenderer.tick(client);
            ScanManager.tick(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            FreecamManager.disableSilently(client);
            ConfigManager.save();
            RouteManager.save();
        });
    }
    private static void sendClientMessage(Minecraft client, Component message) {
        if (client.gui != null) client.gui.getChat().addClientSystemMessage(message);
    }
}

