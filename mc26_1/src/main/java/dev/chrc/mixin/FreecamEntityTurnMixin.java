package dev.chrc.mixin;

import dev.chrc.freecam.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class FreecamEntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void chrc$routePlayerTurnToFreecam(double yRot, double xRot, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if ((Object) this == client.player && FreecamManager.turnCamera(yRot, xRot)) {
            ci.cancel();
        }
    }
}
