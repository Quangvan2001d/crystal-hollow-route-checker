package dev.chrc.mixin;

import dev.chrc.freecam.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class FreecamMinecraftInteractionMixin {
    /** Keep any programmatic interaction ray anchored to the real player, not the detached camera. */
    @Redirect(
            method = "pick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getCameraEntity()Lnet/minecraft/world/entity/Entity;")
    )
    private Entity chrc$pickFromRealPlayer(Minecraft self) {
        if (FreecamManager.isEnabled() && self.player != null) return self.player;
        return self.getCameraEntity();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void chrc$blockManualAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft self = (Minecraft) (Object) this;
        if (FreecamManager.isEnabled() && !FreecamManager.isProgrammaticKeyDown(self.options.keyAttack)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void chrc$blockManualContinueAttack(boolean attacking, CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if (FreecamManager.isEnabled() && !FreecamManager.isProgrammaticKeyDown(self.options.keyAttack)) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void chrc$blockManualUse(CallbackInfo ci) {
        Minecraft self = (Minecraft) (Object) this;
        if (FreecamManager.isEnabled() && !FreecamManager.isProgrammaticKeyDown(self.options.keyUse)) {
            ci.cancel();
        }
    }

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void chrc$blockPickBlock(CallbackInfo ci) {
        if (FreecamManager.isEnabled()) ci.cancel();
    }
}
