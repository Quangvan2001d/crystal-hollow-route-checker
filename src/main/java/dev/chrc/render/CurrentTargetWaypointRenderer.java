package dev.chrc.render;

import dev.chrc.route.ChrcWaypoint;
import dev.chrc.route.RouteManager;
import dev.chrc.scanner.ScanManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Displays exactly one world waypoint: the first route entry that still needs
 * to be scanned. CHRC intentionally reuses Skyblocker's own NamedWaypoint
 * renderer at runtime, so the marker has the same through-walls behaviour and
 * visual style as a normal Skyblocker waypoint without making Skyblocker a
 * compile-time dependency.
 *
 * The rendered waypoint object is created only when the pending route entry
 * changes. Client ticks merely compare the current pending entry with the
 * existing target; they do not recreate the waypoint every tick.
 */
public final class CurrentTargetWaypointRenderer {
    private static final float[] COLOR = new float[]{0.15F, 1.0F, 0.35F};

    private static RenderTarget currentTarget;

    private static boolean skyblockerRendererAvailable;
    private static String initError = "not initialised";

    private static Class<?> primitiveCollectorClass;
    private static Constructor<?> namedWaypointStringConstructor;
    private static Constructor<?> namedWaypointComponentConstructor;
    private static Method namedWaypointExtractRendering;

    private CurrentTargetWaypointRenderer() {}

    /**
     * Register with Skyblocker's level render extraction event during CHRC
     * client initialisation. Unlike the old implementation, this does not try
     * to duplicate PrimitiveCollector method signatures. Instead it constructs
     * a real Skyblocker NamedWaypoint and lets Skyblocker render it itself.
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

            // Skyblocker 6.x exposes this constructor. Keep a Component fallback
            // as well so minor constructor changes do not disable the marker.
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

            // IMPORTANT: do not reflect register() from event.getClass().
            // Fabric's concrete event implementation is ArrayBackedEvent, which is
            // package-private. Invoking a Method whose declaring class is that
            // implementation throws IllegalAccessException on Java 25 even though
            // register itself is public. Resolve register() from Fabric's public
            // Event API class instead and invoke it on the concrete event instance.
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

    /**
     * Called immediately after a successful /chrc start or keybind start, so
     * waypoint #1 exists before the first scanner tick can complete it.
     */
    public static void onScanStarted(Minecraft client) {
        syncTarget(client);
    }

    /** Clear the visible marker immediately on a manual stop. */
    public static void clear() {
        currentTarget = null;
    }

    /**
     * Called once per client tick. It only swaps the marker when the first
     * unscanned route entry changes.
     */
    public static void tick(Minecraft client) {
        syncTarget(client);
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

        Object skyblockerWaypoint = createSkyblockerWaypoint(pending, routeIndex);
        currentTarget = new RenderTarget(
                pending.id,
                routeIndex,
                pending.name,
                pending.x,
                pending.y,
                pending.z,
                skyblockerWaypoint
        );
    }

    private static Object createSkyblockerWaypoint(ChrcWaypoint waypoint, int routeIndex) {
        if (!skyblockerRendererAvailable) return null;

        try {
            BlockPos pos = new BlockPos(waypoint.x, waypoint.y, waypoint.z);
            String label = "CHRC #" + routeIndex + " - " + waypoint.name;

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
        RenderTarget target = currentTarget;
        if (!skyblockerRendererAvailable || target == null || !ScanManager.isRunning()) return;
        if (target.skyblockerWaypoint == null) return;
        if (collector == null || !primitiveCollectorClass.isInstance(collector)) return;

        try {
            // This is Skyblocker's own Waypoint#extractRendering implementation.
            // NamedWaypoint defaults to throughWalls=true, so the target remains
            // visible even when blocks are between the player and the waypoint.
            namedWaypointExtractRendering.invoke(target.skyblockerWaypoint, collector);
        } catch (Throwable throwable) {
            // Do not crash the client because of an optional visual overlay.
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
