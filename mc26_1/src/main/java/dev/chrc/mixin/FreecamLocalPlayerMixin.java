package dev.chrc.mixin;

import dev.chrc.freecam.FreecamManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
public abstract class FreecamLocalPlayerMixin {
    @Shadow
    protected abstract boolean isControlledCamera();

    @Redirect(
            method = "applyInput",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z")
    )
    private boolean chrc$keepRealPlayerInputTicking(LocalPlayer player) {
        if (FreecamManager.isEnabled() && player == Minecraft.getInstance().player) return true;
        return this.isControlledCamera();
    }

    @Redirect(
            method = "sendPosition",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z")
    )
    private boolean chrc$keepRealPlayerPositionSync(LocalPlayer player) {
        if (FreecamManager.isEnabled() && player == Minecraft.getInstance().player) return true;
        return this.isControlledCamera();
    }
}
