package dev.chrc.render;

import dev.chrc.route.ChrcWaypoint;
import dev.chrc.route.RouteManager;
import dev.chrc.scanner.ScanManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * World waypoint renderer used by CHRC.
 *
 * <p>Normal route checking shows only the first waypoint that still needs to be
 * scanned. A separate keybind can toggle an "all waypoints" view that renders
 * every waypoint in the loaded route at once, even when a route scan is not
 * running.</p>
 *
 * <p>CHRC intentionally reuses Skyblocker's own NamedWaypoint renderer at
 * runtime. The final constructor argument is {@code true}, which enables its
 * through-walls rendering. This gives the same useful X-ray-style behaviour as
 * SkyHanni waypoints (marker/text remain visible behind terrain) without adding
 * a compile-time dependency on SkyHanni or duplicating its renderer.</p>
 */
public final class CurrentTargetWaypointRenderer {
    private static final float[] COLOR = new float[]{0.15F, 1.0F, 0.35F};

    private static RenderTarget currentTarget;
    private static List<RenderTarget> allTargets = List.of();
    private static boolean allWaypointsVisible;

    private static boolean skyblockerRendererAvailable;
    private static String initError = "not initialised";

    private static Class<?> primitiveCollectorClass;
    private static Constructor<?> namedWaypointStringConstructor;
    private static Constructor<?> namedWaypointComponentConstructor;
    private static Method namedWaypointExtractRendering;

    private CurrentTargetWaypointRenderer() {}

    /**
     * Register with Skyblocker's level render extraction event during CHRC
     * client initialisation. This constructs real Skyblocker NamedWaypoint
     * objects and lets Skyblocker perform the actual world rendering.
     */
    public static void init() {
        try {
            Class<?> callbackClass = Class.forName(
                    "de.hysky.skyblocker.utils.render.LevelRenderExtractionCallback"
            );
            primitiveCollectorClass = Class.forName(
                    "de.hysky.skyblocker.utils.render.primitive.PrimitiveCollector"
            );
            Class<?> namedWaypointClass = Class.forName(
                    "de.hysky.skyblocker.utils.waypoint.NamedWaypoint"
            );

            try {
                namedWaypointStringConstructor = namedWaypointClass.getConstructor(
                        BlockPos.class,
                        String.class,
                        float[].class,
                        boolean.class
                );
            } catch (NoSuchMethodException ignored) {
                namedWaypointStringConstructor = null;
            }

            try {
                namedWaypointComponentConstructor = namedWaypointClass.getConstructor(
                        BlockPos.class,
                        Component.class,
                        float[].class,
                        boolean.class
                );
            } catch (NoSuchMethodException ignored) {
                namedWaypointComponentConstructor = null;
            }

            if (namedWaypointStringConstructor == null && namedWaypointComponentConstructor == null) {
                throw new NoSuchMethodException("No compatible Skyblocker NamedWaypoint constructor found");
            }

            namedWaypointExtractRendering = namedWaypointClass.getMethod(
                    "extractRendering",
                    primitiveCollectorClass
            );

            Object event = callbackClass.getField("EVENT").get(null);
            Object callback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "CHRC Skyblocker waypoint render callback";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                                default -> null;
                            };
                        }

                        if ("onExtract".equals(method.getName()) && args != null && args.length >= 1) {
                            render(args[0]);
                        }
                        return null;
                    }
            );

            // Resolve register() from Fabric's public Event API. Invoking the
            // package-private ArrayBackedEvent implementation directly can fail
            // with IllegalAccessException on Java 25.
            Class<?> fabricEventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            Method register = null;
            for (Method method : fabricEventClass.getMethods()) {
                if (method.getName().equals("register") && method.getParameterCount() == 1) {
                    register = method;
                    break;
                }
            }
            if (register == null) {
                throw new NoSuchMethodException("Fabric Event#register(listener) not found");
            }

            register.invoke(event, callback);
            skyblockerRendererAvailable = true;
            initError = "";
            System.out.println("[CHRC] Skyblocker waypoint renderer hooked successfully.");
        } catch (Throwable throwable) {
            skyblockerRendererAvailable = false;
            initError = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
            System.err.println("[CHRC] Failed to hook Skyblocker waypoint renderer: " + initError);
            throwable.printStackTrace();
        }
    }

    /** Called immediately after a route scan starts. */
    public static void onScanStarted(Minecraft client) {
        syncTarget(client);
        syncAllTargets(client);
    }

    /**
     * Clear only the current scan marker. The independent all-waypoints toggle
     * intentionally remains unchanged when a route scan is stopped.
     */
    public static void clear() {
        currentTarget = null;
    }

    /** Called once per client tick. */
    public static void tick(Minecraft client) {
        syncTarget(client);
        syncAllTargets(client);
    }

    public static boolean areAllWaypointsVisible() {
        return allWaypointsVisible;
    }

    /**
     * Enable/disable the independent "show all waypoints" overlay.
     * This does not start or stop CHRC's route scanner.
     */
    public static void setAllWaypointsVisible(boolean visible, Minecraft client) {
        allWaypointsVisible = visible;
        if (!visible) {
            allTargets = List.of();
            return;
        }
        syncAllTargets(client);
    }

    private static void syncTarget(Minecraft client) {
        if (!ScanManager.isRunning() || client.level == null || client.player == null) {
            currentTarget = null;
            return;
        }

        ChrcWaypoint pending = ScanManager.currentPendingWaypoint();
        if (pending == null) {
            currentTarget = null;
            return;
        }

        int routeIndex = RouteManager.get().waypoints.indexOf(pending) + 1;
        if (currentTarget != null && currentTarget.matches(pending, routeIndex)) {
            return;
        }

        currentTarget = createRenderTarget(pending, routeIndex);
    }

    /**
     * Rebuild all-waypoint render objects only when the route itself changes.
     * This keeps a 40-100 waypoint route cheap during normal client ticks.
     */
    private static void syncAllTargets(Minecraft client) {
        if (!allWaypointsVisible || client.level == null || client.player == null) {
            allTargets = List.of();
            return;
        }

        List<ChrcWaypoint> waypoints = RouteManager.get().waypoints;
        if (matchesRoute(allTargets, waypoints)) return;

        List<RenderTarget> rebuilt = new ArrayList<>(waypoints.size());
        for (int i = 0; i < waypoints.size(); i++) {
            rebuilt.add(createRenderTarget(waypoints.get(i), i + 1));
        }
        allTargets = List.copyOf(rebuilt);
    }

    private static boolean matchesRoute(List<RenderTarget> targets, List<ChrcWaypoint> waypoints) {
        if (targets.size() != waypoints.size()) return false;
        for (int i = 0; i < waypoints.size(); i++) {
            if (!targets.get(i).matches(waypoints.get(i), i + 1)) return false;
        }
        return true;
    }

    private static RenderTarget createRenderTarget(ChrcWaypoint waypoint, int routeIndex) {
        return new RenderTarget(
                waypoint.id,
                routeIndex,
                waypoint.name,
                waypoint.x,
                waypoint.y,
                waypoint.z,
                createSkyblockerWaypoint(waypoint, routeIndex)
        );
    }

    private static Object createSkyblockerWaypoint(ChrcWaypoint waypoint, int routeIndex) {
        if (!skyblockerRendererAvailable) return null;

        try {
            BlockPos pos = new BlockPos(waypoint.x, waypoint.y, waypoint.z);
            String label = Integer.toString(routeIndex);

            // throughWalls=true: marker and label remain visible behind blocks.
            if (namedWaypointStringConstructor != null) {
                return namedWaypointStringConstructor.newInstance(pos, label, COLOR.clone(), true);
            }
            if (namedWaypointComponentConstructor != null) {
                return namedWaypointComponentConstructor.newInstance(
                        pos,
                        Component.literal(label),
                        COLOR.clone(),
                        true
                );
            }
        } catch (Throwable throwable) {
            System.err.println("[CHRC] Could not create Skyblocker waypoint #" + routeIndex + ": " + throwable);
        }
        return null;
    }

    private static void render(Object collector) {
        if (!skyblockerRendererAvailable) return;
        if (collector == null || !primitiveCollectorClass.isInstance(collector)) return;

        // When Show All Waypoints is enabled, render the route exactly once and
        // suppress the separate current-target marker to avoid a duplicate at
        // the active waypoint.
        if (allWaypointsVisible) {
            for (RenderTarget target : allTargets) {
                renderTarget(target, collector);
            }
            return;
        }

        if (!ScanManager.isRunning()) return;
        renderTarget(currentTarget, collector);
    }

    private static void renderTarget(RenderTarget target, Object collector) {
        if (target == null || target.skyblockerWaypoint == null) return;
        try {
            namedWaypointExtractRendering.invoke(target.skyblockerWaypoint, collector);
        } catch (Throwable throwable) {
            // Keep an optional visual overlay from crashing the client.
            System.err.println("[CHRC] Skyblocker waypoint render failed: " + throwable);
        }
    }

    public static boolean isSkyblockerRendererAvailable() {
        return skyblockerRendererAvailable;
    }

    public static String getInitError() {
        return initError;
    }

    public static int currentTargetIndex() {
        return currentTarget == null ? -1 : currentTarget.routeIndex;
    }

    private record RenderTarget(
            String id,
            int routeIndex,
            String name,
            int x,
            int y,
            int z,
            Object skyblockerWaypoint
    ) {
        private boolean matches(ChrcWaypoint waypoint, int index) {
            return id.equals(waypoint.id)
                    && routeIndex == index
                    && x == waypoint.x
                    && y == waypoint.y
                    && z == waypoint.z
                    && name.equals(waypoint.name);
        }
    }
}
