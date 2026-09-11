package dev.chrc.freecam;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import dev.chrc.config.ConfigManager;
import dev.chrc.input.ChrcKeyBindings;
import dev.chrc.mixin.AccessorKeyMapping;
import dev.chrc.mixin.AccessorWindow;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Standalone CHRC port of Aether's detached-camera freecam.
 *
 * <p>The server-side player stays in place while a client-only RemotePlayer is used as
 * Minecraft's camera entity. Movement keys are read by this class while the matching
 * KeyboardInput mixin suppresses those physical inputs for the real player.</p>
 */
public final class FreecamManager {
    private static final int OBSERVED_PLAYER_UPDATE_INTERVAL = 2;
    private static final int OBSERVED_PLAYER_INTERPOLATION_STEPS = 3;

    private static boolean enabled;
    private static boolean toggleKeyWasDown;
    private static boolean renderHookRegistered;

    private static RemotePlayer cameraEntity;
    private static Entity previousCameraEntity;
    private static CameraType previousCameraType;

    private static Vec3 observedRenderPos;
    private static Vec3 observedRenderPrevPos;
    private static Vec3 observedRenderTargetPos;
    private static int observedRenderInterpolationSteps;
    private static int observedRenderTargetTick;

    private FreecamManager() {}

    public static void init() {
        if (renderHookRegistered) return;
        renderHookRegistered = true;
        LevelRenderEvents.END_EXTRACTION.register(FreecamManager::appendAnchoredPlayerRenderState);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft client) {
        if (enabled) {
            disable(client, false);
        } else {
            enable(client);
        }
    }

    public static void tick(Minecraft client) {
        pollToggleKey(client);
        if (!enabled) return;

        if (client == null || client.player == null || client.level == null) {
            disable(client, true);
            return;
        }

        if (cameraEntity == null || client.getCameraEntity() != cameraEntity) {
            cameraEntity = createCameraEntity(client.level, client.player);
            client.setCameraEntity(cameraEntity);
        }

        enforceFirstPerson(client);
        moveCamera(client);
        updateObservedRenderState(client.player);
    }


    private static void pollToggleKey(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.screen != null) {
            toggleKeyWasDown = false;
            return;
        }

        KeyMapping mapping = ChrcKeyBindings.getFreecamToggle();
        boolean down = mapping != null && isKeyDown(client, mapping);
        if (down && !toggleKeyWasDown) toggle(client);
        toggleKeyWasDown = down;
    }

    public static boolean isProgrammaticKeyDown(KeyMapping mapping) {
        Minecraft client = Minecraft.getInstance();
        return mapping != null && mapping.isDown() && !isKeyDown(client, mapping);
    }

    public static boolean isPhysicalKeyDown(Minecraft client, KeyMapping mapping) {
        return mapping != null && isKeyDown(client, mapping);
    }

    private static boolean isKeyDown(Minecraft client, KeyMapping mapping) {
        InputConstants.Key key = ((AccessorKeyMapping) mapping).chrc$getKey();
        if (key == null || key == InputConstants.UNKNOWN) return false;
        return switch (key.getType()) {
            case KEYSYM, SCANCODE -> InputConstants.isKeyDown(client.getWindow(), key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(
                    ((AccessorWindow) (Object) client.getWindow()).chrc$getHandle(),
                    key.getValue()
            ) == GLFW.GLFW_PRESS;
        };
    }

    public static boolean turnCamera(double yRot, double xRot) {
        if (!enabled || cameraEntity == null) return false;
        cameraEntity.turn(yRot, xRot);
        syncRotationState(cameraEntity, cameraEntity.getYRot(), cameraEntity.getXRot());
        return true;
    }

    public static void disableSilently(Minecraft client) {
        disable(client, true);
    }

    private static void enable(Minecraft client) {
        if (enabled || client == null || client.player == null || client.level == null) return;

        LocalPlayer player = client.player;
        enabled = true;
        previousCameraEntity = client.getCameraEntity();
        previousCameraType = client.options.getCameraType();

        cameraEntity = createCameraEntity(client.level, player);
        observedRenderPos = player.position();
        observedRenderPrevPos = observedRenderPos;
        observedRenderTargetPos = observedRenderPos;
        observedRenderInterpolationSteps = 0;
        observedRenderTargetTick = player.tickCount;

        client.setCameraEntity(cameraEntity);
        enforceFirstPerson(client);
        clearPlayerInput(player);
        send(client, "Freecam enabled!", ChatFormatting.GREEN);
    }

    private static void disable(Minecraft client, boolean silent) {
        if (!enabled && cameraEntity == null) return;

        enabled = false;
        if (client != null) {
            Entity restore = previousCameraEntity;
            if (restore == null || restore.isRemoved()) restore = client.player;
            if (restore != null) client.setCameraEntity(restore);
            restoreCameraType(client);
            if (client.player != null) clearPlayerInput(client.player);
            if (!silent) send(client, "Freecam disabled!", ChatFormatting.YELLOW);
        }

        cameraEntity = null;
        previousCameraEntity = null;
        previousCameraType = null;
        observedRenderPos = null;
        observedRenderPrevPos = null;
        observedRenderTargetPos = null;
        observedRenderInterpolationSteps = 0;
        observedRenderTargetTick = 0;
    }

    private static RemotePlayer createCameraEntity(ClientLevel level, LocalPlayer player) {
        GameProfile profile = new GameProfile(player.getUUID(), player.getName().getString());
        RemotePlayer camera = new RemotePlayer(level, profile);
        camera.setInvisible(true);
        camera.noPhysics = true;
        camera.setNoGravity(true);
        camera.setOldPosAndRot(player.position(), player.getYRot(), player.getXRot());
        camera.setPos(player.position());
        syncRotationState(camera, player.getYRot(), player.getXRot());
        camera.setDeltaMovement(Vec3.ZERO);
        return camera;
    }

    private static void moveCamera(Minecraft client) {
        if (cameraEntity == null || client.options == null) return;

        float speed = ConfigManager.get().freecamSpeed;
        if (isKeyDown(client, client.options.keySprint)) speed *= 2.0f;

        Vec3 look = cameraEntity.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z);
        if (forward.lengthSqr() < 1.0E-6) {
            double yawRad = Math.toRadians(cameraEntity.getYRot());
            forward = new Vec3(-Math.sin(yawRad), 0.0, Math.cos(yawRad));
        }
        forward = forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);

        Vec3 motion = Vec3.ZERO;
        if (isKeyDown(client, client.options.keyUp)) motion = motion.add(forward);
        if (isKeyDown(client, client.options.keyDown)) motion = motion.subtract(forward);
        if (isKeyDown(client, client.options.keyLeft)) motion = motion.subtract(right);
        if (isKeyDown(client, client.options.keyRight)) motion = motion.add(right);
        if (isKeyDown(client, client.options.keyJump)) motion = motion.add(0.0, 1.0, 0.0);
        if (isKeyDown(client, client.options.keyShift)) motion = motion.add(0.0, -1.0, 0.0);

        cameraEntity.setOldPosAndRot(cameraEntity.position(), cameraEntity.getYRot(), cameraEntity.getXRot());
        if (motion.lengthSqr() > 1.0E-6) {
            cameraEntity.setPos(cameraEntity.position().add(motion.normalize().scale(speed)));
        }
        syncRotationState(cameraEntity, cameraEntity.getYRot(), cameraEntity.getXRot());
        cameraEntity.setDeltaMovement(Vec3.ZERO);
    }

    private static void clearPlayerInput(LocalPlayer player) {
        player.setJumping(false);
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        if (player.input != null) {
            player.input.keyPresses = new Input(false, false, false, false, false, false, false);
        }
    }

    private static void updateObservedRenderState(LocalPlayer player) {
        Vec3 currentPos = player.position();
        if (observedRenderPos == null || observedRenderPrevPos == null || observedRenderTargetPos == null) {
            observedRenderPos = currentPos;
            observedRenderPrevPos = currentPos;
            observedRenderTargetPos = currentPos;
            observedRenderInterpolationSteps = 0;
            observedRenderTargetTick = player.tickCount;
            return;
        }

        observedRenderPrevPos = observedRenderPos;
        if (observedRenderTargetPos.distanceToSqr(currentPos) > 1.0E-6
                && player.tickCount - observedRenderTargetTick >= OBSERVED_PLAYER_UPDATE_INTERVAL) {
            observedRenderTargetPos = currentPos;
            observedRenderInterpolationSteps = OBSERVED_PLAYER_INTERPOLATION_STEPS;
            observedRenderTargetTick = player.tickCount;
        }

        if (observedRenderInterpolationSteps > 0) {
            double progress = 1.0 / observedRenderInterpolationSteps;
            observedRenderPos = new Vec3(
                    Mth.lerp(progress, observedRenderPos.x, observedRenderTargetPos.x),
                    Mth.lerp(progress, observedRenderPos.y, observedRenderTargetPos.y),
                    Mth.lerp(progress, observedRenderPos.z, observedRenderTargetPos.z)
            );
            observedRenderInterpolationSteps--;
        } else {
            observedRenderPos = observedRenderTargetPos;
        }
    }

    private static void appendAnchoredPlayerRenderState(LevelExtractionContext context) {
        if (!enabled) return;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || context.level() != player.level()) return;
        if (isPlayerAlreadyQueued(context, player.getId())) return;

        float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(
                !player.level().tickRateManager().isEntityFrozen(player)
        );
        EntityRenderState renderState = client.getEntityRenderDispatcher().extractEntity(player, partialTick);
        if (observedRenderPos != null && observedRenderPrevPos != null) {
            renderState.x = Mth.lerp((double) partialTick, observedRenderPrevPos.x, observedRenderPos.x);
            renderState.y = Mth.lerp((double) partialTick, observedRenderPrevPos.y, observedRenderPos.y);
            renderState.z = Mth.lerp((double) partialTick, observedRenderPrevPos.z, observedRenderPos.z);
        }
        context.levelState().entityRenderStates.add(renderState);
        if (renderState.appearsGlowing()) context.levelState().haveGlowingEntities = true;
    }

    private static boolean isPlayerAlreadyQueued(LevelExtractionContext context, int playerId) {
        for (EntityRenderState renderState : context.levelState().entityRenderStates) {
            if (renderState instanceof AvatarRenderState avatarRenderState && avatarRenderState.id == playerId) {
                return true;
            }
        }
        return false;
    }

    private static void syncRotationState(LivingEntity entity, float yaw, float pitch) {
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        entity.setYHeadRot(yaw);
        entity.yHeadRotO = yaw;
        entity.setYBodyRot(yaw);
        entity.yBodyRotO = yaw;
    }

    private static void enforceFirstPerson(Minecraft client) {
        if (client.options.getCameraType() != CameraType.FIRST_PERSON) {
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    private static void restoreCameraType(Minecraft client) {
        if (client != null && previousCameraType != null) {
            client.options.setCameraType(previousCameraType);
        }
    }

    private static void send(Minecraft client, String text, ChatFormatting color) {
        if (client != null && client.gui != null) {
            client.gui.getChat().addClientSystemMessage(
                    Component.literal("[CHRC] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(text).withStyle(color))
            );
        }
    }
}
