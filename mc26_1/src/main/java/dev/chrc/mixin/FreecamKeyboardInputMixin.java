package dev.chrc.mixin;

import dev.chrc.freecam.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class FreecamKeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void chrc$freezeManualPlayerInput(CallbackInfo ci) {
        if (!FreecamManager.isEnabled()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.options == null) return;

        // Physical movement drives the detached camera. Only programmatically-held
        // mappings (for compatibility with other client mods) are allowed through
        // to the real player, matching Aether's freecam behavior.
        boolean forward = FreecamManager.isProgrammaticKeyDown(client.options.keyUp);
        boolean backward = FreecamManager.isProgrammaticKeyDown(client.options.keyDown);
        boolean left = FreecamManager.isProgrammaticKeyDown(client.options.keyLeft);
        boolean right = FreecamManager.isProgrammaticKeyDown(client.options.keyRight);
        boolean jump = FreecamManager.isProgrammaticKeyDown(client.options.keyJump);
        boolean shift = FreecamManager.isProgrammaticKeyDown(client.options.keyShift);
        boolean sprint = FreecamManager.isProgrammaticKeyDown(client.options.keySprint);

        this.keyPresses = new Input(forward, backward, left, right, jump, shift, sprint);
        this.moveVector = new Vec2(
                chrc$calculateImpulse(left, right),
                chrc$calculateImpulse(forward, backward)
        ).normalized();
    }

    @Unique
    private static float chrc$calculateImpulse(boolean positive, boolean negative) {
        if (positive == negative) return 0.0F;
        return positive ? 1.0F : -1.0F;
    }
}
