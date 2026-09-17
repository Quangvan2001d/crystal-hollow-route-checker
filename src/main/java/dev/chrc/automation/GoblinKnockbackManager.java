package dev.chrc.automation;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chrc.config.ConfigManager;
import dev.chrc.hypixel.TabAreaDetector;
import dev.chrc.mixin.AccessorKeyMapping;
import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.lwjgl.glfw.GLFW;

/**
 * Temporarily pauses an externally-bound mining macro when a Crystal Hollows mob
 * occupies the same block as the local player, then sends short attack bursts until
 * the mob leaves that block.
 */
public final class GoblinKnockbackManager {
    private static final int CHECK_INTERVAL_TICKS = 5;
    private static final int CLICK_DELAY_MIN_MS = 180;
    private static final int CLICK_DELAY_MAX_MS = 220;
    private static final int NATIVE_KEY_HOLD_MS = 55;

    private static volatile Robot nativeKeyboardRobot;
    private static volatile boolean nativeKeyboardUnavailable;

    private enum State {
        IDLE,
        PAUSING_MACRO,
        ATTACK_BURST,
        WAITING_RECHECK
    }

    private static State state = State.IDLE;
    private static boolean macroPausedByUs = false;
    private static int pausedMacroKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    private static int generation = 0;
    private static CompletableFuture<Void> pendingMacroToggle = CompletableFuture.completedFuture(null);

    private GoblinKnockbackManager() {}

    public static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            resetWithoutToggle();
            return;
        }

        if (!ConfigManager.get().goblinKnockbackEnabled) {
            resumeAndReset(client);
            return;
        }

        int macroKeyCode = ConfigManager.get().goblinKnockbackMacroKey;
        if (macroKeyCode == GLFW.GLFW_KEY_UNKNOWN) {
            resumeAndReset(client);
            return;
        }

        if (client.player.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        if (!TabAreaDetector.isCrystalHollows(client)) {
            resumeAndReset(client);
            return;
        }

        // Pause the helper while any GUI is open. This prevents config/inventory clicks
        // from becoming combat clicks. An in-progress sequence resumes after the GUI closes.
        if (client.screen != null) return;

        switch (state) {
            case IDLE -> {
                if (hasMobOnPlayerBlock(client)) {
                    pausedMacroKeyCode = macroKeyCode;
                    macroPausedByUs = true;
                    state = State.PAUSING_MACRO;

                    int pauseGeneration = ++generation;
                    pendingMacroToggle = pressMacroToggleAsync(pausedMacroKeyCode);
                    pendingMacroToggle.whenComplete((ignored, error) -> client.execute(() -> {
                        if (generation != pauseGeneration || state != State.PAUSING_MACRO) return;
                        if (!isSequenceStillAllowed(client)) {
                            resumeAndReset(client);
                            return;
                        }
                        startAttackBurst(client);
                    }));
                }
            }
            case PAUSING_MACRO -> {
                // Wait until the external/native key helper has completed before attacking.
            }
            case ATTACK_BURST -> {
                // The two click pulses are time-based so their spacing stays 180-220 ms.
            }
            case WAITING_RECHECK -> {
                if (hasMobOnPlayerBlock(client)) {
                    startAttackBurst(client);
                } else {
                    resumeAndReset(client);
                }
            }
        }
    }

    /** Called by the settings UI when the feature is turned off or reset. */
    public static void onDisabled(Minecraft client) {
        resumeAndReset(client);
    }

    private static void startAttackBurst(Minecraft client) {
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

    private static void performAttackClick(Minecraft client) {
        if (!isSequenceStillAllowed(client)) return;
        InputConstants.Key attackKey = ((AccessorKeyMapping) (Object) client.options.keyAttack).chrc$getKey();
        if (attackKey != null && attackKey != InputConstants.UNKNOWN) {
            KeyMapping.click(attackKey);
        }
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
        java.util.concurrent.CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(() ->
                client.execute(() -> {
                    if (generation != expectedGeneration) return;
                    action.run();
                }));
    }

    private static int randomClickDelayMs() {
        return ThreadLocalRandom.current().nextInt(CLICK_DELAY_MIN_MS, CLICK_DELAY_MAX_MS + 1);
    }

    private static CompletableFuture<Void> pressMacroToggleAsync(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            // On Windows, send the toggle from a separate OS process first. This avoids
            // generating the key from inside the Minecraft/JVM process, which some macro
            // mods intercept or suppress. The helper still uses a synthetic Win32 key
            // event, so a macro that explicitly rejects all injected input may still ignore it.
            if (sendKeyFromExternalWindowsProcess(keyCode)) return;

            // Fallback for non-Windows systems or if PowerShell cannot be launched.
            pressMacroToggleInProcess(keyCode);
        });
    }

    private static boolean sendKeyFromExternalWindowsProcess(int glfwKeyCode) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) return false;

        int virtualKey = toWindowsVirtualKey(glfwKeyCode);
        if (virtualKey < 0 || virtualKey > 255) return false;

        String memberDefinition =
                "[System.Runtime.InteropServices.DllImport(\"user32.dll\")] "
                        + "public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, System.UIntPtr dwExtraInfo);";
        String script =
                "$ErrorActionPreference='Stop'; "
                        + "Add-Type -MemberDefinition '" + memberDefinition + "' -Name Keyboard -Namespace CHRCNative; "
                        + "[CHRCNative.Keyboard]::keybd_event([byte]" + virtualKey + ",0,0,[System.UIntPtr]::Zero); "
                        + "Start-Sleep -Milliseconds " + NATIVE_KEY_HOLD_MS + "; "
                        + "[CHRCNative.Keyboard]::keybd_event([byte]" + virtualKey + ",0,2,[System.UIntPtr]::Zero);";

        Path ps1File = null;
        Path vbsFile = null;
        try {
            // Starting powershell.exe directly can briefly flash a console window before
            // -WindowStyle Hidden is processed. Launch it through WScript.Shell instead:
            // Run(..., 0, true) creates the PowerShell child with a hidden window and waits
            // for it to finish, so no console should appear on screen.
            ps1File = Files.createTempFile("chrc-key-", ".ps1");
            vbsFile = Files.createTempFile("chrc-key-launcher-", ".vbs");
            Files.writeString(ps1File, script, StandardCharsets.UTF_8);

            String powerShellCommand =
                    "\"powershell.exe\" -NoLogo -NoProfile -NonInteractive "
                            + "-ExecutionPolicy Bypass -WindowStyle Hidden -File \""
                            + ps1File.toAbsolutePath() + "\"";
            String vbsScript =
                    "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                            + "exitCode = shell.Run(\""
                            + powerShellCommand.replace("\"", "\"\"")
                            + "\", 0, True)\r\n"
                            + "WScript.Quit exitCode\r\n";
            Files.writeString(vbsFile, vbsScript, StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(
                    "wscript.exe",
                    "//B",
                    "//Nologo",
                    vbsFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            // WScript is a GUI host, so it does not create a console window. //B also
            // suppresses script error dialogs. Drain any inherited output defensively.
            try (var stream = process.getInputStream()) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (vbsFile != null) {
                try { Files.deleteIfExists(vbsFile); } catch (Exception ignored) {}
            }
            if (ps1File != null) {
                try { Files.deleteIfExists(ps1File); } catch (Exception ignored) {}
            }
        }
    }

    private static void pressMacroToggleInProcess(int keyCode) {
        int awtKeyCode = toAwtKeyCode(keyCode);
        Robot robot = getNativeKeyboardRobot();
        if (robot != null && awtKeyCode != KeyEvent.VK_UNDEFINED) {
            try {
                robot.keyPress(awtKeyCode);
                Thread.sleep(NATIVE_KEY_HOLD_MS);
                robot.keyRelease(awtKeyCode);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    robot.keyRelease(awtKeyCode);
                } catch (RuntimeException ignored) {
                }
            } catch (RuntimeException ignored) {
                // Fall through to Minecraft's internal key click.
            }
        }

        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(keyCode);
        if (key != InputConstants.UNKNOWN) {
            KeyMapping.click(key);
        }
    }

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

    private static Robot getNativeKeyboardRobot() {
        if (nativeKeyboardUnavailable) return null;
        Robot robot = nativeKeyboardRobot;
        if (robot != null) return robot;

        synchronized (GoblinKnockbackManager.class) {
            if (nativeKeyboardRobot != null) return nativeKeyboardRobot;
            if (nativeKeyboardUnavailable) return null;
            try {
                nativeKeyboardRobot = new Robot();
                nativeKeyboardRobot.setAutoDelay(0);
                return nativeKeyboardRobot;
            } catch (AWTException | RuntimeException e) {
                nativeKeyboardUnavailable = true;
                return null;
            }
        }
    }

    private static int toAwtKeyCode(int glfwKey) {
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return KeyEvent.VK_A + (glfwKey - GLFW.GLFW_KEY_A);
        }
        if (glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9) {
            return KeyEvent.VK_0 + (glfwKey - GLFW.GLFW_KEY_0);
        }
        if (glfwKey >= GLFW.GLFW_KEY_F1 && glfwKey <= GLFW.GLFW_KEY_F12) {
            return KeyEvent.VK_F1 + (glfwKey - GLFW.GLFW_KEY_F1);
        }
        if (glfwKey >= GLFW.GLFW_KEY_KP_0 && glfwKey <= GLFW.GLFW_KEY_KP_9) {
            return KeyEvent.VK_NUMPAD0 + (glfwKey - GLFW.GLFW_KEY_KP_0);
        }

        return switch (glfwKey) {
            case GLFW.GLFW_KEY_SPACE -> KeyEvent.VK_SPACE;
            case GLFW.GLFW_KEY_APOSTROPHE -> KeyEvent.VK_QUOTE;
            case GLFW.GLFW_KEY_COMMA -> KeyEvent.VK_COMMA;
            case GLFW.GLFW_KEY_MINUS -> KeyEvent.VK_MINUS;
            case GLFW.GLFW_KEY_PERIOD -> KeyEvent.VK_PERIOD;
            case GLFW.GLFW_KEY_SLASH -> KeyEvent.VK_SLASH;
            case GLFW.GLFW_KEY_SEMICOLON -> KeyEvent.VK_SEMICOLON;
            case GLFW.GLFW_KEY_EQUAL -> KeyEvent.VK_EQUALS;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> KeyEvent.VK_OPEN_BRACKET;
            case GLFW.GLFW_KEY_BACKSLASH -> KeyEvent.VK_BACK_SLASH;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> KeyEvent.VK_CLOSE_BRACKET;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> KeyEvent.VK_BACK_QUOTE;
            case GLFW.GLFW_KEY_ESCAPE -> KeyEvent.VK_ESCAPE;
            case GLFW.GLFW_KEY_ENTER -> KeyEvent.VK_ENTER;
            case GLFW.GLFW_KEY_TAB -> KeyEvent.VK_TAB;
            case GLFW.GLFW_KEY_BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case GLFW.GLFW_KEY_INSERT -> KeyEvent.VK_INSERT;
            case GLFW.GLFW_KEY_DELETE -> KeyEvent.VK_DELETE;
            case GLFW.GLFW_KEY_RIGHT -> KeyEvent.VK_RIGHT;
            case GLFW.GLFW_KEY_LEFT -> KeyEvent.VK_LEFT;
            case GLFW.GLFW_KEY_DOWN -> KeyEvent.VK_DOWN;
            case GLFW.GLFW_KEY_UP -> KeyEvent.VK_UP;
            case GLFW.GLFW_KEY_PAGE_UP -> KeyEvent.VK_PAGE_UP;
            case GLFW.GLFW_KEY_PAGE_DOWN -> KeyEvent.VK_PAGE_DOWN;
            case GLFW.GLFW_KEY_HOME -> KeyEvent.VK_HOME;
            case GLFW.GLFW_KEY_END -> KeyEvent.VK_END;
            case GLFW.GLFW_KEY_CAPS_LOCK -> KeyEvent.VK_CAPS_LOCK;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> KeyEvent.VK_SCROLL_LOCK;
            case GLFW.GLFW_KEY_NUM_LOCK -> KeyEvent.VK_NUM_LOCK;
            case GLFW.GLFW_KEY_PRINT_SCREEN -> KeyEvent.VK_PRINTSCREEN;
            case GLFW.GLFW_KEY_PAUSE -> KeyEvent.VK_PAUSE;
            case GLFW.GLFW_KEY_KP_DECIMAL -> KeyEvent.VK_DECIMAL;
            case GLFW.GLFW_KEY_KP_DIVIDE -> KeyEvent.VK_DIVIDE;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> KeyEvent.VK_MULTIPLY;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> KeyEvent.VK_SUBTRACT;
            case GLFW.GLFW_KEY_KP_ADD -> KeyEvent.VK_ADD;
            case GLFW.GLFW_KEY_KP_ENTER -> KeyEvent.VK_ENTER;
            case GLFW.GLFW_KEY_KP_EQUAL -> KeyEvent.VK_EQUALS;
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> KeyEvent.VK_SHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> KeyEvent.VK_CONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> KeyEvent.VK_ALT;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> KeyEvent.VK_WINDOWS;
            case GLFW.GLFW_KEY_MENU -> KeyEvent.VK_CONTEXT_MENU;
            default -> KeyEvent.VK_UNDEFINED;
        };
    }

    /**
     * Treat any real Minecraft AI mob on the player's feet block as a knockback target.
     * This deliberately does not use the entity name, so Hypixel goblins represented by
     * vanilla mobs are still detected. Players and ArmorStand/name-tag entities are not
     * instances of Mob and therefore do not trigger the helper.
     */
    private static boolean hasMobOnPlayerBlock(Minecraft client) {
        int playerX = floor(client.player.getX());
        int playerY = floor(client.player.getY());
        int playerZ = floor(client.player.getZ());

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;

            int entityX = floor(entity.getX());
            int entityY = floor(entity.getY());
            int entityZ = floor(entity.getZ());
            if (entityX == playerX && entityY == playerY && entityZ == playerZ) {
                return true;
            }
        }
        return false;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static void resumeAndReset(Minecraft client) {
        ++generation;
        if (macroPausedByUs && client != null && client.player != null && client.level != null) {
            int keyToResume = pausedMacroKeyCode;
            CompletableFuture<Void> previousToggle = pendingMacroToggle;
            pendingMacroToggle = previousToggle
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> pressMacroToggleAsync(keyToResume));
        }
        state = State.IDLE;
        macroPausedByUs = false;
        pausedMacroKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    }

    private static void resetWithoutToggle() {
        ++generation;
        state = State.IDLE;
        macroPausedByUs = false;
        pausedMacroKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    }
}
