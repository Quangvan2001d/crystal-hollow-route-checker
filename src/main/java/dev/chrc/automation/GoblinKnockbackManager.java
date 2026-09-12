package dev.chrc.automation;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chrc.config.ConfigManager;
import dev.chrc.hypixel.TabAreaDetector;
import dev.chrc.mixin.AccessorKeyMapping;
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

    private enum State {
        IDLE,
        ATTACK_BURST,
        WAITING_RECHECK
    }

    private static State state = State.IDLE;
    private static boolean macroPausedByUs = false;
    private static int pausedMacroKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    private static int generation = 0;

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
                    pressMacroToggle(pausedMacroKeyCode);
                    macroPausedByUs = true;
                    startAttackBurst(client);
                }
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

    private static void pressMacroToggle(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) return;
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(keyCode);
        if (key != InputConstants.UNKNOWN) {
            // Queue the same click event consumed by Minecraft/mod key mappings bound
            // to this key without generating native OS keyboard input.
            KeyMapping.click(key);
        }
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
            pressMacroToggle(pausedMacroKeyCode);
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
