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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
    private static final int TOGGLE_CONFIRM_TIMEOUT_TICKS = 20;
    private static final int MAX_TOGGLE_ATTEMPTS = 3;

    // Hypixel can represent a visible mob with stacked/offset entities. Keep the X/Z
    // obstruction check narrow, and use one shared vertical band for every candidate.
    private static final double TARGET_HORIZONTAL_PADDING = 0.25;

    // Vertical detection starts at the block immediately below the player's feet and
    // extends upward for exactly four blocks. Using the entity's feet/origin Y prevents
    // a tall mob on the floor below from being counted just because its AABB reaches up.
    private static final double TARGET_VERTICAL_HEIGHT_BLOCKS = 4.0;

    // Golden Goblins on Hypixel can be built from multiple stacked/custom entities.
    // Keep a targeted fallback for entities whose displayed name contains "Goblin"
    // instead of widening the generic mob scan for everything.
    private static final double GOBLIN_FALLBACK_HORIZONTAL_RADIUS = 1.35;

    private static volatile Robot nativeKeyboardRobot;
    private static volatile boolean nativeKeyboardUnavailable;

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

    /**
     * Track Polinex's Gemstone Macro state from chat for the whole play session.
     *
     * These are the only messages that change the state:
     *   true  <- "[Polinex] » Gemstone Macro: Enabled"
     *            "[Polinex] » [Pause] Resumed Gemstone Macro."
     *   false <- "[Polinex] » Gemstone Macro: Disabled"
     *            "[Polinex] » [Pause] Gemstone Macro paused — ..."
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

        if (client.player.tickCount % CHECK_INTERVAL_TICKS != 0) return;

        if (!TabAreaDetector.isCrystalHollows(client)) {
            resetSequenceOnly();
            return;
        }

        // Keep tracking Polinex chat even while a GUI is open, but do not generate
        // combat/toggle input until the GUI has closed.
        if (client.screen != null) return;

        boolean mobOnPlayer = hasMobOnPlayerBlock(client);

        switch (state) {
            case IDLE -> {
                if (!mobOnPlayer) return;

                // Goblin Killer may START only while Polinex has explicitly confirmed that
                // Gemstone Macro is currently running. If the macro is manually disabled or
                // paused, seeing a goblin must not generate any toggle/attack input.
                //
                // Once we start the sequence, CHRC itself pauses the macro; the later states
                // are therefore allowed to continue while gemstoneMacroEnabled == false.
                if (!gemstoneMacroStateSeen || !gemstoneMacroEnabled) return;

                pausedMacroKeyCode = macroKeyCode;
                beginPauseConfirmation(client);
            }

            case WAITING_FOR_MACRO_PAUSE -> {
                if (!mobOnPlayer) {
                    // The goblin moved away before pause confirmation arrived.
                    if (gemstoneMacroStateSeen && !gemstoneMacroEnabled) {
                        beginResumeConfirmation(client);
                    } else {
                        resetSequenceOnly();
                    }
                    return;
                }

                // Never attack until Polinex itself confirms the macro is no longer running.
                if (gemstoneMacroStateSeen && !gemstoneMacroEnabled) {
                    startAttackBurst(client);
                    return;
                }

                if (client.player.tickCount >= toggleConfirmDeadlineTick) {
                    if (toggleAttempts < MAX_TOGGLE_ATTEMPTS) {
                        requestMacroToggleAttempt(client);
                    } else {
                        // Three key presses without a Disabled/Paused confirmation:
                        // abort instead of attacking while the mining macro may still be on.
                        resetSequenceOnly();
                    }
                }
            }

            case ATTACK_BURST -> {
                // The two click pulses are time-based so their spacing stays 180-220 ms.
                // performAttackClick() also verifies the chat-tracked macro state is false.
            }

            case WAITING_RECHECK -> {
                if (mobOnPlayer) {
                    if (!gemstoneMacroStateSeen) {
                        resetSequenceOnly();
                    } else if (gemstoneMacroEnabled) {
                        // The macro somehow resumed while the goblin is still here.
                        // Pause it again and wait for chat confirmation before more attacks.
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
                // Finish only after Polinex confirms Enabled/Resumed.
                if (gemstoneMacroStateSeen && gemstoneMacroEnabled) {
                    resetSequenceOnly();
                    return;
                }

                // If the goblin comes back while we are trying to resume, stop trying to
                // resume and return to combat logic. Any attack still requires macro=false.
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
                        // User requested a hard stop after at most three toggle presses.
                        resetSequenceOnly();
                    }
                }
            }
        }
    }

    /** Called by the settings UI when the feature is turned off or reset. */
    public static void onDisabled(Minecraft client) {
        // Disabling this CHRC feature means stop generating input immediately.
        // Macro state tracking continues independently through onChatMessage().
        resetSequenceOnly();
    }

    private static void beginPauseConfirmation(Minecraft client) {
        state = State.WAITING_FOR_MACRO_PAUSE;
        toggleAttempts = 0;
        requestMacroToggleAttempt(client);
    }

    private static void beginResumeConfirmation(Minecraft client) {
        if (!gemstoneMacroStateSeen) {
            resetSequenceOnly();
            return;
        }

        if (gemstoneMacroEnabled) {
            resetSequenceOnly();
            return;
        }

        state = State.WAITING_FOR_MACRO_RESUME;
        toggleAttempts = 0;
        requestMacroToggleAttempt(client);
    }

    private static void requestMacroToggleAttempt(Minecraft client) {
        if (client == null || client.player == null) {
            resetSequenceOnly();
            return;
        }

        int keyCode = pausedMacroKeyCode;
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            resetSequenceOnly();
            return;
        }

        toggleAttempts++;
        toggleConfirmDeadlineTick = client.player.tickCount + TOGGLE_CONFIRM_TIMEOUT_TICKS;

        // Serialize native key sends so a retry cannot overlap a still-running helper process.
        pendingMacroToggle = pendingMacroToggle
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> pressMacroToggleAsync(keyCode));
    }

    private static String normalizePolinexMessage(String text) {
        if (text == null) return "";
        return text
                .replace('\u00A0', ' ')
                .replace('\u2014', '-')
                .replace('\u2013', '-')
                .trim()
                .replaceAll("\\s+", " ");
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

        // A click is allowed only while Polinex chat says the Gemstone Macro is stopped.
        // This prevents attacking from racing ahead of the macro's own pause confirmation.
        if (!gemstoneMacroStateSeen || gemstoneMacroEnabled) return;

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
     * Detect an entity that can actually be targeted by an attack and is overlapping the
     * player's horizontal space. Hypixel may build a visible Goblin from stacked/offset
     * entities, so requiring `instanceof Mob` or an exact integer Y match is too strict.
     *
     * The Y window intentionally tolerates entities whose logical origin floats above the
     * ground while their visible model/hitbox is effectively on top of the player.
     */
    private static boolean hasMobOnPlayerBlock(Minecraft client) {
        Entity player = client.player;
        var playerBox = player.getBoundingBox();

        double minX = playerBox.minX - TARGET_HORIZONTAL_PADDING;
        double maxX = playerBox.maxX + TARGET_HORIZONTAL_PADDING;
        double minZ = playerBox.minZ - TARGET_HORIZONTAL_PADDING;
        double maxZ = playerBox.maxZ + TARGET_HORIZONTAL_PADDING;

        // Example: player feet at Y=64.0 -> block immediately below is Y=63.
        // Valid entity feet/origin Y is [63, 67): exactly four vertical blocks.
        double minY = Math.floor(playerBox.minY - 0.01);
        double maxY = minY + TARGET_VERTICAL_HEIGHT_BLOCKS;

        for (Entity entity : client.level.entitiesForRendering()) {
            // Never treat any player (local or remote) as a Goblin/mob target.
            if (entity instanceof Player || !entity.isAlive()) continue;

            var box = entity.getBoundingBox();

            // Hypixel's Golden Goblin may use an ArmorStand/display/passenger for the
            // visible model/name while the real collision/attack entity is offset above
            // it.  Such helper entities are not guaranteed to be pickable, so detect the
            // named Goblin first using a narrow X/Z column around the player.
            if (isGoblinNamedEntity(entity)
                    && isInsideGoblinFallbackColumn(playerBox, box, entity.getY(), minY, maxY)) {
                return true;
            }

            // Generic mobs still use the stricter rule: they must be pickable and overlap
            // the player's X/Z obstruction area. Y is intentionally checked from the
            // entity's feet/origin rather than AABB overlap so a mob one floor below
            // cannot trigger merely because its head/hitbox reaches upward.
            if (!entity.isPickable()) continue;

            boolean overlapsHorizontally =
                    box.maxX >= minX && box.minX <= maxX
                            && box.maxZ >= minZ && box.minZ <= maxZ;
            if (!overlapsHorizontally) continue;

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

        // A zero/tiny marker box is common for display/name entities.  In that case its
        // position is more useful than requiring an AABB intersection.
        double entityCenterX = (entityBox.minX + entityBox.maxX) * 0.5;
        double entityCenterZ = (entityBox.minZ + entityBox.maxZ) * 0.5;
        double dx = entityCenterX - playerCenterX;
        double dz = entityCenterZ - playerCenterZ;
        if (dx * dx + dz * dz
                > GOBLIN_FALLBACK_HORIZONTAL_RADIUS * GOBLIN_FALLBACK_HORIZONTAL_RADIUS) {
            return false;
        }

        // Use the same exact four-block vertical band as the generic detector.
        // Do not use AABB Y overlap here: a helper/hitbox from the floor below can be
        // tall enough to cross the band even though its actual origin is below it.
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
