package dev.chrc.automation;

import dev.chrc.config.ConfigManager;
import dev.chrc.hypixel.TabAreaDetector;
import dev.chrc.mixin.AccessorKeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/**
 * Goblin Knockback — PostMessage-only implementation.
 *
 * When a Crystal Hollows mob occupies the same block as the local player:
 *   1. Pause the Polinex Gemstone Macro by sending the macro toggle key via PostMessage
 *   2. Send two left-click attack bursts via PostMessage (WM_LBUTTONDOWN/WM_LBUTTONUP)
 *   3. Recheck — if the mob is gone, resume the macro via PostMessage
 *
 * ALL input (macro toggle key AND attack clicks) is delivered exclusively through
 * Win32 PostMessage to Minecraft's own HWND. No java.awt.Robot, no keybd_event,
 * no KeyMapping.click/set, no SendInput — only PostMessage.
 */
public final class GoblinKnockbackManager {
    private static final int CHECK_INTERVAL_TICKS = 5;
    private static final int CLICK_DELAY_MIN_MS = 180;
    private static final int CLICK_DELAY_MAX_MS = 220;
    private static final int NATIVE_KEY_HOLD_MS = 55;
    private static final int MOUSE_CLICK_HOLD_MS = 50;
    private static final int TOGGLE_CONFIRM_TIMEOUT_TICKS = 20;
    private static final int MAX_TOGGLE_ATTEMPTS = 3;

    // Win32 message constants
    private static final int WM_KEYDOWN    = 0x0100;
    private static final int WM_KEYUP      = 0x0101;
    private static final int WM_LBUTTONDOWN = 0x0201;
    private static final int WM_LBUTTONUP   = 0x0202;
    // MK_LBUTTON flag for wParam of mouse messages
    private static final int MK_LBUTTON = 0x0001;

    private static final double TARGET_VERTICAL_HEIGHT_BLOCKS = 4.0;

    private enum State {
        IDLE,
        WAITING_FOR_MACRO_PAUSE,
        ATTACK_BURST,
        WAITING_RECHECK,
        WAITING_FOR_MACRO_RESUME
    }

    private static State state = State.IDLE;

    /**
     * Live Polinex Gemstone Macro state, learned only from Polinex chat messages.
     * true  = Enabled / [Pause] Resumed
     * false = Disabled / [Pause] Gemstone Macro paused
     */
    private static volatile boolean gemstoneMacroEnabled = false;
    private static volatile boolean gemstoneMacroStateSeen = false;

    private static int pausedMacroKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    private static int toggleAttempts = 0;
    private static int toggleConfirmDeadlineTick = 0;
    private static int generation = 0;
    private static CompletableFuture<Void> pendingMacroToggle = CompletableFuture.completedFuture(null);

    private GoblinKnockbackManager() {}

    // ─── Chat tracking ───────────────────────────────────────────────────

    /**
     * Track Polinex's Gemstone Macro state from chat for the whole play session.
     */
    public static void onChatMessage(Component message) {
        if (message == null) return;

        String lower = normalizePolinexMessage(message.getString()).toLowerCase(Locale.ROOT);
        if (!lower.contains("[polinex]")) return;

        boolean enabledMessage = lower.contains("gemstone macro: enabled");
        boolean resumedMessage = lower.contains("[pause] resumed gemstone macro");
        boolean disabledMessage = lower.contains("gemstone macro: disabled");
        boolean pausedMessage = lower.contains("[pause]")
                && lower.contains("gemstone macro paused");

        if (enabledMessage || resumedMessage) {
            gemstoneMacroEnabled = true;
            gemstoneMacroStateSeen = true;
        } else if (disabledMessage || pausedMessage) {
            gemstoneMacroEnabled = false;
            gemstoneMacroStateSeen = true;
        }
    }

    // ─── Tick loop ───────────────────────────────────────────────────────

    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            resetSequenceOnly();
            gemstoneMacroEnabled = false;
            gemstoneMacroStateSeen = false;
            return;
        }

        if (!ConfigManager.get().goblinKnockbackEnabled) {
            resetSequenceOnly();
            return;
        }

        int macroKeyCode = ConfigManager.get().goblinKnockbackMacroKey;
        if (macroKeyCode == GLFW.GLFW_KEY_UNKNOWN) {
            resetSequenceOnly();
            return;
        }

        // While CHRC owns a pause/attack sequence, inspect the player's block EVERY tick.
        if (state == State.IDLE && client.player.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        if (!TabAreaDetector.isCrystalHollows(client)) {
            resetSequenceOnly();
            return;
        }

        // Keep tracking chat even while a GUI is open, but do not generate input.
        if (client.screen != null) return;

        boolean mobOnPlayer = hasMobOnPlayerBlock(client);

        // When the obstruction has left, cancel pending attacks and resume immediately.
        if (state != State.IDLE && !mobOnPlayer) {
            resumeIfObstructionCleared(client);
            if (state != State.WAITING_FOR_MACRO_RESUME) return;
        }

        switch (state) {
            case IDLE -> {
                if (!mobOnPlayer) return;
                if (!gemstoneMacroStateSeen || !gemstoneMacroEnabled) return;

                pausedMacroKeyCode = macroKeyCode;
                beginPauseConfirmation(client);
            }

            case WAITING_FOR_MACRO_PAUSE -> {
                if (!mobOnPlayer) {
                    if (gemstoneMacroStateSeen && !gemstoneMacroEnabled) {
                        beginResumeConfirmation(client);
                    } else {
                        resetSequenceOnly();
                    }
                    return;
                }

                if (gemstoneMacroStateSeen && !gemstoneMacroEnabled) {
                    startAttackBurst(client);
                    return;
                }

                if (client.player.tickCount >= toggleConfirmDeadlineTick) {
                    if (toggleAttempts < MAX_TOGGLE_ATTEMPTS) {
                        requestMacroToggleAttempt(client);
                    } else {
                        resetSequenceOnly();
                    }
                }
            }

            case ATTACK_BURST -> {
                // Attack clicks are time-based via PostMessage; nothing to do here.
            }

            case WAITING_RECHECK -> {
                if (mobOnPlayer) {
                    if (!gemstoneMacroStateSeen) {
                        resetSequenceOnly();
                    } else if (gemstoneMacroEnabled) {
                        pausedMacroKeyCode = macroKeyCode;
                        beginPauseConfirmation(client);
                    } else {
                        startAttackBurst(client);
                    }
                } else {
                    beginResumeConfirmation(client);
                }
            }

            case WAITING_FOR_MACRO_RESUME -> {
                if (gemstoneMacroStateSeen && gemstoneMacroEnabled) {
                    resetSequenceOnly();
                    return;
                }

                if (mobOnPlayer) {
                    if (!gemstoneMacroStateSeen) {
                        resetSequenceOnly();
                    } else if (gemstoneMacroEnabled) {
                        pausedMacroKeyCode = macroKeyCode;
                        beginPauseConfirmation(client);
                    } else {
                        startAttackBurst(client);
                    }
                    return;
                }

                if (client.player.tickCount >= toggleConfirmDeadlineTick) {
                    if (toggleAttempts < MAX_TOGGLE_ATTEMPTS) {
                        requestMacroToggleAttempt(client);
                    } else {
                        resetSequenceOnly();
                    }
                }
            }
        }
    }

    /** Called by the settings UI when the feature is turned off or reset. */
    public static void onDisabled(Minecraft client) {
        resetSequenceOnly();
    }

    // ─── State transitions ───────────────────────────────────────────────

    private static void beginPauseConfirmation(Minecraft client) {
        state = State.WAITING_FOR_MACRO_PAUSE;
        toggleAttempts = 0;
        requestMacroToggleAttempt(client);
    }

    private static void beginResumeConfirmation(Minecraft client) {
        if (!gemstoneMacroStateSeen) { resetSequenceOnly(); return; }
        if (gemstoneMacroEnabled)    { resetSequenceOnly(); return; }

        state = State.WAITING_FOR_MACRO_RESUME;
        toggleAttempts = 0;
        requestMacroToggleAttempt(client);
    }

    private static void requestMacroToggleAttempt(Minecraft client) {
        if (client == null || client.player == null) { resetSequenceOnly(); return; }

        int keyCode = pausedMacroKeyCode;
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) { resetSequenceOnly(); return; }

        toggleAttempts++;
        toggleConfirmDeadlineTick = client.player.tickCount + TOGGLE_CONFIRM_TIMEOUT_TICKS;

        long hwnd = getWin32Hwnd(client);

        // Serialize toggle sends so a retry cannot overlap a still-running key press.
        pendingMacroToggle = pendingMacroToggle
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> postMessageKeyPressAsync(keyCode, hwnd));
    }

    /** Stop outstanding clicks immediately when the target leaves the player's block. */
    private static void resumeIfObstructionCleared(Minecraft client) {
        if (state == State.IDLE) return;
        if (state == State.WAITING_FOR_MACRO_RESUME) {
            if (gemstoneMacroStateSeen && gemstoneMacroEnabled) resetSequenceOnly();
            return;
        }
        ++generation; // invalidate ALL delayed attack callbacks
        if (gemstoneMacroStateSeen && !gemstoneMacroEnabled) {
            beginResumeConfirmation(client);
        } else {
            resetSequenceOnly();
        }
    }

    // ─── Attack burst (PostMessage left-click) ───────────────────────────

    private static void startAttackBurst(Minecraft client) {
        if (!hasMobOnPlayerBlock(client)) {
            resumeIfObstructionCleared(client);
            return;
        }
        state = State.ATTACK_BURST;
        int myGeneration = ++generation;
        int firstDelay = randomClickDelayMs();
        int secondDelay = randomClickDelayMs();

        schedule(client, myGeneration, firstDelay, () -> performAttackClick(client));
        schedule(client, myGeneration, firstDelay + secondDelay, () -> {
            performAttackClick(client);
            if (generation == myGeneration && state == State.ATTACK_BURST) {
                state = State.WAITING_RECHECK;
            }
        });
    }

    /**
     * Send a left-click attack via PostMessage (WM_LBUTTONDOWN + WM_LBUTTONUP).
     * The click is sent to the center of the Minecraft window so it hits whatever
     * entity the crosshair is pointing at.
     */
    private static void performAttackClick(Minecraft client) {
        if (!isSequenceStillAllowed(client)) return;

        if (!hasMobOnPlayerBlock(client)) {
            resumeIfObstructionCleared(client);
            return;
        }
        // Attack only after Polinex has confirmed the macro is paused.
        if (!gemstoneMacroStateSeen || gemstoneMacroEnabled) return;

        long hwnd = getWin32Hwnd(client);
        if (hwnd == 0L) return;

        // Send click asynchronously to avoid blocking the client thread
        CompletableFuture.runAsync(() -> sendLeftClickViaPostMessage(hwnd, client));
    }

    /**
     * Send WM_LBUTTONDOWN followed by WM_LBUTTONUP to Minecraft's HWND.
     * lParam encodes the cursor position as MAKELPARAM(x, y) = (y << 16) | x.
     * We target the center of the window where the crosshair is.
     */
    private static void sendLeftClickViaPostMessage(long hwnd, Minecraft client) {
        // Get window center coordinates for the click position
        int centerX = 0;
        int centerY = 0;
        try {
            var window = client.getWindow();
            if (window != null) {
                // Use half the framebuffer width/height as a reasonable center
                centerX = window.getScreenWidth() / 2;
                centerY = window.getScreenHeight() / 2;
            }
        } catch (RuntimeException ignored) {
            // Default to 0,0 if we can't get the window size
        }

        final int cx = centerX;
        final int cy = centerY;

        String nativeMethods =
                "[System.Runtime.InteropServices.DllImport(\"user32.dll\", SetLastError=true)] "
                        + "public static extern bool PostMessage(System.IntPtr hWnd, uint Msg, System.UIntPtr wParam, System.IntPtr lParam);";

        // lParam = MAKELPARAM(x, y) = (y << 16) | (x & 0xFFFF)
        long lParamValue = ((long) cy << 16) | (cx & 0xFFFFL);

        String script = "$ErrorActionPreference='Stop'; "
                + "Add-Type -MemberDefinition '" + nativeMethods + "' -Name WinInput -Namespace CHRCNative -ErrorAction SilentlyContinue; "
                + "$hwnd=[System.IntPtr]::new([long]" + Long.toUnsignedString(hwnd) + "); "
                + "$lParam=[System.IntPtr]::new([long]" + lParamValue + "); "
                + "$wParamDown=[System.UIntPtr]::new([uint64]" + MK_LBUTTON + "); "
                + "$wParamUp=[System.UIntPtr]::Zero; "
                // WM_LBUTTONDOWN
                + "if (-not [CHRCNative.WinInput]::PostMessage($hwnd," + WM_LBUTTONDOWN + ",$wParamDown,$lParam)) { exit 2 }; "
                + "Start-Sleep -Milliseconds " + MOUSE_CLICK_HOLD_MS + "; "
                // WM_LBUTTONUP
                + "if (-not [CHRCNative.WinInput]::PostMessage($hwnd," + WM_LBUTTONUP + ",$wParamUp,$lParam)) { exit 3 };";

        runHiddenPowerShell(script);
    }

    // ─── PostMessage key press ───────────────────────────────────────────

    /**
     * Send a keyboard key press via PostMessage (WM_KEYDOWN + WM_KEYUP) to Minecraft's HWND.
     */
    private static CompletableFuture<Void> postMessageKeyPressAsync(int glfwKeyCode, long hwnd) {
        if (glfwKeyCode == GLFW.GLFW_KEY_UNKNOWN || hwnd == 0L) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> sendKeyViaPostMessage(glfwKeyCode, hwnd));
    }

    /**
     * Win32 PostMessage WM_KEYDOWN/WM_KEYUP with proper scan code in lParam.
     */
    private static void sendKeyViaPostMessage(int glfwKeyCode, long hwnd) {
        int virtualKey = toWindowsVirtualKey(glfwKeyCode);
        if (virtualKey < 0 || virtualKey > 255) return;

        String nativeMethods =
                "[System.Runtime.InteropServices.DllImport(\"user32.dll\", SetLastError=true)] "
                        + "public static extern bool PostMessage(System.IntPtr hWnd, uint Msg, System.UIntPtr wParam, System.IntPtr lParam); "
                        + "[System.Runtime.InteropServices.DllImport(\"user32.dll\")] "
                        + "public static extern uint MapVirtualKey(uint uCode, uint uMapType);";

        String script = "$ErrorActionPreference='Stop'; "
                + "Add-Type -MemberDefinition '" + nativeMethods + "' -Name WinKey -Namespace CHRCNative -ErrorAction SilentlyContinue; "
                + "$hwnd=[System.IntPtr]::new([long]" + Long.toUnsignedString(hwnd) + "); "
                + "$vk=[uint32]" + virtualKey + "; "
                + "$scan=[CHRCNative.WinKey]::MapVirtualKey($vk,0); "
                + "$down=[System.IntPtr]::new([long]($scan -shl 16)); "
                + "$up=[System.IntPtr]::new([long](($scan -shl 16) -bor 0xC0000001)); "
                // WM_KEYDOWN
                + "if (-not [CHRCNative.WinKey]::PostMessage($hwnd," + WM_KEYDOWN + ",[System.UIntPtr]::new([uint64]$vk),$down)) { exit 2 }; "
                + "Start-Sleep -Milliseconds " + NATIVE_KEY_HOLD_MS + "; "
                // WM_KEYUP
                + "if (-not [CHRCNative.WinKey]::PostMessage($hwnd," + WM_KEYUP + ",[System.UIntPtr]::new([uint64]$vk),$up)) { exit 3 };";

        runHiddenPowerShell(script);
    }

    // ─── PowerShell runner (hidden, no console flash) ────────────────────

    private static boolean runHiddenPowerShell(String script) {
        Path ps1File = null;
        Path vbsFile = null;
        try {
            ps1File = Files.createTempFile("chrc-pm-", ".ps1");
            vbsFile = Files.createTempFile("chrc-pm-launcher-", ".vbs");
            Files.writeString(ps1File, script, StandardCharsets.UTF_8);
            String command = "\"powershell.exe\" -NoLogo -NoProfile -NonInteractive "
                    + "-ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + ps1File.toAbsolutePath() + "\"";
            String vbs = "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                    + "exitCode = shell.Run(\"" + command.replace("\"", "\"\"") + "\", 0, True)\r\n"
                    + "WScript.Quit exitCode\r\n";
            Files.writeString(vbsFile, vbs, StandardCharsets.UTF_8);
            Process proc = new ProcessBuilder("wscript.exe", "//B", "//Nologo", vbsFile.toString())
                    .redirectErrorStream(true).start();
            try (var stream = proc.getInputStream()) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return proc.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (vbsFile != null) try { Files.deleteIfExists(vbsFile); } catch (Exception ignored) {}
            if (ps1File != null) try { Files.deleteIfExists(ps1File); } catch (Exception ignored) {}
        }
    }

    // ─── Window handle resolution ────────────────────────────────────────

    private static long getWin32Hwnd(Minecraft client) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return 0L;
        try {
            long glfwHandle = getGlfwWindowHandle(client);
            if (glfwHandle == 0L) return 0L;
            return GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
        } catch (RuntimeException | LinkageError ignored) {
            return 0L;
        }
    }

    /**
     * Resolve GLFW window handle across Minecraft mapping revisions.
     */
    private static long getGlfwWindowHandle(Minecraft client) {
        if (client == null || client.getWindow() == null) return 0L;
        Object window = client.getWindow();
        for (String methodName : new String[] {"getHandle", "handle", "getWindow"}) {
            try {
                java.lang.reflect.Method getter = window.getClass().getMethod(methodName);
                Object result = getter.invoke(window);
                if (result instanceof Number) return ((Number) result).longValue();
            } catch (ReflectiveOperationException | SecurityException ignored) {}
        }
        return 0L;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static String normalizePolinexMessage(String text) {
        if (text == null) return "";
        return text
                .replace('\u00A0', ' ')
                .replace('\u2014', '-')
                .replace('\u2013', '-')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean isSequenceStillAllowed(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.screen == null
                && ConfigManager.get().goblinKnockbackEnabled
                && ConfigManager.get().goblinKnockbackMacroKey != GLFW.GLFW_KEY_UNKNOWN
                && TabAreaDetector.isCrystalHollows(client);
    }

    private static void schedule(Minecraft client, int expectedGeneration, int delayMs, Runnable action) {
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(() ->
                client.execute(() -> {
                    if (generation != expectedGeneration) return;
                    action.run();
                }));
    }

    private static int randomClickDelayMs() {
        return ThreadLocalRandom.current().nextInt(CLICK_DELAY_MIN_MS, CLICK_DELAY_MAX_MS + 1);
    }

    // ─── GLFW → Windows Virtual Key mapping ─────────────────────────────

    private static int toWindowsVirtualKey(int glfwKey) {
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return 0x41 + (glfwKey - GLFW.GLFW_KEY_A);
        }
        if (glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9) {
            return 0x30 + (glfwKey - GLFW.GLFW_KEY_0);
        }
        if (glfwKey >= GLFW.GLFW_KEY_F1 && glfwKey <= GLFW.GLFW_KEY_F12) {
            return 0x70 + (glfwKey - GLFW.GLFW_KEY_F1);
        }
        if (glfwKey >= GLFW.GLFW_KEY_KP_0 && glfwKey <= GLFW.GLFW_KEY_KP_9) {
            return 0x60 + (glfwKey - GLFW.GLFW_KEY_KP_0);
        }

        return switch (glfwKey) {
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> 0x10;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> 0x11;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> 0x12;
            case GLFW.GLFW_KEY_PAUSE -> 0x13;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0x14;
            case GLFW.GLFW_KEY_ESCAPE -> 0x1B;
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_PRINT_SCREEN -> 0x2C;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> 0x5B;
            case GLFW.GLFW_KEY_MENU -> 0x5D;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0x6A;
            case GLFW.GLFW_KEY_KP_ADD -> 0x6B;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0x6D;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0x6E;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0x6F;
            case GLFW.GLFW_KEY_NUM_LOCK -> 0x90;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> 0x91;
            default -> -1;
        };
    }

    // ─── Mob detection (unchanged) ───────────────────────────────────────

    private static boolean hasMobOnPlayerBlock(Minecraft client) {
        Entity player = client.player;
        var playerBox = player.getBoundingBox();

        double minY = Math.floor(player.getY()) - 1;
        double maxY = minY + TARGET_VERTICAL_HEIGHT_BLOCKS;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof Player || !entity.isAlive()) continue;

            if (entity.getType() == EntityType.MAGMA_CUBE
                    || entity.getType() == EntityType.IRON_GOLEM) {
                continue;
            }

            var box = entity.getBoundingBox();

            if (isGoblinNamedEntity(entity)
                    && isInsideGoblinFallbackColumn(playerBox, box, entity.getY(), minY, maxY)) {
                return true;
            }

            if (!entity.isPickable()) continue;

            double playerCenterX = (playerBox.minX + playerBox.maxX) * 0.5;
            double playerCenterZ = (playerBox.minZ + playerBox.maxZ) * 0.5;
            double entityCenterX = (box.minX + box.maxX) * 0.5;
            double entityCenterZ = (box.minZ + box.maxZ) * 0.5;
            if (Math.floor(entityCenterX) != Math.floor(playerCenterX)
                    || Math.floor(entityCenterZ) != Math.floor(playerCenterZ)) continue;

            double entityY = entity.getY();
            if (entityY >= minY && entityY < maxY) return true;
        }
        return false;
    }

    private static boolean isGoblinNamedEntity(Entity entity) {
        String name = entity.getName().getString();
        if (name != null && name.toLowerCase(Locale.ROOT).contains("goblin")) {
            return true;
        }

        Component customName = entity.getCustomName();
        return customName != null
                && customName.getString().toLowerCase(Locale.ROOT).contains("goblin");
    }

    private static boolean isInsideGoblinFallbackColumn(
            net.minecraft.world.phys.AABB playerBox,
            net.minecraft.world.phys.AABB entityBox,
            double entityY,
            double minY,
            double maxY) {
        double playerCenterX = (playerBox.minX + playerBox.maxX) * 0.5;
        double playerCenterZ = (playerBox.minZ + playerBox.maxZ) * 0.5;

        double entityCenterX = (entityBox.minX + entityBox.maxX) * 0.5;
        double entityCenterZ = (entityBox.minZ + entityBox.maxZ) * 0.5;

        if (Math.floor(entityCenterX) != Math.floor(playerCenterX)
                || Math.floor(entityCenterZ) != Math.floor(playerCenterZ)) {
            return false;
        }

        return entityY >= minY && entityY < maxY;
    }

    private static void resetSequenceOnly() {
        ++generation;
        state = State.IDLE;
        pausedMacroKeyCode = GLFW.GLFW_KEY_UNKNOWN;
        toggleAttempts = 0;
        toggleConfirmDeadlineTick = 0;
    }
}
