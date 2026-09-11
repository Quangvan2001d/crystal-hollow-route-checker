package dev.chrc.mixin;

import dev.chrc.freecam.FreecamManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class FreecamGameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void chrc$hideHeldItem(CameraRenderState cameraRenderState, float partialTick, Matrix4fc matrix, CallbackInfo ci) {
        if (FreecamManager.isEnabled()) ci.cancel();
    }
}
